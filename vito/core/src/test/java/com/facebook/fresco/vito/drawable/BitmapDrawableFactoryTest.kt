/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.drawable

import android.annotation.TargetApi
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.DisplayMetrics
import com.facebook.drawee.drawable.RoundedBitmapDrawable
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.fresco.vito.options.RoundingOptions
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BitmapDrawableFactoryTest {

  private val imageExtrasRounded: Map<String, Any> = mapOf("is_rounded" to true)
  private val imageExtrasNotRounded: Map<String, Any> = mapOf("is_rounded" to false)

  private lateinit var resources: Resources
  private lateinit var displayMetrics: DisplayMetrics
  private lateinit var drawableFactory: ImageOptionsDrawableFactory

  @Before
  fun setup() {
    resources = mock()
    displayMetrics = DisplayMetrics()
    whenever(resources.displayMetrics).thenReturn(displayMetrics)
    drawableFactory = BitmapDrawableFactory()
  }

  @Test
  fun testCreateDrawable_whenImageUnknown_thenReturnNull() {
    val closeableImage = mock<CloseableImage>()
    val options = mock<ImageOptions>()
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNull()
  }

  @Test
  fun testCreateDrawable_whenImageIsStaticBitmap_thenReturnBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(BitmapDrawable::class.java)
  }

  @Test
  fun testCreateDrawable_whenRoundAsCircleAndJavaRounding_thenReturnRoundedBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.asCircle())
    val factory = BitmapDrawableFactory()
    val drawable = factory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(RoundedBitmapDrawable::class.java)
  }

  @Test
  fun testCreateDrawable_whenRoundAsCircleAndNativeRounding_thenReturnBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    whenever(closeableImage.extras).thenReturn(imageExtrasRounded)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.asCircle())
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(BitmapDrawable::class.java)
  }

  @Test
  fun testCreateDrawable_whenRoundWithCornerRadius_thenReturnBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.forCornerRadiusPx(123f))
    whenever(closeableImage.extras).thenReturn(imageExtrasNotRounded)
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((drawable as RoundedBitmapDrawable?)?.radii)
        .isEqualTo(floatArrayOf(123f, 123f, 123f, 123f, 123f, 123f, 123f, 123f))
  }

  @Test
  fun testCreateDrawable_whenRoundWithCornerRadii_thenReturnBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.forCornerRadii(1f, 2f, 3f, 4f))
    whenever(closeableImage.extras).thenReturn(imageExtrasNotRounded)
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((drawable as RoundedBitmapDrawable?)?.radii)
        .isEqualTo(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f))
  }

  @Test
  fun testCreateDrawable_whenAlreadyRoundedWithBorder_thenReturnBitmapDrawable() {
    val closeableImage = mock<CloseableStaticBitmap>()
    val bitmap = mock<Bitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    whenever(closeableImage.extras).thenReturn(imageExtrasRounded)
    val options = mock<ImageOptions>()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)
    whenever(options.borderOptions).thenReturn(borderOptions)
    whenever(options.roundingOptions)
        .thenReturn(RoundingOptions.asCircle(antiAliasing = false, forceRoundAtDecode = false))
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(CircularBorderBitmapDrawable::class.java)
    assertThat((drawable as CircularBorderBitmapDrawable?)?.border).isEqualTo(borderOptions)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.P])
  @TargetApi(Build.VERSION_CODES.O)
  fun testCreateDrawable_whenHardwareBitmapNotRoundedAtDecode_thenRoundsAtRenderTime() {
    val closeableImage = hardwareBitmapImage()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)

    val circle = createRoundedDrawable(closeableImage, RoundingOptions.asCircle(), null)
    val circleWithBorder =
        createRoundedDrawable(closeableImage, RoundingOptions.asCircle(), borderOptions)
    val corners = createRoundedDrawable(closeableImage, RoundingOptions.forCornerRadiusPx(8f), null)
    val cornersWithBorder =
        createRoundedDrawable(closeableImage, RoundingOptions.forCornerRadiusPx(8f), borderOptions)

    assertThat(circle.isCircle).isTrue()
    assertThat(circleWithBorder.isCircle).isTrue()
    assertThat(circleWithBorder.borderWidth).isEqualTo(10f)
    assertThat(corners.radii).isEqualTo(FloatArray(8) { 8f })
    assertThat(cornersWithBorder.radii).isEqualTo(FloatArray(8) { 8f })
    assertThat(cornersWithBorder.borderWidth).isEqualTo(10f)
    listOf(circle, circleWithBorder, corners, cornersWithBorder).forEach { drawable ->
      assertThat(drawable.bitmap?.config).isEqualTo(Bitmap.Config.HARDWARE)
      assertThat(drawable.paintFilterBitmap).isFalse()
    }
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.P])
  fun testCreateDrawable_whenFilterRoundedBitmaps_thenFiltersRoundedBitmapDrawable() {
    val closeableImage = hardwareBitmapImage()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)

    listOf(
        createRoundedDrawable(closeableImage, RoundingOptions.asCircle(), null, filter = true),
        createRoundedDrawable(
            closeableImage,
            RoundingOptions.forCornerRadiusPx(8f),
            borderOptions,
            filter = true,
        ),
        createRoundedDrawable(closeableImage, null, borderOptions, filter = true),
    )
        .forEach { drawable -> assertThat(drawable.paintFilterBitmap).isTrue() }
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.P])
  fun testCreateDrawable_whenFilterRoundedBitmapsAndNotRounded_thenReturnBitmapDrawable() {
    val options = mock<ImageOptions>()
    whenever(options.filterRoundedBitmaps).thenReturn(true)
    val drawable = drawableFactory.createDrawable(resources, hardwareBitmapImage(), options)

    assertThat(drawable).isExactlyInstanceOf(BitmapDrawable::class.java)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.P])
  fun testCreateDrawable_whenDefaultsFilterRoundedBitmaps_thenRequestCanOptOut() {
    val originalDefaults = ImageOptions.defaults()
    try {
      ImageOptions.setDefaults(originalDefaults.extend().filterRoundedBitmaps(true).build())
      val rounded = ImageOptions.create().round(RoundingOptions.forCornerRadiusPx(8f))

      val fromDefaults =
          drawableFactory.createDrawable(resources, hardwareBitmapImage(), rounded.build())
      val optedOut =
          drawableFactory.createDrawable(
              resources,
              hardwareBitmapImage(),
              rounded.filterRoundedBitmaps(false).build(),
          )

      assertThat((fromDefaults as RoundedBitmapDrawable).paintFilterBitmap).isTrue()
      assertThat((optedOut as RoundedBitmapDrawable).paintFilterBitmap).isFalse()
    } finally {
      ImageOptions.setDefaults(originalDefaults)
    }
  }

  @TargetApi(Build.VERSION_CODES.O)
  private fun hardwareBitmapImage(): CloseableStaticBitmap {
    val bitmap = mock<Bitmap>()
    whenever(bitmap.config).thenReturn(Bitmap.Config.HARDWARE)
    val closeableImage = mock<CloseableStaticBitmap>()
    whenever(closeableImage.underlyingBitmap).thenReturn(bitmap)
    whenever(closeableImage.extras).thenReturn(imageExtrasNotRounded)
    return closeableImage
  }

  private fun createRoundedDrawable(
      closeableImage: CloseableStaticBitmap,
      roundingOptions: RoundingOptions?,
      borderOptions: BorderOptions?,
      filter: Boolean = false,
  ): RoundedBitmapDrawable {
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(roundingOptions)
    whenever(options.borderOptions).thenReturn(borderOptions)
    whenever(options.filterRoundedBitmaps).thenReturn(filter)
    val drawable = drawableFactory.createDrawable(resources, closeableImage, options)
    assertThat(drawable).isExactlyInstanceOf(RoundedBitmapDrawable::class.java)
    return drawable as RoundedBitmapDrawable
  }
}
