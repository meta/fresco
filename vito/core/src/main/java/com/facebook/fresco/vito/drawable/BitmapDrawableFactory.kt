/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.drawable

import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import com.facebook.drawee.drawable.OrientedDrawable
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection

class BitmapDrawableFactory : ImageOptionsDrawableFactory {

  override fun createDrawable(
      resources: Resources,
      closeableImage: CloseableImage,
      imageOptions: ImageOptions,
  ): Drawable? =
      traceSection("BitmapDrawableFactory#createDrawable") {
        if (closeableImage is CloseableStaticBitmap) {
          handleCloseableStaticBitmap(resources, closeableImage, imageOptions)
        } else {
          null
        }
      }

  /**
   * We handle the given bitmap and return a Drawable ready for being displayed: If rounding is set,
   * the image will be rounded, if a border if set, the border will be applied and finally, the
   * image will be rotated if required.
   *
   * Bitmap -> border -> rounded corners -> RoundedBitmapDrawable (since bitmap is square) -> fully
   * circular -> CircularBorderBitmapDrawable (bitmap is circular) -> square image ->
   * RoundedBitmapDrawable (for border support) -> no border -> rounded corners ->
   * RoundedBitmapDrawable (since bitmap is square) -> fully circular -> BitmapDrawable (since
   * bitmap is circular) -> square image -> BitmapDrawable
   *
   * If needed, the resulting drawable is rotated using OrientedDrawable.
   *
   * @param closeableStaticBitmap the image to handle
   * @param imageOptions display options for the given image
   * @return the drawable to display
   */
  protected fun handleCloseableStaticBitmap(
      resources: Resources,
      closeableStaticBitmap: CloseableStaticBitmap,
      imageOptions: ImageOptions,
  ): Drawable? {
    val bitmap = closeableStaticBitmap.underlyingBitmap ?: return null
    val roundingOptions = imageOptions.roundingOptions
    val borderOptions = imageOptions.borderOptions
    val isBitmapRounded = true == closeableStaticBitmap.extras["is_rounded"]
    val drawable: Drawable =
        if (isBitmapRounded && roundingOptions != null && roundingOptions.isCircular) {
          if (borderOptions != null && borderOptions.width > 0) {
            CircularBorderBitmapDrawable(
                resources,
                bitmap,
                borderOptions,
            )
          } else {
            BitmapDrawable(resources, bitmap)
          }
        } else {
          RoundingUtils.roundedDrawable(
              resources,
              bitmap,
              borderOptions,
              roundingOptions,
              imageOptions.filterRoundedBitmaps,
          )
        }
    return rotatedDrawable(closeableStaticBitmap, drawable)
  }

  protected fun rotatedDrawable(
      closeableStaticBitmap: CloseableStaticBitmap,
      drawable: Drawable,
  ): Drawable =
      if (
          !hasTransformableRotationAngle(closeableStaticBitmap) &&
              !hasTransformableExifOrientation(closeableStaticBitmap)
      ) {
        // Return the bitmap drawable directly as there's nothing to transform in it
        drawable
      } else {
        OrientedDrawable(
            drawable,
            closeableStaticBitmap.rotationAngle,
            closeableStaticBitmap.exifOrientation,
        )
      }

  /* Returns true if there is anything to rotate using the rotation angle */
  private fun hasTransformableRotationAngle(closeableStaticBitmap: CloseableStaticBitmap): Boolean =
      closeableStaticBitmap.rotationAngle != 0 &&
          closeableStaticBitmap.rotationAngle != EncodedImage.UNKNOWN_ROTATION_ANGLE

  /* Returns true if there is anything to rotate using the EXIF orientation */
  private fun hasTransformableExifOrientation(
      closeableStaticBitmap: CloseableStaticBitmap,
  ): Boolean =
      closeableStaticBitmap.exifOrientation != ExifInterface.ORIENTATION_NORMAL &&
          closeableStaticBitmap.exifOrientation != ExifInterface.ORIENTATION_UNDEFINED
}
