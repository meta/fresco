/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.datasource.DataSubscriber
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.drawee.components.DeferredReleaser
import com.facebook.drawee.drawable.ScaleTypeDrawable
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.fresco.ui.common.ControllerListener2.Extras
import com.facebook.fresco.vito.core.CombinedImageListener
import com.facebook.fresco.vito.core.ImagePerfLoggingListener
import com.facebook.fresco.vito.core.NopDrawable
import com.facebook.fresco.vito.core.VitoImagePerfListener
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.core.VitoImageRequestListener
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.listener.BaseRequestListener
import com.facebook.imagepipeline.listener.RequestListener
import kotlin.jvm.JvmField

class FrescoDrawable2Impl(
    private val useNewReleaseCallbacks: Boolean,
    imagePerfLoggingListener: ImagePerfLoggingListener?,
    vitoImagePerfListener: VitoImagePerfListener,
    override var uiFramework: String? = null,
    private val resetVitoImageRequestListener: Boolean = false,
    private val resetLocalVitoImageRequestListener: Boolean = false,
    private val resetLocalImagePerfStateListener: Boolean = false,
    private val resetControllerListener2: Boolean = false,
    private val preservePendingReleaseOnImageUpdate: Boolean = false,
) : FrescoDrawable2(), DataSubscriber<CloseableReference<CloseableImage>> {

  override var imageRequest: VitoImageRequest? = null
  override var callerContext: Any? = null
  var drawableDataSubscriber: DrawableDataSubscriber? = null
  var tempFinalImageExtras: Extras? = null

  @get:Synchronized @set:Synchronized override var imageId: Long = 0

  override var extras: Any? = null
  private var dataSource: DataSource<CloseableReference<CloseableImage>>? = null
  private var fetchSubmitted = false
  override var refetchRunnable: Runnable? = null
  val internalListener: CombinedImageListener = CombinedImageListenerImpl()
  override val imagePerfListener: VitoImagePerfListener
  override var forceReloadIfImageAlreadySet: Boolean = false
  override var retriggerListenersIfImageAlreadySet: Boolean = false

  init {
    internalListener.setImagePerfLoggingListener(imagePerfLoggingListener)
    imagePerfListener = vitoImagePerfListener
  }

  private val releaseRunnable = Runnable { scheduleReleaseNextFrame() }
  private var delayedReleasePending = false
  override val actualImageWrapper: ScaleTypeDrawable =
      ScaleTypeDrawable(NopDrawable, ScalingUtils.ScaleType.CENTER_CROP)

  // Image perf data fields
  val imageOriginListener: RequestListener =
      object : BaseRequestListener() {
        override fun onUltimateProducerReached(
            requestId: String,
            producerName: String,
            successful: Boolean,
        ) {
          imageOrigin = mapProducerNameToImageOrigin(producerName)
        }

        @ImageOrigin
        private fun mapProducerNameToImageOrigin(producerName: String): Int =
            when (producerName) {
              "BitmapMemoryCacheGetProducer",
              "BitmapMemoryCacheProducer",
              "PostprocessedBitmapMemoryCacheProducer" -> ImageOrigin.MEMORY_BITMAP

              "EncodedMemoryCacheProducer" -> ImageOrigin.MEMORY_ENCODED

              "DiskCacheProducer",
              "PartialDiskCacheProducer" -> ImageOrigin.DISK

              "NetworkFetchProducer" -> ImageOrigin.NETWORK

              "DataFetchProducer",
              "LocalAssetFetchProducer",
              "LocalContentUriFetchProducer",
              "LocalContentUriThumbnailFetchProducer",
              "LocalExifThumbnailProducer",
              "LocalFileFetchProducer",
              "LocalResourceFetchProducer",
              "LocalThumbnailBitmapSdk29Producer",
              "VideoThumbnailProducer",
              "QualifiedResourceFetchProducer" -> ImageOrigin.LOCAL

              else -> ImageOrigin.UNKNOWN
            }
      }

  @ImageOrigin var imageOrigin: Int = ImageOrigin.UNKNOWN

  @JvmField @VisibleForTesting var imageReference: CloseableReference<CloseableImage>? = null

  private var _intrinsicWidth = -1
  private var _intrinsicHeight = -1

  fun setImageDrawable(newDrawable: Drawable?): Drawable? = setImage(newDrawable, null)

  fun setImage(
      imageDrawable: Drawable?,
      imageReference: CloseableReference<CloseableImage>?,
  ): Drawable? {
    if (!preservePendingReleaseOnImageUpdate) {
      cancelReleaseNextFrame()
      cancelReleaseDelayed()
    }
    if (imageDrawable !== actualImageWrapper) {
      actualImageWrapper.setCurrent(NopDrawable)
    }
    CloseableReference.closeSafely(this.imageReference)
    this.imageReference = CloseableReference.cloneOrNull(imageReference)
    return setDrawable(IMAGE_DRAWABLE_INDEX, imageDrawable)
  }

  override val actualImageDrawable: Drawable?
    get() {
      val actual = getDrawable(IMAGE_DRAWABLE_INDEX)
      return if (actual === actualImageWrapper) {
        actualImageWrapper.drawable
      } else {
        actual
      }
    }

  @Synchronized
  fun setDataSource(imageId: Long, dataSource: DataSource<CloseableReference<CloseableImage>>?) {
    if (imageId != this.imageId) {
      return
    }

    val prevDataSource = this.dataSource
    if (prevDataSource != null && prevDataSource !== dataSource) {
      prevDataSource.close()
    }

    this.dataSource = dataSource
  }

  override val isFetchSubmitted: Boolean
    get() = fetchSubmitted

  override fun setFetchSubmitted(fetchSubmitted: Boolean) {
    this.fetchSubmitted = fetchSubmitted
  }

  override var imageListener: ImageListener?
    get() = internalListener.imageListener
    set(imageListener) {
      internalListener.imageListener = imageListener
    }

  fun setVitoImageRequestListener(listener: VitoImageRequestListener?) {
    internalListener.setVitoImageRequestListener(listener)
  }

  fun setLocalVitoImageRequestListener(listener: VitoImageRequestListener?) {
    internalListener.setLocalVitoImageRequestListener(listener)
  }

  override fun release() {
    close()
  }

  override fun reset() {
    // Close calls super.reset()
    _intrinsicWidth = -1
    _intrinsicHeight = -1
    close()
  }

  @Synchronized
  override fun close() {
    cancelReleaseNextFrame()
    cancelReleaseDelayed()
    if (useNewReleaseCallbacks && fetchSubmitted) {
      drawableDataSubscriber?.onRelease(this)
    }
    imageId = 0
    super.close()
    super.reset()
    actualImageWrapper.setCurrent(NopDrawable)
    CloseableReference.closeSafely(imageReference)
    imageReference = null
    drawableDataSubscriber = null
    dataSource?.close()
    dataSource = null
    fetchSubmitted = false
    imageRequest = null
    imageOrigin = ImageOrigin.UNKNOWN
    extras = null
    forceReloadIfImageAlreadySet = false
    retriggerListenersIfImageAlreadySet = false
    setOnFadeListener(null)
    tempFinalImageExtras = null
    internalListener.onReset(
        resetVitoImageRequestListener,
        resetLocalVitoImageRequestListener,
        resetLocalImagePerfStateListener,
        resetControllerListener2,
    )
  }

  fun scheduleReleaseDelayed(releaseDelayMs: Long) {
    if (delayedReleasePending) {
      return
    }
    handler.postDelayed(releaseRunnable, releaseDelayMs)
    delayedReleasePending = true
  }

  override fun cancelReleaseDelayed() {
    if (delayedReleasePending) {
      handler.removeCallbacks(releaseRunnable)
      delayedReleasePending = false
    }
  }

  fun scheduleReleaseNextFrame() {
    cancelReleaseDelayed()
    deferredReleaser.scheduleDeferredRelease(this)
    if (!useNewReleaseCallbacks) {
      drawableDataSubscriber?.onRelease(this)
    }
  }

  fun releaseImmediately() {
    if (!useNewReleaseCallbacks) {
      drawableDataSubscriber?.onRelease(this)
    }
    close()
  }

  override fun cancelReleaseNextFrame() {
    deferredReleaser.cancelDeferredRelease(this)
  }

  override fun onNewResult(p0: DataSource<CloseableReference<CloseableImage>>) {
    val imageRequest = this.imageRequest
    val drawableDataSubscriber = this.drawableDataSubscriber
    if (p0 !== this.dataSource || imageRequest == null || drawableDataSubscriber == null) {
      imagePerfListener.onIgnoreResult(this)
      return // We don't care
    }

    drawableDataSubscriber.onNewResult(this, imageRequest, p0)
  }

  override fun onFailure(p0: DataSource<CloseableReference<CloseableImage>>) {
    val imageRequest = this.imageRequest
    val drawableDataSubscriber = this.drawableDataSubscriber
    if (p0 !== this.dataSource || imageRequest == null || drawableDataSubscriber == null) {
      imagePerfListener.onIgnoreFailure(this)
      return // wrong image
    }
    drawableDataSubscriber.onFailure(this, imageRequest, p0)
  }

  override fun onCancellation(p0: DataSource<CloseableReference<CloseableImage>>): Unit =
      Unit // no-op

  override fun onProgressUpdate(p0: DataSource<CloseableReference<CloseableImage>>) {
    val imageRequest = this.imageRequest
    val drawableDataSubscriber = this.drawableDataSubscriber
    if (p0 !== this.dataSource || imageRequest == null || drawableDataSubscriber == null) {
      return // wrong image
    }
    drawableDataSubscriber.onProgressUpdate(this, imageRequest, p0)
  }

  override val actualImageWidthPx: Int
    /** @return the width of the underlying actual image or -1 if unset */
    get() = imageReference?.takeIf(CloseableReference<CloseableImage>::isValid)?.get()?.width ?: -1

  override val actualImageHeightPx: Int
    /** @return the height of the underlying actual image or -1 if unset */
    get() = imageReference?.takeIf(CloseableReference<CloseableImage>::isValid)?.get()?.height ?: -1

  override fun setIntrinsicSize(width: Int, height: Int) {
    _intrinsicWidth = width
    _intrinsicHeight = height
  }

  override fun getIntrinsicWidth(): Int =
      if (_intrinsicWidth != -1) {
        _intrinsicWidth
      } else {
        super.getIntrinsicWidth()
      }

  override fun getIntrinsicHeight(): Int =
      if (_intrinsicHeight != -1) {
        _intrinsicHeight
      } else {
        super.getIntrinsicHeight()
      }

  override fun getImagePerfLoggingListener(): ImagePerfLoggingListener? =
      internalListener.getImagePerfLoggingListener()

  /**
   * This function is not needed in this flow because it is already handled in
   * ScaleTypeDrawable.java
   */
  override fun configureWhenUnderlyingChanged(): Unit = Unit

  override fun hasBitmapWithGainmap(): Boolean =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        maybeGetActualImageBitmap()?.hasGainmap() == true
      } else {
        false
      }

  override fun reportVisible(visible: Boolean) {
    getImagePerfLoggingListener()?.reportVisible(visible)
  }

  private fun maybeGetActualImageBitmap(): Bitmap? {
    val drawable = actualImageDrawable
    return if (drawable is BitmapDrawable) {
      drawable.bitmap
    } else {
      null
    }
  }

  companion object {
    private val handler = Handler(Looper.getMainLooper())
    private val deferredReleaser = DeferredReleaser.getInstance()
  }
}
