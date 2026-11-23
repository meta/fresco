/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import com.facebook.drawee.drawable.Rounded
import com.facebook.drawee.drawable.RoundedBitmapDrawable
import com.facebook.drawee.drawable.RoundedColorDrawable
import com.facebook.drawee.drawable.RoundedCornersDrawable
import com.facebook.drawee.drawable.RoundedNinePatchDrawable
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.RoundingOptions

/**
 * A class that contains helper methods for rounding a bitmap or different kind of Drawables. It
 * handles the conversion to the specific types of drawables.
 *
 * Different combinations are:<br></br>
 * - [Bitmap] -> already rounded -> no border -> circular -> [BitmapDrawable]<br></br>
 * - [Bitmap] -> already rounded -> border -> circular -> [CircularBorderBitmapDrawable] <br></br>
 * - [Bitmap] -> already rounder or not -> rounded corners -> [RoundedBitmapDrawable] <br></br>
 * - [BitmapDrawable] -> [RoundedBitmapDrawable]<br></br>
 * - [ColorDrawable] -> [RoundedColorDrawable]<br></br>
 * - [NinePatchDrawable] -> [RoundedNinePatchDrawable]<br></br>
 */
object RoundingUtils {

  /**
   * Creates a drawable with the [RoundingOptions] and [BorderOptions] applied to it.
   *
   * @param bitmap a bitmap to be wrapped in the final [BitmapDrawable]
   * @param borderOptions border options for the given image
   * @param roundingOptions rounding options for the given image
   * @param filterBitmap whether a [RoundedBitmapDrawable] filters the bitmap when it's scaled
   * @return a drawable with the applied effect
   */
  @JvmOverloads
  fun roundedDrawable(
      resources: Resources,
      bitmap: Bitmap,
      borderOptions: BorderOptions?,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean = false,
  ): Drawable =
      if (borderOptions != null && borderOptions.width > 0) {
        roundedDrawableWithBorder(resources, bitmap, borderOptions, roundingOptions, filterBitmap)
      } else {
        roundedDrawableWithoutBorder(resources, bitmap, roundingOptions, filterBitmap)
      }

  /**
   * Creates a drawable with the [RoundingOptions] and [BorderOptions] applied to it.
   *
   * @param drawable the image to transform
   * @param borderOptions border options for the given image
   * @param roundingOptions rounding options for the given image
   * @param filterBitmap whether a [RoundedBitmapDrawable] filters the bitmap when it's scaled
   * @return a drawable with the applied effect
   */
  @JvmOverloads
  fun roundedDrawable(
      resources: Resources,
      drawable: Drawable,
      borderOptions: BorderOptions?,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean = false,
  ): Drawable =
      if (borderOptions != null && borderOptions.width > 0) {
        roundedDrawableWithBorder(resources, drawable, borderOptions, roundingOptions, filterBitmap)
      } else {
        roundedDrawableWithoutBorder(resources, drawable, roundingOptions, filterBitmap)
      }

  private fun roundedDrawableWithoutBorder(
      resources: Resources,
      bitmap: Bitmap,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean,
  ): Drawable =
      if (roundingOptions == null) {
        BitmapDrawable(resources, bitmap)
      } else {
        applyRounding(getRoundedDrawable(resources, bitmap, filterBitmap), null, roundingOptions)
      }

  private fun roundedDrawableWithBorder(
      resources: Resources,
      bitmap: Bitmap,
      borderOptions: BorderOptions,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean,
  ): Drawable =
      if (roundingOptions == null) {
        squareDrawableWithBorder(getRoundedDrawable(resources, bitmap, filterBitmap), borderOptions)
      } else {
        applyRounding(
            getRoundedDrawable(resources, bitmap, filterBitmap),
            borderOptions,
            roundingOptions,
        )
      }

  private fun roundedDrawableWithoutBorder(
      resources: Resources,
      drawable: Drawable,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean,
  ): Drawable =
      if (roundingOptions != null) {
        applyRounding(getRoundedDrawable(resources, drawable, filterBitmap), null, roundingOptions)
      } else {
        drawable
      }

  private fun roundedDrawableWithBorder(
      resources: Resources,
      drawable: Drawable,
      borderOptions: BorderOptions,
      roundingOptions: RoundingOptions?,
      filterBitmap: Boolean,
  ): Drawable =
      if (roundingOptions == null) {
        squareDrawableWithBorder(
            getRoundedDrawable(resources, drawable, filterBitmap),
            borderOptions,
        )
      } else {
        applyRounding(
            getRoundedDrawable(resources, drawable, filterBitmap),
            borderOptions,
            roundingOptions,
        )
      }

  @Suppress("UNCHECKED_CAST")
  private fun <T> getRoundedDrawable(
      resources: Resources,
      drawable: Drawable,
      filterBitmap: Boolean,
  ): T where T : Drawable, T : Rounded =
      when (drawable) {
        is BitmapDrawable -> getRoundedDrawable(resources, drawable.bitmap, filterBitmap)
        is NinePatchDrawable -> RoundedNinePatchDrawable(drawable) as T
        is ColorDrawable -> RoundedColorDrawable.fromColorDrawable(drawable) as T
        else -> RoundedCornersDrawable(drawable) as T
      }

  private fun <T> applyRounding(
      drawable: T,
      borderOptions: BorderOptions?,
      roundingOptions: RoundingOptions,
  ): Drawable where T : Drawable, T : Rounded =
      if (!roundingOptions.isCircular) {
        roundedCornerDrawable(drawable, borderOptions, roundingOptions)
      } else {
        circularDrawable(drawable, borderOptions)
      }

  private fun <T> squareDrawableWithBorder(drawable: T, borderOptions: BorderOptions): Drawable
      where T : Drawable, T : Rounded =
      // We use the same rounded corner drawable to draw the border without applying rounding
      roundedCornerDrawable(drawable, borderOptions, null)

  @Suppress("UNCHECKED_CAST")
  private fun <T> getRoundedDrawable(
      resources: Resources,
      bitmap: Bitmap?,
      filterBitmap: Boolean,
  ): T where T : Drawable, T : Rounded =
      RoundedBitmapDrawable(resources, bitmap).apply { paintFilterBitmap = filterBitmap } as T

  private fun <T> circularDrawable(drawable: T, borderOptions: BorderOptions?): Drawable
      where T : Drawable, T : Rounded {
    drawable.isCircle = true
    if (borderOptions != null) {
      applyBorders(drawable, borderOptions)
    }
    return drawable
  }

  private fun <T> roundedCornerDrawable(
      drawable: T,
      borderOptions: BorderOptions?,
      roundingOptions: RoundingOptions?,
  ): Drawable where T : Drawable, T : Rounded {
    if (borderOptions != null) {
      applyBorders(drawable, borderOptions)
    }
    if (roundingOptions != null) {
      val radii = roundingOptions.cornerRadii
      if (radii != null) {
        drawable.radii = radii
      } else {
        drawable.setRadius(roundingOptions.cornerRadius)
      }
    }
    return drawable
  }

  /**
   * Applies the border according to [BorderOptions]
   *
   * @param drawable the drawable where the borders are applied
   * @param borderOptions [BorderOptions]
   */
  private fun <T> applyBorders(drawable: T, borderOptions: BorderOptions)
      where T : Drawable, T : Rounded {
    drawable.setBorder(borderOptions.color, borderOptions.width)
    drawable.padding = borderOptions.padding
  }
}
