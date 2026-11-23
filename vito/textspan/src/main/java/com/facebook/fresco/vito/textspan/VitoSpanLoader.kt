/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.textspan

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.lifecycle.AttachDetachListener
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.listener.ImageListener
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.provider.FrescoVitoProvider
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.widget.text.span.BetterImageSpan

/** Vito text span image loader implementation */
object VitoSpanLoader {

  @JvmStatic
  @JvmOverloads
  fun createSpan(
      resources: Resources,
      @BetterImageSpan.BetterImageSpanAlignment
      verticalAlignment: Int = BetterImageSpan.ALIGN_BASELINE,
  ): VitoSpan = VitoSpan(resources, createDrawable(), verticalAlignment)

  @JvmStatic
  fun show(
      imageSource: ImageSource,
      imageOptions: ImageOptions,
      logWithHighSamplingRate: Boolean = false,
      callerContext: Any?,
      contextChain: ContextChain?,
      imageListener: ImageListener?,
      target: VitoSpan,
  ) {
    show(
        FrescoVitoProvider.getImagePipeline()
            .createImageRequest(
                target.resources,
                imageSource,
                imageOptions,
                logWithHighSamplingRate,
            ),
        callerContext,
        contextChain,
        imageListener,
        target,
    )
  }

  @JvmStatic
  fun show(
      imageRequest: VitoImageRequest,
      callerContext: Any?,
      contextChain: ContextChain?,
      imageListener: ImageListener?,
      target: VitoSpan,
  ) {
    val fetchCommand = {
      FrescoVitoProvider.getController()
          .fetch(
              drawable = target.drawableInterface,
              imageRequest = imageRequest,
              callerContext = callerContext,
              contextChain = contextChain,
              listener = imageListener,
              onFadeListener = null,
              viewportDimensions = null,
          )
    }
    target.imageFetchCommand = fetchCommand
    fetchCommand()
  }

  /**
   * Sets up [target] as [show] does, without starting the request. [DeferredFetchVitoSpan] says
   * when it starts. A text that is built but never attached or drawn makes no request.
   */
  @JvmStatic
  fun showWhenAttachedOrDrawn(
      imageSource: ImageSource,
      imageOptions: ImageOptions,
      logWithHighSamplingRate: Boolean,
      callerContext: Any?,
      contextChain: ContextChain?,
      imageListener: ImageListener?,
      target: DeferredFetchVitoSpan,
  ) {
    val imageRequest =
        FrescoVitoProvider.getImagePipeline()
            .createImageRequest(
                target.resources,
                imageSource,
                imageOptions,
                logWithHighSamplingRate,
            )
    target.imageFetchCommand = {
      target.isFetchPending = false
      FrescoVitoProvider.getController()
          .fetch(
              drawable = target.drawableInterface,
              imageRequest = imageRequest,
              callerContext = callerContext,
              contextChain = contextChain,
              listener = imageListener,
              onFadeListener = null,
              viewportDimensions = null,
          )
    }
    target.isFetchPending = true
  }

  @JvmStatic
  fun release(target: VitoSpan?) {
    target?.let { FrescoVitoProvider.getController().releaseImmediately(target.drawableInterface) }
  }

  @JvmStatic
  fun setImageSpanOnBuilder(
      sb: Spannable,
      imageSpan: VitoSpan,
      startIndex: Int,
      endIndex: Int,
      imageWidthPx: Int,
      imageHeightPx: Int,
      parentView: View?,
  ) {
    if (endIndex > sb.length) {
      // Unfortunately, some callers use this wrong. The original implementation also swallows
      // an exception if this happens (e.g. if you tap on a video that has a minutiae as well.
      // Example: Text = "ABC", insert image at position 18.
      return
    }
    (imageSpan.drawableInterface as Drawable).setBounds(0, 0, imageWidthPx, imageHeightPx)
    imageSpan.parentView = parentView

    sb.setSpan(imageSpan, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
  }

  fun SpannableStringBuilder.setImageSpan(
      imageSpan: VitoSpan,
      startIndex: Int,
      endIndex: Int,
      imageWidthPx: Int,
      imageHeightPx: Int,
      parentView: View?,
  ) {
    setImageSpanOnBuilder(
        this,
        imageSpan,
        startIndex,
        endIndex,
        imageWidthPx,
        imageHeightPx,
        parentView,
    )
  }

  fun createDrawable(): FrescoDrawableInterface =
      FrescoVitoProvider.getController().createDrawable("textspan")

  class VitoAttachDetachListener(val vitoSpan: VitoSpan) : AttachDetachListener {
    override fun onAttachToView(view: View) {
      vitoSpan.parentView = view
      vitoSpan.imageFetchCommand?.let { it() }
    }

    override fun onDetachFromView(view: View) {
      vitoSpan.parentView = null
      release(vitoSpan)
    }
  }
}
