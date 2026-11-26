/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.datasource.DataSubscriber
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.renderer.ImageDataModel
import com.facebook.imagepipeline.image.CloseableImage
import java.util.concurrent.Executor

class ImageFetchSubscriber(
    private val imageId: Long,
    private val drawable: KFrescoVitoDrawable,
    private val imageToDataModelMapper:
        (Resources, CloseableImage, ImageOptions) -> ImageDataModel?,
    private val debugOverlayHandler: VitoDebugOverlay? = null,
    private val invalidationExecutor: Executor? = null,
) : DataSubscriber<CloseableReference<CloseableImage>> {
  override fun onNewResult(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    if (imageId != drawable.imageId) {
      return
    }
    val request = drawable.imageRequest ?: return

    val result: CloseableReference<CloseableImage>? = dataSource.result

    if (result == null || !result.isValid) {
      onFailure(dataSource)
      result?.close()
      return
    }

    // We avoid cloning result and closing the original for performance reasons
    drawable.closeable = result
    val image = result.get()
    drawable.actualImageLayer.setActualImage(
        request.resources,
        request.imageOptions,
        image,
        imageToDataModelMapper,
    )
    invalidationExecutor?.execute {
      val fadeListener = drawable.onFadeListener
      val fadeDurationMs = request.imageOptions.fadeDurationMs
      if (fadeListener != null && fadeDurationMs > 0) {
        fadeListener.onFadeStarted()
        drawable.actualImageLayer.fadeIn(fadeDurationMs) {
          // A reset cuts the fade short and clears the listener: only the still-current
          // listener gets the finish callback (FadeDrawable fires none on reset).
          if (drawable.onFadeListener === fadeListener) {
            fadeListener.onFadeFinished()
          }
        }
      } else {
        drawable.actualImageLayer.fadeIn(fadeDurationMs)
        fadeListener?.onShownImmediately()
      }
      drawable.placeholderLayer.fadeOut(fadeDurationMs, true)
    }
    // Remove the progress image
    if (dataSource.isFinished) {
      drawable.hideProgressLayer()
    }
    val imageInfo = image.imageInfo
    if (notifyFinalResult(dataSource)) {
      drawable.listenerManager.onFinalImageSet(
          imageId,
          request,
          originFromExtras(dataSource),
          imageInfo,
          drawable.obtainExtras(dataSource, result),
          drawable.actualImageDrawable,
      )
    } else {
      drawable.listenerManager.onIntermediateImageSet(imageId, request, imageInfo)
    }
    invalidate(drawable)
  }

  override fun onFailure(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    if (imageId != drawable.imageId) {
      return
    }
    val request = drawable.imageRequest ?: return
    drawable.actualImageLayer.setError(request.resources, request.imageOptions)
    drawable.onFadeListener?.onShownImmediately()
    if (dataSource.isFinished) {
      drawable.hideProgressLayer()
    }
    if (notifyFinalResult(dataSource)) {
      dataSource.result.use { result ->
        drawable.listenerManager.onFailure(
            imageId,
            request,
            drawable.actualImageLayer.getDataModel().maybeGetDrawable(),
            dataSource.failureCause,
            drawable.obtainExtras(dataSource, result),
        )
      }
    } else {
      drawable.listenerManager.onIntermediateImageFailed(
          imageId,
          request,
          dataSource.failureCause,
      )
    }
    drawable.imagePerfListener.onImageError(drawable)
    invalidate(drawable)
  }

  override fun onCancellation(dataSource: DataSource<CloseableReference<CloseableImage>>) = Unit

  override fun onProgressUpdate(dataSource: DataSource<CloseableReference<CloseableImage>>) {
    drawable.updateProgress(dataSource)
    debugOverlayHandler?.update(drawable)
  }

  private fun invalidate(drawable: KFrescoVitoDrawable) {
    invalidationExecutor?.execute { drawable.invalidateSelf() } ?: drawable.invalidateSelf()
    debugOverlayHandler?.update(drawable)
  }

  private fun notifyFinalResult(
      dataSource: DataSource<CloseableReference<CloseableImage>>?,
  ): Boolean {
    return dataSource == null || dataSource.isFinished || dataSource.hasMultipleResults()
  }

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
