/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import android.graphics.Bitmap
import com.facebook.common.internal.ImmutableMap
import com.facebook.common.internal.Supplier
import com.facebook.common.logging.FLog
import com.facebook.common.memory.ByteArrayPool
import com.facebook.common.references.CloseableReference
import com.facebook.common.util.ExceptionWithNoStacktrace
import com.facebook.common.util.UriUtil
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.imageformat.DefaultImageFormats
import com.facebook.imagepipeline.cache.CacheMissException
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.core.CloseableReferenceFactory
import com.facebook.imagepipeline.core.DownsampleMode
import com.facebook.imagepipeline.decoder.DecodeException
import com.facebook.imagepipeline.decoder.ImageDecoder
import com.facebook.imagepipeline.decoder.ProgressiveJpegConfig
import com.facebook.imagepipeline.decoder.ProgressiveJpegParser
import com.facebook.imagepipeline.image.CloseableBitmap
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.image.ImmutableQualityInfo
import com.facebook.imagepipeline.image.QualityInfo
import com.facebook.imagepipeline.producers.JobScheduler.JobRunnable
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.request.ImageRequestBuilder
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection
import com.facebook.imagepipeline.transcoder.DownsampleUtil
import com.facebook.imageutils.BitmapUtil
import java.lang.Exception
import java.util.HashMap
import java.util.concurrent.Executor
import javax.annotation.concurrent.GuardedBy

/**
 * Decodes images.
 *
 * Progressive JPEGs are decoded progressively as new data arrives.
 */
class DecodeProducer(
    val byteArrayPool: ByteArrayPool,
    val executor: Executor,
    val imageDecoder: ImageDecoder,
    val progressiveJpegConfig: ProgressiveJpegConfig,
    val downsampleMode: DownsampleMode,
    val downsampleEnabledForNetwork: Boolean,
    val decodeCancellationEnabled: Boolean,
    val inputProducer: Producer<EncodedImage>,
    val maxBitmapDimension: Int,
    val closeableReferenceFactory: CloseableReferenceFactory,
    val reclaimMemoryRunnable: Runnable?,
    val recoverFromDecoderOOM: Supplier<Boolean>,
    val decodedOriginalImageAnalyzers: Set<DecodedOriginalImageAnalyzer>? = null,
) : Producer<CloseableReference<CloseableImage>> {

  fun interface DecodedOriginalImageAnalyzer {
    fun analyze(
        context: ProducerContext,
        imageRef: CloseableReference<CloseableImage>,
        encodedImage: EncodedImage,
    )
  }

  override fun produceResults(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      context: ProducerContext,
  ): Unit =
      traceSection("DecodeProducer#produceResults") {
        val imageRequest = context.imageRequest
        val progressiveDecoder =
            if (
                !UriUtil.isNetworkUri(imageRequest.sourceUri) &&
                    !ImageRequestBuilder.isCustomNetworkUri(imageRequest.sourceUri)
            ) {
              LocalImagesProgressiveDecoder(
                  consumer,
                  context,
                  this.decodeCancellationEnabled,
                  this.maxBitmapDimension,
              )
            } else {
              val jpegParser = ProgressiveJpegParser(this.byteArrayPool)
              NetworkImagesProgressiveDecoder(
                  consumer,
                  context,
                  jpegParser,
                  this.progressiveJpegConfig,
                  this.decodeCancellationEnabled,
                  this.maxBitmapDimension,
              )
            }
        this.inputProducer.produceResults(progressiveDecoder, context)
      }

  private abstract inner class ProgressiveDecoder(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      private val producerContext: ProducerContext,
      decodeCancellationEnabled: Boolean,
      maxBitmapDimension: Int,
  ) : DelegatingConsumer<EncodedImage, CloseableReference<CloseableImage>>(consumer) {
    private val TAG = "ProgressiveDecoder"
    private val producerListener: ProducerListener2 = producerContext.producerListener
    private val imageDecodeOptions: ImageDecodeOptions =
        producerContext.imageRequest.imageDecodeOptions

    /** @return true if producer is finished */
    @get:Synchronized @GuardedBy("this") private var isFinished: Boolean = false
    private val jobScheduler: JobScheduler
    var lastScheduledScanNumber = 0

    private fun maybeIncreaseSampleSize(encodedImage: EncodedImage) {
      if (encodedImage.imageFormat !== DefaultImageFormats.JPEG) {
        return
      }
      val pixelSize = BitmapUtil.getPixelSizeForBitmapConfig(imageDecodeOptions.bitmapConfig)
      val sampleSize =
          DownsampleUtil.determineSampleSizeJPEG(encodedImage, pixelSize, MAX_BITMAP_SIZE)
      encodedImage.sampleSize = sampleSize
    }

    override fun onNewResultImpl(newResult: EncodedImage?, @Consumer.Status status: Int): Unit =
        traceSection("DecodeProducer#onNewResultImpl") {
          val isLast = isLast(status)
          if (isLast) {
            if (newResult == null) {
              val cacheHit =
                  producerContext.getExtra<Boolean>(ProducerConstants.EXTRA_CACHED_VALUE_FOUND) ==
                      true
              val fullFetch =
                  producerContext.lowestPermittedRequestLevel ==
                      ImageRequest.RequestLevel.FULL_FETCH

              val exception =
                  if (
                      !fullFetch &&
                          !cacheHit &&
                          producerContext.imagePipelineConfig.experiments
                              .throwCacheMissExceptionOnCacheMiss
                  )
                      CacheMissException("Image not found in cache")
                  else ExceptionWithNoStacktrace("Encoded image is null.")
              handleError(exception)
              return
            } else if (!newResult.isValid) {
              handleError(ExceptionWithNoStacktrace("Encoded image is not valid."))
              return
            }
          }
          if (!updateDecodeJob(newResult, status)) {
            return
          }
          val isPlaceholder = statusHasFlag(status, Consumer.IS_PLACEHOLDER)
          val scheduleIntermediateResult =
              producerContext.isIntermediateResultExpected &&
                  (!producerContext.imagePipelineConfig.experiments
                      .skipNonJpegIntermediateDecodeScheduling ||
                      newResult?.imageFormat === DefaultImageFormats.JPEG)
          if (isLast || isPlaceholder || scheduleIntermediateResult) {
            jobScheduler.scheduleJob()
          }
        }

    override fun onProgressUpdateImpl(progress: Float) {
      super.onProgressUpdateImpl(progress * 0.99f)
    }

    override fun onFailureImpl(t: Throwable) {
      handleError(t)
    }

    override fun onCancellationImpl() {
      handleCancellation()
    }

    /** Updates the decode job. */
    open fun updateDecodeJob(ref: EncodedImage?, @Consumer.Status status: Int): Boolean =
        jobScheduler.updateJob(ref, status)

    /** Performs the decode synchronously. */
    private fun doDecode(
        encodedImage: EncodedImage,
        @Consumer.Status status: Int,
        lastScheduledScanNumber: Int,
    ) {
      // do not run for partial results of anything except JPEG
      var newStatus = status
      if (encodedImage.imageFormat !== DefaultImageFormats.JPEG && isNotLast(status)) {
        return
      }
      if (isFinished || !EncodedImage.isValid(encodedImage)) {
        return
      }
      if (
          encodedImage.imageFormat == DefaultImageFormats.GIF &&
              isTooBig(encodedImage, imageDecodeOptions)
      ) {
        val e = IllegalStateException(
            "Image is too big to attempt decoding: w = ${encodedImage.width}, h = ${encodedImage.height}, pixel config = ${imageDecodeOptions.bitmapConfig}, max bitmap size = $MAX_BITMAP_SIZE",
        )
        producerListener.onProducerFinishWithFailure(producerContext, PRODUCER_NAME, e, null)
        handleError(e)
        return
      }
      val imageFormat = encodedImage.imageFormat
      val imageFormatStr = imageFormat.name
      val encodedImageSize = encodedImage.width.toString() + "x" + encodedImage.height
      val sampleSize = encodedImage.sampleSize.toString()
      val isLast = isLast(status)
      val isLastAndComplete = isLast && !statusHasFlag(status, Consumer.IS_PARTIAL_RESULT)
      val isPlaceholder = statusHasFlag(status, Consumer.IS_PLACEHOLDER)
      val resizeOptions = producerContext.imageRequest.resizeOptions
      val requestedSizeStr =
          if (resizeOptions != null) {
            resizeOptions.width.toString() + "x" + resizeOptions.height
          } else {
            "unknown"
          }
      try {
        val queueTime = jobScheduler.queuedTime
        val requestUri = producerContext.imageRequest.sourceUri.toString()
        val length =
            if (isLastAndComplete || isPlaceholder) encodedImage.size
            else getIntermediateImageEndOffset(encodedImage)
        val quality =
            if (isLastAndComplete || isPlaceholder) ImmutableQualityInfo.FULL_QUALITY
            else qualityInfo
        producerListener.onProducerStart(producerContext, PRODUCER_NAME)
        var image: CloseableImage? = null
        try {
          image =
              try {
                internalDecode(encodedImage, length, quality)
              } catch (e: DecodeException) {
                val failedEncodedImage = e.encodedImage
                FLog.d(
                    TAG,
                    "%s, {uri: %s, firstEncodedBytes: %s, length: %d}",
                    e.message,
                    requestUri,
                    failedEncodedImage.getFirstBytesAsHexString(
                        DECODE_EXCEPTION_MESSAGE_NUM_HEADER_BYTES,
                    ),
                    failedEncodedImage.size,
                )
                throw e
              }
          if (encodedImage.sampleSize != EncodedImage.DEFAULT_SAMPLE_SIZE) {
            newStatus = status or Consumer.IS_RESIZING_DONE
          }
        } catch (e: Exception) {
          val failedScanCount =
              (producerContext.getExtra(HasExtraData.KEY_FAILED_SCAN_COUNT) as? Int ?: 0) + 1
          if (producerContext.getExtra<Int>(HasExtraData.KEY_FIRST_FAILED_SCAN_NUMBER) == null) {
            producerContext.putExtra(
                HasExtraData.KEY_FIRST_FAILED_SCAN_NUMBER,
                lastScheduledScanNumber,
            )
          }
          producerContext.putExtra(
              HasExtraData.KEY_FAILED_SCAN_NUMBER,
              lastScheduledScanNumber,
          )
          producerContext.putExtra(
              HasExtraData.KEY_FAILED_SCAN_IS_INTERMEDIATE,
              !isLast,
          )
          producerContext.putExtra(HasExtraData.KEY_FAILED_SCAN_COUNT, failedScanCount)
          producerContext.putExtra(HasExtraData.KEY_FAILED_SCAN_END_OFFSET, length)
          producerContext.putExtra(HasExtraData.KEY_FAILED_SCAN_ERROR_CLASS, e.javaClass.name)
          val extraMap = getExtraMap(
              image,
              queueTime,
              quality,
              isLast,
              imageFormatStr,
              encodedImageSize,
              requestedSizeStr,
              sampleSize,
          )
          producerListener.onProducerFinishWithFailure(producerContext, PRODUCER_NAME, e, extraMap)
          if (
              !isLast &&
                  encodedImage.imageFormat === DefaultImageFormats.JPEG &&
                  progressiveJpegConfig.recoverFromIntermediateDecodeFailure(
                      producerContext.imageRequest,
                  )
          ) {
            return
          }
          handleError(e)
          return
        }
        val extraMap = getExtraMap(
            image,
            queueTime,
            quality,
            isLast,
            imageFormatStr,
            encodedImageSize,
            requestedSizeStr,
            sampleSize,
        )
        producerListener.onProducerFinishWithSuccess(producerContext, PRODUCER_NAME, extraMap)
        setImageExtras(encodedImage, image, lastScheduledScanNumber)
        handleResult(image, newStatus, encodedImage)
      } finally {
        EncodedImage.closeSafely(encodedImage)
      }
    }

    /** This does not close the encodedImage * */
    private fun internalDecode(
        encodedImage: EncodedImage,
        length: Int,
        quality: QualityInfo,
    ): CloseableImage? {
      val recover = reclaimMemoryRunnable != null && recoverFromDecoderOOM.get()
      val image =
          try {
            imageDecoder.decode(encodedImage, length, quality, imageDecodeOptions)
          } catch (e: OutOfMemoryError) {
            if (!recover) {
              throw e
            }

            reclaimMemoryRunnable.run()
            System.gc()

            // Now we retry only once
            imageDecoder.decode(encodedImage, length, quality, imageDecodeOptions)
          }
      return image
    }

    private fun setImageExtras(
        encodedImage: EncodedImage,
        image: CloseableImage?,
        lastScheduledScanNumber: Int,
    ) {
      producerContext.putExtra(HasExtraData.KEY_ENCODED_WIDTH, encodedImage.width)
      producerContext.putExtra(HasExtraData.KEY_ENCODED_HEIGHT, encodedImage.height)
      producerContext.putExtra(HasExtraData.KEY_ENCODED_SIZE, encodedImage.size)
      producerContext.putExtra(HasExtraData.KEY_COLOR_SPACE, encodedImage.colorSpace)
      if (image is CloseableBitmap) {
        val config: Bitmap.Config? = image.underlyingBitmap?.config
        producerContext.putExtra(HasExtraData.KEY_BITMAP_CONFIG, config.toString())
      }
      producerContext.putExtra(HasExtraData.KEY_LAST_SCAN_NUMBER, lastScheduledScanNumber)
      image?.putExtras(producerContext.getExtras())
    }

    private fun getExtraMap(
        image: CloseableImage?,
        queueTime: Long,
        quality: QualityInfo,
        isFinal: Boolean,
        imageFormatName: String,
        encodedImageSize: String,
        requestImageSize: String,
        sampleSize: String,
    ): Map<String, String>? {
      if (!producerListener.requiresExtraMap(producerContext, PRODUCER_NAME)) {
        return null
      }
      val queueStr = queueTime.toString()
      val qualityStr = quality.isOfGoodEnoughQuality.toString()
      val finalStr = isFinal.toString()
      val nonFatalErrorStr = image?.extras?.get(NON_FATAL_DECODE_ERROR)?.toString()
      return if (image is CloseableStaticBitmap) {
        val bitmap = image.underlyingBitmap
        checkNotNull(bitmap)
        val sizeStr = bitmap.width.toString() + "x" + bitmap.height
        // We need this because the copyOf() utility method doesn't have a proper overload method
        // for all these parameters
        val tmpMap: MutableMap<String, String> = HashMap(8)
        tmpMap[EXTRA_BITMAP_SIZE] = sizeStr
        tmpMap[JobScheduler.QUEUE_TIME_KEY] = queueStr
        tmpMap[EXTRA_HAS_GOOD_QUALITY] = qualityStr
        tmpMap[EXTRA_IS_FINAL] = finalStr
        tmpMap[ENCODED_IMAGE_SIZE] = encodedImageSize
        tmpMap[EXTRA_IMAGE_FORMAT_NAME] = imageFormatName
        tmpMap[REQUESTED_IMAGE_SIZE] = requestImageSize
        tmpMap[SAMPLE_SIZE] = sampleSize
        tmpMap[EXTRA_BITMAP_BYTES] = bitmap.byteCount.toString() + ""
        if (nonFatalErrorStr != null) {
          tmpMap[NON_FATAL_DECODE_ERROR] = nonFatalErrorStr
        }
        ImmutableMap.copyOf(tmpMap)
      } else {
        val tmpMap: MutableMap<String, String> = HashMap(7)
        tmpMap[JobScheduler.QUEUE_TIME_KEY] = queueStr
        tmpMap[EXTRA_HAS_GOOD_QUALITY] = qualityStr
        tmpMap[EXTRA_IS_FINAL] = finalStr
        tmpMap[ENCODED_IMAGE_SIZE] = encodedImageSize
        tmpMap[EXTRA_IMAGE_FORMAT_NAME] = imageFormatName
        tmpMap[REQUESTED_IMAGE_SIZE] = requestImageSize
        tmpMap[SAMPLE_SIZE] = sampleSize
        if (nonFatalErrorStr != null) {
          tmpMap[NON_FATAL_DECODE_ERROR] = nonFatalErrorStr
        }
        ImmutableMap.copyOf(tmpMap)
      }
    }

    /**
     * Finishes if not already finished and `shouldFinish` is specified.
     *
     * If just finished, the intermediate image gets released.
     */
    private fun maybeFinish(shouldFinish: Boolean) {
      synchronized(this@ProgressiveDecoder) {
        if (!shouldFinish || isFinished) {
          return
        }
        consumer.onProgressUpdate(1.0f)
        isFinished = true
      }
      jobScheduler.clearJob()
    }

    /** Notifies consumer of new result and finishes if the result is final. */
    private fun handleResult(
        decodedImage: CloseableImage?,
        @Consumer.Status status: Int,
        encodedImage: EncodedImage,
    ) {
      val decodedImageRef: CloseableReference<CloseableImage>? =
          closeableReferenceFactory.create(decodedImage)
      try {
        val isLast = isLast(status)
        maybeFinish(isLast)
        if (isLast) {
          decodedOriginalImageAnalyzers?.forEach {
            if (decodedImageRef != null) {
              it.analyze(producerContext, decodedImageRef, encodedImage)
            }
          }
        }
        consumer.onNewResult(decodedImageRef, status)
      } finally {
        CloseableReference.closeSafely(decodedImageRef)
      }
    }

    /** Notifies consumer about the failure and finishes. */
    private fun handleError(t: Throwable) {
      maybeFinish(true)
      consumer.onFailure(t)
    }

    /** Notifies consumer about the cancellation and finishes. */
    private fun handleCancellation() {
      maybeFinish(true)
      consumer.onCancellation()
    }

    abstract fun getIntermediateImageEndOffset(encodedImage: EncodedImage): Int

    abstract val qualityInfo: QualityInfo

    init {
      val job = JobRunnable { encodedImage, status ->
        if (encodedImage != null) {
          val request = producerContext.imageRequest
          producerContext.putExtra(HasExtraData.KEY_IMAGE_FORMAT, encodedImage.imageFormat.name)
          encodedImage.source = request.sourceUri.toString()

          val requestDownsampleMode = request.downsampleOverride ?: downsampleMode
          val isResizingDone = statusHasFlag(status, Consumer.IS_RESIZING_DONE)
          val shouldAdjustSampleSize =
              (requestDownsampleMode == DownsampleMode.ALWAYS ||
                  (requestDownsampleMode == DownsampleMode.AUTO && !isResizingDone)) &&
                  (downsampleEnabledForNetwork || !UriUtil.isNetworkUri(request.sourceUri))
          if (shouldAdjustSampleSize) {
            encodedImage.sampleSize =
                DownsampleUtil.determineSampleSize(
                    request.rotationOptions,
                    request.resizeOptions,
                    encodedImage,
                    maxBitmapDimension,
                )
          }

          if (producerContext.imagePipelineConfig.experiments.downsampleIfLargeBitmap) {
            maybeIncreaseSampleSize(encodedImage)
          }
          doDecode(encodedImage, status, lastScheduledScanNumber)
        }
      }
      jobScheduler =
          JobScheduler(
              executor,
              job,
              imageDecodeOptions.minDecodeIntervalMs,
              producerContext.imagePipelineConfig.experiments.scheduleLastResultImmediately,
          )
      producerContext.addCallbacks(
          object : BaseProducerContextCallbacks() {
            override fun onIsIntermediateResultExpectedChanged() {
              if (producerContext.isIntermediateResultExpected) {
                if (
                    producerContext.imagePipelineConfig.experiments
                        .skipNonJpegIntermediateDecodeScheduling
                ) {
                  jobScheduler.scheduleJobIfImageFormat(DefaultImageFormats.JPEG)
                } else {
                  jobScheduler.scheduleJob()
                }
              }
            }

            override fun onCancellationRequested() {
              if (decodeCancellationEnabled) {
                handleCancellation()
              }
            }
          },
      )
    }
  }

  private inner class LocalImagesProgressiveDecoder(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      producerContext: ProducerContext,
      decodeCancellationEnabled: Boolean,
      maxBitmapDimension: Int,
  ) : ProgressiveDecoder(consumer, producerContext, decodeCancellationEnabled, maxBitmapDimension) {
    @Synchronized
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun updateDecodeJob(
        encodedImage: EncodedImage?,
        @Consumer.Status status: Int,
    ): Boolean =
        if (isNotLast(status)) {
          false
        } else {
          super.updateDecodeJob(encodedImage, status)
        }

    override fun getIntermediateImageEndOffset(encodedImage: EncodedImage): Int = encodedImage.size

    override val qualityInfo: QualityInfo
      get() = ImmutableQualityInfo.of(0, false, false)
  }

  private inner class NetworkImagesProgressiveDecoder(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      val producerContext: ProducerContext,
      val progressiveJpegParser: ProgressiveJpegParser,
      val progressiveJpegConfig: ProgressiveJpegConfig,
      decodeCancellationEnabled: Boolean,
      maxBitmapDimension: Int,
  ) : ProgressiveDecoder(consumer, producerContext, decodeCancellationEnabled, maxBitmapDimension) {
    @Synchronized
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun updateDecodeJob(
        encodedImage: EncodedImage?,
        @Consumer.Status status: Int,
    ): Boolean {
      if (encodedImage == null) {
        return false
      }
      val ret = super.updateDecodeJob(encodedImage, status)
      if (
          (isNotLast(status) || statusHasFlag(status, Consumer.IS_PARTIAL_RESULT)) &&
              !statusHasFlag(status, Consumer.IS_PLACEHOLDER) &&
              EncodedImage.isValid(encodedImage) &&
              encodedImage.imageFormat === DefaultImageFormats.JPEG
      ) {
        if (!this.progressiveJpegParser.parseMoreData(encodedImage)) {
          return false
        }
        val scanNum = this.progressiveJpegParser.bestScanNumber
        if (scanNum <= lastScheduledScanNumber) {
          // We have already decoded this scan, no need to do so again
          return false
        }
        if (
            scanNum <
                progressiveJpegConfig.getNextScanNumberToDecode(
                    producerContext.imageRequest,
                    lastScheduledScanNumber,
                ) && !this.progressiveJpegParser.isEndMarkerRead
        ) {
          // We have not reached the minimum scan set by the configuration and there
          // are still more scans to be read (the end marker is not reached)
          return false
        }
        lastScheduledScanNumber = scanNum
      }
      return ret
    }

    override fun getIntermediateImageEndOffset(encodedImage: EncodedImage): Int =
        this.progressiveJpegParser.bestScanEndOffset

    override val qualityInfo: QualityInfo
      get() =
          progressiveJpegConfig.getQualityInfo(
              producerContext.imageRequest,
              this.progressiveJpegParser.bestScanNumber,
          )

    init {
      lastScheduledScanNumber = 0
    }
  }

  companion object {
    const val PRODUCER_NAME = "DecodeProducer"

    private const val DECODE_EXCEPTION_MESSAGE_NUM_HEADER_BYTES = 10

    // In recent versions of Android you cannot draw bitmap that is bigger than 100MB bytes:
    // https://web.archive.org/web/20191017003524/https://chromium.googlesource.com/android_tools/+/refs/heads/master/sdk/sources/android-25/android/view/DisplayListCanvas.java
    private const val MAX_BITMAP_SIZE = 100 * 1_024 * 1_024 // 100 MB

    // keys for extra map
    const val EXTRA_BITMAP_SIZE = ProducerConstants.EXTRA_BITMAP_SIZE
    const val EXTRA_HAS_GOOD_QUALITY = ProducerConstants.EXTRA_HAS_GOOD_QUALITY
    const val EXTRA_IS_FINAL = ProducerConstants.EXTRA_IS_FINAL
    const val EXTRA_IMAGE_FORMAT_NAME = ProducerConstants.EXTRA_IMAGE_FORMAT_NAME
    const val EXTRA_BITMAP_BYTES = ProducerConstants.EXTRA_BYTES
    const val ENCODED_IMAGE_SIZE = ProducerConstants.ENCODED_IMAGE_SIZE
    const val REQUESTED_IMAGE_SIZE = ProducerConstants.REQUESTED_IMAGE_SIZE
    const val SAMPLE_SIZE = ProducerConstants.SAMPLE_SIZE
    const val NON_FATAL_DECODE_ERROR = ProducerConstants.NON_FATAL_DECODE_ERROR

    private fun isTooBig(
        encodedImage: EncodedImage,
        imageDecodeOptions: ImageDecodeOptions,
    ): Boolean {
      val w: Long = encodedImage.width.toLong()
      val h: Long = encodedImage.height.toLong()
      val size = BitmapUtil.getPixelSizeForBitmapConfig(imageDecodeOptions.bitmapConfig)
      return w * h * size > MAX_BITMAP_SIZE
    }
  }
}
