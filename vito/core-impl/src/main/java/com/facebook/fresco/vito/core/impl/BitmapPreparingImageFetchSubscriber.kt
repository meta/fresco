/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.datasource.DataSubscriber
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.fresco.ui.common.ControllerListener2
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.renderer.ImageDataModel
import com.facebook.imagepipeline.image.CloseableBitmap
import com.facebook.imagepipeline.image.CloseableImage
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/** DataSource callbacks must be delivered on [uiThreadExecutor]. */
class BitmapPreparingImageFetchSubscriber(
    private val imageId: Long,
    private val drawable: KFrescoVitoDrawable,
    private val imageToDataModelMapper:
        (Resources, CloseableImage, ImageOptions) -> ImageDataModel?,
    private val debugOverlayHandler: VitoDebugOverlay? = null,
    private val uiThreadExecutor: Executor = Executor { it.run() },
    private val preparationExecutor: Executor? = null,
) : DataSubscriber<CloseableReference<CloseableImage>> {
  // DataSource callbacks and result application are serialized on UI; workers do not read this.
  private var resultSequence = 0L

  override fun onNewResult(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    assertOnUiThread()
    if (imageId != drawable.imageId) {
      return
    }
    val request = drawable.imageRequest ?: return

    val result: CloseableReference<CloseableImage>? = dataSource.result

    if (result == null || !result.isValid) {
      result?.close()
      onFailure(dataSource)
      return
    }

    val sequence = ++resultSequence
    val image = result.get()
    val isFinished = dataSource.isFinished
    val isFinalResult = notifyFinalResult(dataSource)
    val origin = originFromExtras(dataSource)
    val extras = drawable.obtainExtras(dataSource, result)

    val pendingResult = AtomicReference<CloseableReference<CloseableImage>?>(result)
    fun releasePendingResult() {
      pendingResult.getAndSet(null)?.close()
    }

    fun prepareResult(): Result<ImageDataModel> = runCatching {
      checkNotNull(imageToDataModelMapper(request.resources, image, request.imageOptions)) {
        "Could not create an image data model"
      }
    }

    fun applyPreparedResult(prepared: Result<ImageDataModel>) {
      assertOnUiThread()
      val ownedResult = pendingResult.getAndSet(null) ?: return
      ownedResult.use {
        if (!isCurrent(sequence)) {
          return
        }
        val model = prepared.getOrElse { throwable ->
          handleFailure(request, throwable, isFinished, isFinalResult, extras)
          return
        }
        val imageInfo = ownedResult.get().imageInfo
        // Keep local ownership through callbacks that may reset and close the drawable's copy.
        drawable.closeable = ownedResult.clone()
        drawable.actualImageLayer.setActualImage(request.imageOptions, model)
        if (!isCurrent(sequence)) {
          return
        }
        val fadeListener = drawable.onFadeListener
        val fadeDurationMs = request.imageOptions.fadeDurationMs
        if (fadeListener != null && fadeDurationMs > 0) {
          fadeListener.onFadeStarted()
          if (!isCurrent(sequence)) {
            return
          }
          drawable.actualImageLayer.fadeIn(fadeDurationMs) {
            if (isCurrent(sequence) && drawable.onFadeListener === fadeListener) {
              fadeListener.onFadeFinished()
            }
          }
        } else {
          drawable.actualImageLayer.fadeIn(fadeDurationMs)
          if (!isCurrent(sequence)) {
            return
          }
          fadeListener?.onShownImmediately()
        }
        if (!isCurrent(sequence)) {
          return
        }
        drawable.placeholderLayer.fadeOut(fadeDurationMs, true)
        if (!isCurrent(sequence)) {
          return
        }
        if (isFinished) {
          drawable.hideProgressLayer()
        }
        if (!isCurrent(sequence)) {
          return
        }
        if (isFinalResult) {
          drawable.listenerManager.onFinalImageSet(
              imageId,
              request,
              origin,
              imageInfo,
              extras,
              drawable.actualImageDrawable,
          )
        } else {
          drawable.listenerManager.onIntermediateImageSet(imageId, request, imageInfo)
        }
        if (isCurrent(sequence)) {
          invalidate(drawable)
        }
      }
    }

    // Drawable factories can create animations and callbacks that require the main thread.
    val executor = preparationExecutor
    if (
        executor != null &&
            image is CloseableBitmap &&
            request.imageOptions.customDrawableFactory == null
    ) {
      try {
        executor.execute {
          val prepared = prepareResult()
          try {
            uiThreadExecutor.execute { applyPreparedResult(prepared) }
          } catch (exception: RejectedExecutionException) {
            // Application can claim the reference only on main. A claimed reference means an
            // inline UI callback threw, so preserve that exception and the drawable's ownership.
            val rejectedResult = pendingResult.getAndSet(null) ?: throw exception
            rejectedResult.close()
            // A rejected executor cannot deliver the failure; use the main looper directly.
            Handler(Looper.getMainLooper()).post {
              if (isCurrent(sequence)) {
                handleFailure(request, exception, isFinished, isFinalResult, extras)
              }
            }
          } catch (exception: Exception) {
            releasePendingResult()
            throw exception
          }
        }
      } catch (exception: RejectedExecutionException) {
        val rejectedResult = pendingResult.getAndSet(null) ?: throw exception
        rejectedResult.use {
          if (isCurrent(sequence)) {
            handleFailure(request, exception, isFinished, isFinalResult, extras)
          }
        }
      } catch (exception: Exception) {
        releasePendingResult()
        throw exception
      }
    } else {
      applyPreparedResult(prepareResult())
    }
  }

  override fun onFailure(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    assertOnUiThread()
    if (imageId != drawable.imageId) {
      return
    }
    val request = drawable.imageRequest ?: return
    dataSource.result.use { result ->
      handleFailure(
          request,
          dataSource.failureCause,
          dataSource.isFinished,
          notifyFinalResult(dataSource),
          drawable.obtainExtras(dataSource, result),
      )
    }
  }

  private fun handleFailure(
      request: VitoImageRequest,
      throwable: Throwable?,
      isFinished: Boolean,
      isFinalResult: Boolean,
      extras: ControllerListener2.Extras,
  ) {
    assertOnUiThread()
    val sequence = ++resultSequence
    drawable.actualImageLayer.setError(request.resources, request.imageOptions)
    if (!isCurrent(sequence)) {
      return
    }
    drawable.onFadeListener?.onShownImmediately()
    if (!isCurrent(sequence)) {
      return
    }
    if (isFinished) {
      drawable.hideProgressLayer()
    }
    if (!isCurrent(sequence)) {
      return
    }
    if (isFinalResult) {
      drawable.listenerManager.onFailure(
          imageId,
          request,
          drawable.actualImageLayer.getDataModel().maybeGetDrawable(),
          throwable,
          extras,
      )
    } else {
      drawable.listenerManager.onIntermediateImageFailed(imageId, request, throwable)
    }
    if (isCurrent(sequence)) {
      drawable.imagePerfListener.onImageError(drawable)
      invalidate(drawable)
    }
  }

  override fun onCancellation(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    assertOnUiThread()
    resultSequence++
  }

  override fun onProgressUpdate(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    assertOnUiThread()
    if (imageId != drawable.imageId) {
      return
    }
    drawable.updateProgress(dataSource)
    debugOverlayHandler?.update(drawable)
  }

  private fun isCurrent(sequence: Long): Boolean =
      sequence == resultSequence && imageId == drawable.imageId

  private fun assertOnUiThread() {
    check(Looper.myLooper() === Looper.getMainLooper()) {
      "Image result callbacks and application must run on the UI thread"
    }
  }

  private fun invalidate(drawable: KFrescoVitoDrawable) {
    drawable.invalidateSelf()
    debugOverlayHandler?.update(drawable)
  }

  private fun notifyFinalResult(
      dataSource: DataSource<CloseableReference<CloseableImage>>,
  ): Boolean = dataSource.isFinished || dataSource.hasMultipleResults()

  private fun originFromExtras(dataSource: DataSource<CloseableReference<CloseableImage>>): Int {
    return when (dataSource.extras?.get("origin") as? String) {
      "network" -> ImageOrigin.NETWORK
      "disk" -> ImageOrigin.DISK
      "memory_encoded" -> ImageOrigin.MEMORY_ENCODED
      "memory_bitmap" -> ImageOrigin.MEMORY_BITMAP
      "memory_bitmap_shortcut" -> ImageOrigin.MEMORY_BITMAP_SHORTCUT
      "local" -> ImageOrigin.LOCAL
      else -> ImageOrigin.UNKNOWN
    }
  }
}
