/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.internal.Supplier
import com.facebook.common.logging.FLog
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.fresco.ui.common.ImagePerfDataListener
import com.facebook.fresco.ui.common.ImagePerfDataNotifier
import com.facebook.fresco.ui.common.OnFadeListener
import com.facebook.fresco.ui.common.VitoUtils
import com.facebook.fresco.vito.core.FrescoController2
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.FrescoVitoConfig
import com.facebook.fresco.vito.core.ImagePerfLoggingListener
import com.facebook.fresco.vito.core.VitoImagePerfListener
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.core.VitoImageRequestListener
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.fresco.vito.renderer.AnimatedDrawableImageDataModel
import com.facebook.fresco.vito.renderer.BitmapImageDataModel
import com.facebook.fresco.vito.renderer.DrawableImageDataModel
import com.facebook.fresco.vito.renderer.ImageDataModel
import com.facebook.fresco.vito.source.BitmapImageSource
import com.facebook.fresco.vito.source.ColorImageSource
import com.facebook.fresco.vito.source.DrawableImageSource
import com.facebook.fresco.vito.source.DrawableResImageSource
import com.facebook.fresco.vito.source.EmptyImageSource
import com.facebook.imagepipeline.image.CloseableBitmap
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.ImageInfoImpl
import com.facebook.imagepipeline.image.ImmutableQualityInfo
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection
import java.util.concurrent.Executor

class KFrescoController(
    private val config: FrescoVitoConfig,
    private val vitoImagePipeline: VitoImagePipeline,
    private val uiThreadExecutor: Executor,
    private val lightweightBackgroundThreadExecutor: Executor,
    private val globalImageRequestListener: VitoImageRequestListener? = null,
    private val imagePerfLoggingListenerSupplier: Supplier<ImagePerfLoggingListener>? = null,
    private val imagePerfListener: VitoImagePerfListener = BaseVitoImagePerfListener(),
    private val drawableFactory: ImageOptionsDrawableFactory? = null,
) : FrescoController2 {

  private val imageToDataModelMapper: (Resources, CloseableImage, ImageOptions) -> ImageDataModel? =
      { resources, closeableImage, imageOptions ->
        traceSection("KFrescoController#imageToDataModelMapper") {
          // Try custom drawable factory first
          imageOptions.customDrawableFactory
              ?.createDrawable(resources, closeableImage, imageOptions)
              ?.let { createDrawableModel(it, imageOptions) }
              ?: when (closeableImage) {
                is CloseableBitmap -> {
                  val bitmap = closeableImage.underlyingBitmap
                  if (bitmap == null) {
                    FLog.w(TAG, "underlyingBitmap is null, image may be closed")
                  }
                  if (bitmap != null) {
                    if (config.enablePrepareToDrawOnFetch()) {
                      bitmap.prepareToDraw()
                    }
                    val isRounded = closeableImage.getExtras()["is_rounded"] == true
                    BitmapImageDataModel(bitmap, isRounded)
                  } else {
                    null
                  }
                  // TODO(T105148151): handle rotation for closeable static bitmap, handle other
                  // types
                }
                else -> {
                  drawableFactory?.createDrawable(resources, closeableImage, imageOptions)?.let {
                    createDrawableModel(it, imageOptions)
                  }
                }
              }
              ?: run {
                FLog.e(TAG, "Could not create Drawable for CloseableImage: $imageOptions")
                null
              }
        }
      }

  var debugOverlayHandler: DebugOverlayHandler? = null

  override fun <T> createDrawable(): T where T : Drawable, T : FrescoDrawableInterface =
      createDrawable(null)

  @Suppress("UNCHECKED_CAST")
  override fun <T> createDrawable(uiFramework: String?): T
      where T : Drawable, T : FrescoDrawableInterface {
    traceSection("KFrescoController#createDrawable") {
      val drawable = KFrescoVitoDrawable(
          imagePerfListener,
          config.experimentalResetVitoImageRequestListener(),
          config.experimentalResetLocalVitoImageRequestListener(),
          config.experimentalResetLocalImagePerfStateListener(),
          config.experimentalResetControllerListener2(),
          config.experimentalOptimizeAlphaHandling(),
          config.fixClearActualImageLayerOnClose(),
          config.fixResetPlaceholderOnZeroFade(),
          config.fixKotlinActualImageBounds(),
          skipRedundantImageClip = uiFramework == "compose" && config.enableComposeImagePathFixes(),
      )
      drawable.uiFramework = uiFramework
      imagePerfLoggingListenerSupplier
          ?.get()
          ?.let(drawable.listenerManager::setImagePerfLoggingListener)
      return drawable as T
    }
  }

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
    traceSection("KFrescoController#fetch") {
      if (drawable !is KFrescoVitoDrawable) {
        FLog.e(TAG, "Drawable not supported $drawable")
        return false
      }

      if (config.enableRetriggerListenersIfImageAlreadySet()) {
        drawable.retriggerListenersIfImageAlreadySet = true
      }

      val forceReload = drawable.forceReloadIfImageAlreadySet
      val retriggerListeners = drawable.retriggerListenersIfImageAlreadySet

      // Check if we already fetched that image
      if (!forceReload && isAlreadyLoadingImage(imageRequest, drawable)) {
        ImageReleaseScheduler.cancelAllReleasing(drawable)
        if (retriggerListeners && drawable.hasImage()) {
          val closeableImage =
              (drawable.closeable as? CloseableReference<*>)?.get() as? CloseableImage
          drawable.listenerManager.onFinalImageSet(
              drawable.imageId,
              imageRequest,
              ImageOrigin.MEMORY_BITMAP_SHORTCUT,
              closeableImage?.imageInfo,
              drawable.obtainExtras(imageExtras = closeableImage?.extras),
              drawable.actualImageDrawable,
          )
        }
        return true
      }

      if (drawable.isFetchSubmitted) {
        drawable.imagePerfListener.onDrawableReconfigured(drawable)
      }

      // We didn't -> reset everything and set up new fetch
      // TODO(T105148151): move to new package so that no legacy impl dep
      val imageId: Long = VitoUtils.generateIdentifier()
      val d = drawable
      d.reset()
      d.forceReloadIfImageAlreadySet = forceReload
      d.retriggerListenersIfImageAlreadySet = retriggerListeners

      d.imageRequest = imageRequest
      d.callerContext = callerContext
      d.imageListener = listener
      d.onFadeListener = if (config.fixKotlinOnFadeListener()) onFadeListener else null
      d.listenerManager.setVitoImageRequestListener(globalImageRequestListener)
      d.listenerManager.setLocalVitoImageRequestListener(vitoImageRequestListener)

      // Setup local perf data listener
      val localPerfStateListener = perfDataListener?.let(::ImagePerfDataNotifier)
      d.listenerManager.setLocalImagePerfStateListener(localPerfStateListener)

      d._imageId = imageId
      d.viewportDimensions = viewportDimensions

      val options: ImageOptions = imageRequest.imageOptions

      drawable.listenerManager.onSubmit(
          imageId,
          imageRequest,
          callerContext,
          drawable.obtainExtras(),
      )
      drawable.imagePerfListener.onImageFetch(drawable)
      drawable.overlayImageLayer.setOverlay(imageRequest.resources, options)
      drawable.setupBackgroundLayer(imageRequest.resources, options)

      // Handle direct image sources (no fetching required)
      when (val source = imageRequest.imageSource) {
        is BitmapImageSource -> {
          val closeableBitmap =
              CloseableStaticBitmap.of(source.bitmap, {}, ImmutableQualityInfo.FULL_QUALITY, 0)
          val bitmapRef = CloseableReference.of<CloseableImage>(closeableBitmap)
          return drawable.setActualImage(imageRequest, bitmapRef)
        }
        is ColorImageSource -> {
          setDrawableAsActualImage(drawable, ColorDrawable(source.color), imageRequest, imageId)
          return true
        }
        is DrawableImageSource -> {
          setDrawableAsActualImage(drawable, source.drawable, imageRequest, imageId)
          return true
        }
        is DrawableResImageSource -> {
          val resourceDrawable = source.createDrawable(imageRequest.resources)
          setDrawableAsActualImage(drawable, resourceDrawable, imageRequest, imageId)
          return true
        }
        is EmptyImageSource -> {
          // Expected empty: caller opted in via `isExpectedEmpty` and the config flag is on. Route
          // through `onEmptyEvent` + placeholder — skipping the default fetch path where
          // `ImageSourceToImagePipelineAdapter.NO_REQUEST_SUPPLIER` produces an immediate failure
          // with `"No image request was specified!"`. The config read is the LAST condition so we
          // only exercise the new path when the source is genuinely expected-empty. Non-expected
          // empty sources fall through to today's failure path.
          if (source.isExpectedEmpty && config.handleExpectedEmptyImageSource()) {
            drawable.listenerManager.onEmptyEvent(callerContext)
            drawable.placeholderLayer.setPlaceholder(imageRequest.resources, options)
            drawable.listenerManager.onPlaceholderSet(
                imageId,
                imageRequest,
                drawable.placeholderLayer.getDataModel().maybeGetDrawable(),
            )
            return true
          }
        }
      }

      // Check if the image is in cache
      val cachedImage = vitoImagePipeline.getCachedImage(imageRequest)

      val isIntermediateImage =
          config.useIntermediateImagesAsPlaceholder() &&
              cachedImage?.get()?.qualityInfo?.isOfFullQuality != true
      val hasImage = drawable.setActualImage(imageRequest, cachedImage, isIntermediateImage)
      if (hasImage && !isIntermediateImage) {
        // Immediately return since we have the full image
        return true
      }

      if (!hasImage) {
        // The image is not in cache -> Set up layers visible until the image is available
        drawable.placeholderLayer.setPlaceholder(imageRequest.resources, options)
        drawable.listenerManager.onPlaceholderSet(
            imageId,
            imageRequest,
            drawable.placeholderLayer.getDataModel().maybeGetDrawable(),
        )

        drawable.setupProgressLayer(imageRequest.resources, options)
      }

      // Fetch the image
      lightweightBackgroundThreadExecutor.execute {
        if (imageId != drawable.imageId) {
          return@execute
        }
        val dataSource: DataSource<CloseableReference<CloseableImage>> =
            vitoImagePipeline.fetchDecodedImage(imageRequest, callerContext, null, imageId)
        if (config.handleImageResultInBackground()) {
          dataSource.subscribe(
              BitmapPreparingImageFetchSubscriber(
                  imageId,
                  drawable,
                  imageToDataModelMapper,
                  debugOverlayHandler,
                  uiThreadExecutor,
                  lightweightBackgroundThreadExecutor,
              ),
              uiThreadExecutor,
          )
        } else {
          dataSource.subscribe(
              ImageFetchSubscriber(
                  imageId,
                  drawable,
                  imageToDataModelMapper,
                  debugOverlayHandler,
                  uiThreadExecutor,
              ),
              uiThreadExecutor,
          )
        }
        if (config.fixKotlinDataSourceRace()) {
          drawable.setDataSource(imageId, dataSource)
        } else {
          drawable.dataSource = dataSource
        }
      }
      drawable.setFetchSubmitted(true)
      drawable.invalidateSelf()
      debugOverlayHandler?.update(drawable)
      return false
    }
  }

  private fun setDrawableAsActualImage(
      drawable: KFrescoVitoDrawable,
      actualImageDrawable: Drawable,
      imageRequest: VitoImageRequest,
      imageId: Long,
  ) {
    val extras = drawable.obtainExtras()
    drawable.actualImageLayer.setActualImageDrawable(imageRequest.imageOptions, actualImageDrawable)
    drawable.listenerManager.onFinalImageSet(
        imageId,
        imageRequest,
        ImageOrigin.LOCAL,
        ImageInfoImpl(
            actualImageDrawable.intrinsicWidth,
            actualImageDrawable.intrinsicHeight,
            0,
            ImmutableQualityInfo.FULL_QUALITY,
            extras.imageExtras ?: emptyMap(),
        ),
        extras,
        drawable.actualImageDrawable,
    )
    drawable.onFadeListener?.onShownImmediately()
    debugOverlayHandler?.update(drawable)
  }

  override fun releaseDelayed(drawable: FrescoDrawableInterface) {
    traceSection("KFrescoController#releaseDelayed") {
      val vitoDrawable = drawable.asVitoDrawableOrNull() ?: return
      ImageReleaseScheduler.releaseDelayed(vitoDrawable, config.releaseDelayMs())
    }
  }

  override fun releaseNextFrame(drawable: FrescoDrawableInterface) {
    traceSection("KFrescoController#release") {
      val vitoDrawable = drawable.asVitoDrawableOrNull() ?: return
      ImageReleaseScheduler.releaseNextFrame(vitoDrawable)
    }
  }

  override fun releaseImmediately(drawable: FrescoDrawableInterface) {
    traceSection("KFrescoController#releaseImmediately") {
      val vitoDrawable = drawable.asVitoDrawableOrNull() ?: return
      ImageReleaseScheduler.releaseImmediately(vitoDrawable)
    }
  }

  private fun FrescoDrawableInterface.asVitoDrawableOrNull(): KFrescoVitoDrawable? {
    if (this !is KFrescoVitoDrawable) {
      FLog.e(TAG, "Drawable not supported $this")
      return null
    }
    return this
  }

  /**
   * Set the actual image to be the given image reference.
   *
   * @return true if the image has been set, false if it failed (e.g. invalid/null image reference)
   */
  private fun KFrescoVitoDrawable.setActualImage(
      imageRequest: VitoImageRequest,
      imageReference: CloseableReference<CloseableImage>?,
      isIntermediateImage: Boolean = false,
  ): Boolean {
    traceSection("KFrescoController#setActualImage") {
      try {
        if (!CloseableReference.isValid(imageReference)) {
          return false
        }

        val image = imageReference?.get() ?: return false
        setFetchSubmitted(true)
        closeable = imageReference.clone()
        actualImageLayer.setActualImage(
            imageRequest.resources,
            imageRequest.imageOptions,
            image,
            imageToDataModelMapper,
        )
        // TODO(T105148151): trigger listeners
        invalidateSelf()

        val imageInfo = image.imageInfo
        listenerManager.onImageSet(imageReference, viewportDimensions)

        if (isIntermediateImage) {
          listenerManager.onIntermediateImageSet(imageId, imageRequest, imageInfo)
        } else {
          listenerManager.onFinalImageSet(
              imageId,
              imageRequest,
              ImageOrigin.MEMORY_BITMAP_SHORTCUT,
              imageInfo,
              obtainExtras(image = imageReference),
              actualImageDrawable,
          )
        }
        onFadeListener?.onShownImmediately()
        debugOverlayHandler?.update(this)
        return true
      } finally {
        CloseableReference.closeSafely(imageReference)
      }
    }
  }

  private fun isAlreadyLoadingImage(
      imageRequest: VitoImageRequest,
      drawable: KFrescoVitoDrawable,
  ): Boolean =
      traceSection("KFrescoController#isAlreadyLoadingImage") {
        if (config.useSmartPropertyDiffing()) {
          imageRequest.equalsIfHasImage(drawable.imageRequest, drawable.hasImage())
        } else {
          imageRequest == drawable.imageRequest
        }
      }

  private fun createDrawableModel(drawable: Drawable, options: ImageOptions): ImageDataModel =
      if (drawable is Animatable) {
        AnimatedDrawableImageDataModel(drawable, drawable, options.shouldAutoPlay())
      } else {
        DrawableImageDataModel(
            drawable,
            options.actualImageScaleType == ScalingUtils.ScaleType.DISABLED,
        )
      }

  companion object {
    private const val TAG = "KFrescoController"
  }
}
