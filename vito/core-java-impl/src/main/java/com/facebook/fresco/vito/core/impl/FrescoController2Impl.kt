/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.internal.ImmutableMap
import com.facebook.common.internal.Supplier
import com.facebook.common.logging.FLog
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.fresco.middleware.MiddlewareUtils.obtainExtras
import com.facebook.fresco.ui.common.ControllerListener2.Extras
import com.facebook.fresco.ui.common.ImagePerfDataListener
import com.facebook.fresco.ui.common.ImagePerfDataNotifier
import com.facebook.fresco.ui.common.ImagePerfNotifier
import com.facebook.fresco.ui.common.OnFadeListener
import com.facebook.fresco.ui.common.VitoUtils.generateIdentifier
import com.facebook.fresco.urimod.asDimensions
import com.facebook.fresco.vito.core.FrescoController2
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.FrescoVitoConfig
import com.facebook.fresco.vito.core.ImagePerfLoggingListener
import com.facebook.fresco.vito.core.NopDrawable
import com.facebook.fresco.vito.core.VitoImagePerfListener
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.core.VitoImageRequestListener
import com.facebook.fresco.vito.core.impl.debug.DebugOverlayFactory2
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.source.BitmapImageSource
import com.facebook.fresco.vito.source.ColorImageSource
import com.facebook.fresco.vito.source.DrawableImageSource
import com.facebook.fresco.vito.source.DrawableResImageSource
import com.facebook.fresco.vito.source.EmptyImageSource
import com.facebook.fresco.vito.source.SmartImageSource
import com.facebook.imagepipeline.image.CloseableBitmap
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.ImageInfoImpl
import com.facebook.imagepipeline.image.ImmutableQualityInfo
import java.util.concurrent.Executor

open class FrescoController2Impl(
    private val config: FrescoVitoConfig,
    private val hierarcher: Hierarcher,
    private val lightweightBackgroundThreadExecutor: Executor,
    private val uiThreadExecutor: Executor,
    private val imagePipeline: VitoImagePipeline,
    private val globalImageListener: VitoImageRequestListener?,
    private val debugOverlayFactory: DebugOverlayFactory2,
    private val imagePerfListenerSupplier: Supplier<ImagePerfLoggingListener>?,
    private val vitoImagePerfListener: VitoImagePerfListener,
) : DrawableDataSubscriber, FrescoController2 {

  @Suppress("UNCHECKED_CAST")
  override fun <T> createDrawable(uiFramework: String?): T
      where T : Drawable, T : FrescoDrawableInterface =
      FrescoDrawable2Impl(
          config.useNewReleaseCallback(),
          imagePerfListenerSupplier?.get(),
          vitoImagePerfListener,
          uiFramework,
          config.experimentalResetVitoImageRequestListener(),
          config.experimentalResetLocalVitoImageRequestListener(),
          config.experimentalResetLocalImagePerfStateListener(),
          config.experimentalResetControllerListener2(),
          config.preservePendingReleaseOnImageUpdate(),
      )
          as T

  override fun fetch(
      drawable: FrescoDrawableInterface,
      imageRequest: VitoImageRequest,
      callerContext: Any?,
      contextChain: ContextChain?,
      listener: ImageListener?,
      perfDataListener: ImagePerfDataListener?,
      onFadeListener: OnFadeListener?,
      viewportDimensions: Rect?,
      vitoImageRequestListener: VitoImageRequestListener?,
  ): Boolean {
    if (drawable !is FrescoDrawable2Impl) {
      FLog.e(TAG, "Drawable not supported $drawable")
      return false
    }
    // Fast path for null-URIs. Two entry points: (a) the existing global
    // `fastPathForEmptyRequests` toggle, or (b) a per-source opt-in via
    // `EmptyImageSource.isExpectedEmpty` (callers who know "no image" is the legitimate outcome and
    // want it counted as onEmptyEvent rather than onFailure). The config read is the LAST condition
    // so (b) only exercises the new path when the source is genuinely expected-empty.
    val emptySource = imageRequest.imageSource as? EmptyImageSource
    if (
        emptySource != null &&
            (config.fastPathForEmptyRequests() ||
                (emptySource.isExpectedEmpty && config.handleExpectedEmptyImageSource()))
    ) {
      emptyRequestFastPath(drawable, imageRequest, callerContext)
      return true
    }

    // Save viewport dimension for future use
    drawable.viewportDimensions = viewportDimensions

    if (config.enableRetriggerListenersIfImageAlreadySet()) {
      drawable.retriggerListenersIfImageAlreadySet = true
    }

    val forceReload = drawable.forceReloadIfImageAlreadySet
    val retriggerListeners = drawable.retriggerListenersIfImageAlreadySet

    // Check if we already fetched the image
    if (!forceReload && isAlreadyLoadingImage(drawable, imageRequest)) {
      drawable.cancelReleaseNextFrame()
      drawable.cancelReleaseDelayed()
      if (retriggerListeners) {
        val submitExtras = obtainExtras(null, null, drawable, imageRequest)
        drawable.internalListener.onSubmit(
            drawable.imageId,
            imageRequest,
            callerContext,
            submitExtras,
        )
        drawable.imagePerfListener.onImageFetch(drawable)
        val actualImageDrawable = drawable.actualImageDrawable
        if (actualImageDrawable != null) {
          val finalExtras = drawable.tempFinalImageExtras
          if (finalExtras != null) {
            val imageInfoExtras: Map<String, Any> = finalExtras.imageExtras ?: HashMap()
            drawable.internalListener.onFinalImageSet(
                drawable.imageId,
                imageRequest,
                ImageOrigin.LOCAL,
                ImageInfoImpl(
                    actualImageDrawable.intrinsicWidth,
                    actualImageDrawable.intrinsicHeight,
                    0,
                    ImmutableQualityInfo.FULL_QUALITY,
                    imageInfoExtras,
                ),
                finalExtras,
                actualImageDrawable,
            )
            drawable.imagePerfListener.onImageSuccess(drawable, true)
          }
        }
      }
      return true // already set
    }
    if (drawable.isFetchSubmitted) {
      drawable.imagePerfListener.onDrawableReconfigured(drawable)
    }
    // We didn't -> Reset everything
    drawable.close()
    if (forceReload) {
      drawable.forceReloadIfImageAlreadySet = true
    }
    if (retriggerListeners) {
      drawable.retriggerListenersIfImageAlreadySet = retriggerListeners
    }
    // Basic setup
    drawable.drawableDataSubscriber = this
    drawable.imageRequest = imageRequest
    drawable.callerContext = callerContext
    drawable.imageListener = listener
    drawable.setVitoImageRequestListener(globalImageListener)
    drawable.setLocalVitoImageRequestListener(vitoImageRequestListener)

    // Setup local perf data listener
    if (perfDataListener != null) {
      val localPerfStateListener: ImagePerfNotifier = ImagePerfDataNotifier(perfDataListener)
      drawable.internalListener.setLocalImagePerfStateListener(localPerfStateListener)
    } else {
      drawable.internalListener.setLocalImagePerfStateListener(null)
    }
    drawable.setOnFadeListener(onFadeListener)

    // Set layers that are always visible
    drawable.setOverlayDrawable(
        hierarcher.buildOverlayDrawable(imageRequest.resources, imageRequest.imageOptions),
    )

    // We're fetching a new image, so we're updating the ID
    val imageId = generateIdentifier()
    drawable.imageId = imageId
    val extras = obtainExtras(null, null, drawable, imageRequest)

    // Notify listeners that we're about to fetch an image
    drawable.internalListener.onSubmit(imageId, imageRequest, callerContext, extras)
    drawable.imagePerfListener.onImageFetch(drawable)

    // Direct bitmap available
    if (imageRequest.imageSource is BitmapImageSource) {
      val bitmap = (imageRequest.imageSource as BitmapImageSource).bitmap
      val closeableBitmap: CloseableBitmap =
          CloseableStaticBitmap.of(bitmap, { _: Bitmap? -> }, ImmutableQualityInfo.FULL_QUALITY, 0)
      val bitmapRef = CloseableReference.of<CloseableImage>(closeableBitmap)
      return try {
        drawable.imageOrigin = ImageOrigin.MEMORY_BITMAP
        // Immediately display the actual image.
        setActualImage(drawable, imageRequest, bitmapRef, true, null)
        drawable.setFetchSubmitted(true)
        true
      } finally {
        CloseableReference.closeSafely(bitmapRef)
      }
    } else if (imageRequest.imageSource is ColorImageSource) {
      setActualDrawable(
          drawable,
          imageRequest,
          extras,
          ColorDrawable((imageRequest.imageSource as ColorImageSource).color),
      )
      return true
    } else if (imageRequest.imageSource is DrawableImageSource) {
      setActualDrawable(
          drawable,
          imageRequest,
          extras,
          (imageRequest.imageSource as DrawableImageSource).drawable,
      )
      return true
    } else if (imageRequest.imageSource is DrawableResImageSource) {
      setActualDrawable(
          drawable,
          imageRequest,
          extras,
          (imageRequest.imageSource as DrawableResImageSource).createDrawable(
              imageRequest.resources,
          ),
      )
      return true
    }

    // Check if the image is in cache
    val cachedImage = imagePipeline.getCachedImage(imageRequest)
    var needsPlaceholderDrawable = true
    try {
      if (CloseableReference.isValid(cachedImage)) {
        drawable.imageOrigin = ImageOrigin.MEMORY_BITMAP_SHORTCUT
        val isIntermediateImage =
            config.useIntermediateImagesAsPlaceholder() &&
                cachedImage?.get()?.qualityInfo?.isOfFullQuality != true
        // Immediately display the actual image.
        setActualImage(drawable, imageRequest, cachedImage, true, null, isIntermediateImage)
        drawable.setFetchSubmitted(true)
        if (!isIntermediateImage) {
          return true
        } else {
          // We are displaying an intermediate image and can skip setting up the placeholder
          needsPlaceholderDrawable = false
        }
      }
    } finally {
      CloseableReference.closeSafely(cachedImage)
    }

    if (needsPlaceholderDrawable) {
      // The image is not in cache -> Set up layers visible until the image is available
      drawable.setProgressDrawable(
          hierarcher.buildProgressDrawable(imageRequest.resources, imageRequest.imageOptions),
      )
      // Immediately show the progress image and set progress to 0
      drawable.setProgress(0f)
      drawable.showProgressImmediately()
      setUpPlaceholder(drawable, imageRequest, imageId)
    }

    // Fetch the image
    val fetchRunnable = Runnable {
      if (imageId != drawable.imageId) {
        return@Runnable // We're trying to load a different image -> ignore
      }
      val dataSource =
          imagePipeline.fetchDecodedImage(
              imageRequest,
              callerContext,
              drawable.imageOriginListener,
              imageId,
              viewportDimensions?.asDimensions(),
          )
      drawable.setDataSource(imageId, dataSource)
      dataSource.subscribe(drawable, uiThreadExecutor)
    }
    if (config.submitFetchOnBgThread()) {
      lightweightBackgroundThreadExecutor.execute(fetchRunnable)
    } else {
      fetchRunnable.run()
    }
    drawable.setFetchSubmitted(true)
    debugOverlayFactory.update(drawable, null)
    return false
  }

  private fun emptyRequestFastPath(
      frescoDrawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      callerContext: Any?,
  ) {
    frescoDrawable.close()
    frescoDrawable.setVitoImageRequestListener(globalImageListener)
    frescoDrawable.internalListener.onEmptyEvent(callerContext)
    frescoDrawable.setOverlayDrawable(
        hierarcher.buildOverlayDrawable(imageRequest.resources, imageRequest.imageOptions),
    )
    setUpPlaceholder(frescoDrawable, imageRequest, EMPTY_IMAGE_ID)
  }

  private fun setUpPlaceholder(
      frescoDrawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      imageId: Long,
  ) {
    val placeholder =
        hierarcher.buildPlaceholderDrawable(imageRequest.resources, imageRequest.imageOptions)
    frescoDrawable.setPlaceholderDrawable(placeholder)
    frescoDrawable.setImageDrawable(null)
    frescoDrawable.internalListener.onPlaceholderSet(imageId, imageRequest, placeholder)
  }

  override fun releaseDelayed(drawable: FrescoDrawableInterface) {
    if (drawable !is FrescoDrawable2Impl) {
      FLog.e(TAG, "Drawable not supported $drawable")
      return
    }
    drawable.imagePerfListener.onScheduleReleaseDelayed(drawable)
    drawable.scheduleReleaseDelayed(config.releaseDelayMs())
  }

  override fun releaseNextFrame(drawable: FrescoDrawableInterface) {
    if (drawable !is FrescoDrawable2Impl) {
      FLog.e(TAG, "Drawable not supported $drawable")
      return
    }
    drawable.imagePerfListener.onScheduleReleaseNextFrame(drawable)
    drawable.scheduleReleaseNextFrame()
  }

  override fun releaseImmediately(drawable: FrescoDrawableInterface) {
    if (drawable !is FrescoDrawable2Impl) {
      FLog.e(TAG, "Drawable not supported $drawable")
      return
    }
    drawable.imagePerfListener.onReleaseImmediately(drawable)
    drawable.releaseImmediately()
  }

  private fun setActualImage(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      image: CloseableReference<CloseableImage>?,
      displayImmediately: Boolean,
      dataSource: DataSource<CloseableReference<CloseableImage>>?,
      isIntermediateImage: Boolean = false,
  ) {
    val actualImageWrapperDrawable = drawable.actualImageWrapper
    hierarcher.setupActualImageWrapper(
        actualImageWrapperDrawable,
        imageRequest.imageOptions,
        drawable.callerContext,
    )
    val actualDrawable = image?.let { closeableImageRef ->
      if (config.enablePrepareToDrawOnFetch()) {
        val img = closeableImageRef.get()
        if (img is CloseableBitmap) {
          val bitmap = img.underlyingBitmap
          if (bitmap != null) {
            bitmap.prepareToDraw()
          } else {
            FLog.w(TAG, "underlyingBitmap is null during prepareToDraw, image may be closed")
          }
        }
      }
      hierarcher.buildActualImageDrawable(
          imageRequest.resources,
          imageRequest.imageOptions,
          closeableImageRef,
      )
    }

    actualImageWrapperDrawable.setCurrent(actualDrawable ?: NopDrawable)
    drawable.setImage(actualImageWrapperDrawable, image)
    if (displayImmediately || imageRequest.imageOptions.fadeDurationMs <= 0) {
      drawable.showImageImmediately()
    } else {
      drawable.fadeInImage(imageRequest.imageOptions.fadeDurationMs)
    }
    if (
        imageRequest.imageOptions.keepProgressIndicatorDuringIntermediateImages &&
            (isIntermediateImage || dataSource?.isFinished == false)
    ) {
      drawable.showProgressImmediately()
    }
    if (imageRequest.imageOptions.shouldAutoPlay() && actualDrawable is Animatable) {
      actualDrawable.start()
    }
    val extras = obtainExtras(dataSource, image, drawable, imageRequest)

    val imageInfo = image?.get()?.imageInfo
    image?.let { drawable.internalListener.onImageSet(it, drawable.viewportDimensions) }
    if (!isIntermediateImage && notifyFinalResult(dataSource)) {
      drawable.internalListener.onFinalImageSet(
          drawable.imageId,
          imageRequest,
          drawable.imageOrigin,
          imageInfo,
          extras,
          actualDrawable,
      )
      if (drawable.retriggerListenersIfImageAlreadySet) {
        drawable.tempFinalImageExtras = extras
      }
    } else {
      drawable.internalListener.onIntermediateImageSet(drawable.imageId, imageRequest, imageInfo)
    }
    drawable.imagePerfListener.onImageSuccess(drawable, displayImmediately)
    var progress = 1f
    if (dataSource != null && !dataSource.isFinished) {
      progress = dataSource.progress
    }
    drawable.setProgress(progress)
    debugOverlayFactory.update(drawable, extras)
  }

  override fun onNewResult(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      dataSource: DataSource<CloseableReference<CloseableImage>>,
  ) {
    if (!dataSource.hasResult()) {
      return
    }
    val image = dataSource.result
    try {
      if (!CloseableReference.isValid(image)) {
        onFailure(drawable, imageRequest, dataSource)
      } else {
        setActualImage(drawable, imageRequest, image, false, dataSource)
      }
    } finally {
      CloseableReference.closeSafely(image)
    }
  }

  override fun onFailure(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      dataSource: DataSource<CloseableReference<CloseableImage>>,
  ) {
    val errorDrawable =
        hierarcher.buildErrorDrawable(imageRequest.resources, imageRequest.imageOptions)
    drawable.setProgress(1f)
    drawable.setImageDrawable(errorDrawable)
    if (!drawable.isDefaultLayerIsOn) {
      if (imageRequest.imageOptions.fadeDurationMs <= 0) {
        drawable.showImageImmediately()
      } else {
        drawable.fadeInImage(imageRequest.imageOptions.fadeDurationMs)
      }
    } else {
      drawable.setPlaceholderDrawable(null)
      drawable.setProgressDrawable(null)
    }
    val extras = obtainExtras(dataSource, dataSource.result, drawable, imageRequest)
    if (notifyFinalResult(dataSource)) {
      drawable.internalListener.onFailure(
          drawable.imageId,
          imageRequest,
          errorDrawable,
          dataSource.failureCause,
          extras,
      )
    } else {
      drawable.internalListener.onIntermediateImageFailed(
          drawable.imageId,
          imageRequest,
          dataSource.failureCause,
      )
    }
    drawable.imagePerfListener.onImageError(drawable)
    debugOverlayFactory.update(drawable, extras)
  }

  override fun onProgressUpdate(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      dataSource: DataSource<CloseableReference<CloseableImage>>,
  ) {
    if (!dataSource.isFinished) {
      drawable.setProgress(dataSource.progress)
    }
  }

  override fun onRelease(drawable: FrescoDrawable2Impl) {
    val imageRequest = drawable.imageRequest
    if (imageRequest != null) {
      // Notify listeners
      drawable.internalListener.onRelease(
          drawable.imageId,
          imageRequest,
          obtainExtras(null, null, drawable, imageRequest),
      )
    }
    drawable.imagePerfListener.onImageRelease(drawable)
  }

  private fun isAlreadyLoadingImage(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
  ): Boolean {
    if (drawable.drawableDataSubscriber !== this || !drawable.isFetchSubmitted) {
      return false
    }
    return if (config.useSmartPropertyDiffing()) {
      imageRequest.equalsIfHasImage(drawable.imageRequest, drawable.hasImage())
    } else {
      imageRequest == drawable.imageRequest
    }
  }

  private fun setActualDrawable(
      drawable: FrescoDrawable2Impl,
      imageRequest: VitoImageRequest,
      extras: Extras,
      actualImageDrawable: Drawable,
  ) {
    val actualImageWrapperDrawable = drawable.actualImageWrapper
    hierarcher.setupActualImageWrapper(
        actualImageWrapperDrawable,
        imageRequest.imageOptions,
        drawable.callerContext,
    )
    val actualDrawable =
        hierarcher.applyRoundingOptions(
            imageRequest.resources,
            actualImageDrawable,
            imageRequest.imageOptions,
        )
    actualImageWrapperDrawable.setCurrent(actualDrawable)
    drawable.setImage(actualImageWrapperDrawable, null)
    drawable.showImageImmediately()
    drawable.imageOrigin = ImageOrigin.LOCAL
    drawable.setFetchSubmitted(true)
    if (imageRequest.imageOptions.shouldAutoPlay() && actualImageDrawable is Animatable) {
      actualImageDrawable.start()
    }
    val imageInfoExtras: Map<String, Any> = extras.imageExtras ?: HashMap()
    drawable.internalListener.onFinalImageSet(
        drawable.imageId,
        imageRequest,
        ImageOrigin.LOCAL,
        ImageInfoImpl(
            actualImageDrawable.intrinsicWidth,
            actualImageDrawable.intrinsicHeight,
            0,
            ImmutableQualityInfo.FULL_QUALITY,
            imageInfoExtras,
        ),
        extras,
        actualDrawable,
    )
    drawable.imagePerfListener.onImageSuccess(drawable, true)
    debugOverlayFactory.update(drawable, extras)
  }

  companion object {
    private val COMPONENT_EXTRAS = ImmutableMap.of<String, Any>("component_tag", "vito2")
    private val SHORTCUT_EXTRAS =
        ImmutableMap.of<String, Any>(
            "origin",
            "memory_bitmap",
            HasExtraData.KEY_ORIGIN_SUBCATEGORY,
            "shortcut",
        )
    private const val TAG = "FrescoController2Impl"
    private const val EMPTY_IMAGE_ID = Long.MAX_VALUE

    private fun obtainExtras(
        dataSource: DataSource<CloseableReference<CloseableImage>>?,
        image: CloseableReference<CloseableImage>?,
        drawable: FrescoDrawable2,
        imageRequest: VitoImageRequest,
    ): Extras {
      var imageExtras: Map<String, Any>? = null
      if (image != null) {
        imageExtras = image.get().extras
      }
      var sourceUri: Uri? = null
      val vitoImageRequest = drawable.imageRequest
      var imageSourceExtras: Map<String, Any>? = null
      var logWithHighSamplingRate = false
      if (vitoImageRequest != null) {
        logWithHighSamplingRate = vitoImageRequest.logWithHighSamplingRate
        vitoImageRequest.finalImageRequest?.let { finalImageRequest ->
          sourceUri = finalImageRequest.sourceUri
        }
        @Suppress("UNCHECKED_CAST")
        imageSourceExtras =
            vitoImageRequest.extras[HasExtraData.KEY_IMAGE_SOURCE_EXTRAS] as? Map<String, Any>
      }
      val extras = obtainExtras(
          COMPONENT_EXTRAS,
          SHORTCUT_EXTRAS,
          dataSource?.extras,
          imageSourceExtras,
          drawable.viewportDimensions,
          drawable.actualImageScaleType,
          drawable.actualImageFocusPoint,
          imageExtras,
          drawable.callerContext,
          logWithHighSamplingRate,
          sourceUri,
      )
      extras.smartUrlFetchStrategy = imageRequest.extras[HasExtraData.KEY_SF_FETCH_STRATEGY]
      extras.smartUrlModificationResult = imageRequest.extras[HasExtraData.KEY_SF_MOD_RESULT]
      extras.smartOriginalUri = imageRequest.extras[HasExtraData.KEY_SF_ORIGINAL_URL] as? Uri
      extras.uiFramework = drawable.uiFramework
      extras.imageSource = imageRequest.imageSource
      extras.sizingHint = (imageRequest.imageSource as? SmartImageSource)?.sizingHint
      return extras
    }

    private fun notifyFinalResult(
        dataSource: DataSource<CloseableReference<CloseableImage>>?,
    ): Boolean = dataSource == null || dataSource.isFinished || dataSource.hasMultipleResults()
  }
}
