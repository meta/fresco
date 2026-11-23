/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.facebook.common.logging.FLog
import com.facebook.common.references.CloseableReference
import com.facebook.drawee.drawable.ForwardingDrawable
import com.facebook.drawee.drawable.ScaleTypeDrawable
import com.facebook.fresco.vito.core.NopDrawable
import com.facebook.fresco.vito.drawable.RoundingUtils
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.systrace.FrescoSystrace

open class HierarcherImpl(private val drawableFactory: ImageOptionsDrawableFactory) : Hierarcher {

  private val TAG = "HierarcherImpl"

  override fun buildActualImageDrawable(
      resources: Resources,
      imageOptions: ImageOptions,
      closeableImage: CloseableReference<CloseableImage>,
  ): Drawable? {
    val drawableFactory = imageOptions.customDrawableFactory ?: drawableFactory
    return drawableFactory.createDrawable(resources, closeableImage.get(), imageOptions)
  }

  override fun buildPlaceholderDrawable(
      resources: Resources,
      imageOptions: ImageOptions,
  ): Drawable? {
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.beginSection("HierarcherImpl#buildPlaceholderDrawable")
    }
    return try {
      var placeholderDrawable = imageOptions.placeholderDrawable
      if (placeholderDrawable == null && imageOptions.placeholderRes != 0) {
        try {
          placeholderDrawable = resources.getDrawable(imageOptions.placeholderRes)
        } catch (e: Resources.NotFoundException) {
          FLog.e(TAG, "Placeholder drawable not found in Resources", e)
        }
      }
      if (placeholderDrawable == null) {
        placeholderDrawable = imageOptions.placeholderColor?.let(::ColorDrawable)
      }

      if (placeholderDrawable == null) {
        return NopDrawable
      }
      if (imageOptions.placeholderApplyRoundingOptions) {
        placeholderDrawable = applyRoundingOptions(resources, placeholderDrawable, imageOptions)
      }
      imageOptions.placeholderScaleType?.let { ScaleTypeDrawable(placeholderDrawable, it) }
          ?: placeholderDrawable
    } finally {
      if (FrescoSystrace.isTracing()) {
        FrescoSystrace.endSection()
      }
    }
  }

  override fun applyRoundingOptions(
      resources: Resources,
      drawable: Drawable,
      imageOptions: ImageOptions,
  ): Drawable {
    val roundingOptions = imageOptions.roundingOptions
    val borderOptions = imageOptions.borderOptions
    return RoundingUtils.roundedDrawable(
        resources,
        drawable,
        borderOptions,
        roundingOptions,
        imageOptions.filterRoundedBitmaps,
    )
  }

  override fun buildProgressDrawable(resources: Resources, imageOptions: ImageOptions): Drawable? {
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.beginSection("HierarcherImpl#buildProgressDrawable")
    }
    return try {
      if (imageOptions.progressRes == 0 && imageOptions.progressDrawable == null) {
        return null
      }
      var progressDrawable = imageOptions.progressDrawable
      if (progressDrawable == null) {
        progressDrawable = resources.getDrawable(imageOptions.progressRes)
      }
      if (progressDrawable == null) {
        return null
      }

      progressDrawable.setLevel(0)
      imageOptions.progressScaleType?.let { ScaleTypeDrawable(progressDrawable, it) }
          ?: progressDrawable
    } finally {
      if (FrescoSystrace.isTracing()) {
        FrescoSystrace.endSection()
      }
    }
  }

  override fun buildErrorDrawable(resources: Resources, imageOptions: ImageOptions): Drawable? {
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.beginSection("HierarcherImpl#buildErrorDrawable")
    }
    return try {
      var drawable = imageOptions.errorDrawable
      if (drawable == null && imageOptions.errorRes != 0) {
        drawable = resources.getDrawable(imageOptions.errorRes)
      } else if (drawable == null) {
        drawable = imageOptions.errorColor?.let(::ColorDrawable)
      }
      if (drawable == null) {
        return null
      }

      if (imageOptions.errorApplyRoundingOptions) {
        drawable = applyRoundingOptions(resources, drawable, imageOptions)
      }
      imageOptions.errorScaleType?.let {
        ScaleTypeDrawable(drawable, it, imageOptions.errorFocusPoint)
      } ?: drawable
    } finally {
      if (FrescoSystrace.isTracing()) {
        FrescoSystrace.endSection()
      }
    }
  }

  override fun buildActualImageWrapper(
      imageOptions: ImageOptions,
      callerContext: Any?,
  ): ForwardingDrawable {
    val wrapper = ScaleTypeDrawable(
        NopDrawable,
        imageOptions.actualImageScaleType,
        imageOptions.actualImageFocusPoint,
    )
    imageOptions.actualImageColorFilter?.let(wrapper::setColorFilter)
    return wrapper
  }

  override fun setupActualImageWrapper(
      actualImageWrapper: ScaleTypeDrawable,
      imageOptions: ImageOptions,
      callerContext: Any?,
  ) {
    actualImageWrapper.scaleType = imageOptions.actualImageScaleType
    actualImageWrapper.focusPoint = imageOptions.actualImageFocusPoint
    actualImageWrapper.colorFilter = imageOptions.actualImageColorFilter
  }

  override fun buildOverlayDrawable(resources: Resources, imageOptions: ImageOptions): Drawable? {
    val overlayDrawable = imageOptions.overlayDrawable
    if (overlayDrawable != null) {
      return overlayDrawable
    }
    val resId = imageOptions.overlayRes
    return if (resId == 0) null else resources.getDrawable(imageOptions.overlayRes)
  }
}
