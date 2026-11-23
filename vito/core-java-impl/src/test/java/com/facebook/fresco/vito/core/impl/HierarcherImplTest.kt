/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import com.facebook.drawee.drawable.RoundedBitmapDrawable
import com.facebook.drawee.drawable.RoundedColorDrawable
import com.facebook.drawee.drawable.ScaleTypeDrawable
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.fresco.vito.options.RoundingOptions
import kotlin.test.fail
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HierarcherImplTest {

  private lateinit var resources: Resources
  private lateinit var displayMetrics: DisplayMetrics
  private lateinit var drawable: Drawable
  private lateinit var hierarcher: Hierarcher
  private lateinit var drawableFactory: ImageOptionsDrawableFactory

  @Before
  fun setup() {
    resources = mock()
    drawable = mock()
    drawableFactory = mock()
    displayMetrics = DisplayMetrics()
    whenever(resources.displayMetrics).thenReturn(displayMetrics)
    whenever(resources.getDrawable(eq(RES_ID))).thenReturn(drawable)
    whenever(resources.getDrawable(AdditionalMatchers.not(eq(RES_ID))))
        .thenThrow(Resources.NotFoundException())
    hierarcher = HierarcherImpl(drawableFactory)
  }

  @Test
  fun testBuildPlaceholderRes() {
    val options = ImageOptions.create().placeholderRes(RES_ID).build()
    val errorDrawable = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(errorDrawable).isNotNull()
    assertThat(errorDrawable).isInstanceOf(ScaleTypeDrawable::class.java)
    val scaleTypeDrawable = errorDrawable as ScaleTypeDrawable
    assertThat(scaleTypeDrawable.scaleType).isEqualTo(ImageOptions.defaults().placeholderScaleType)
    assertThat(scaleTypeDrawable.focusPoint)
        .isEqualTo(ImageOptions.defaults().placeholderFocusPoint)
    assertThat(scaleTypeDrawable.current).isEqualTo(drawable)
  }

  @Test
  fun testBuildPlaceholderDrawable() {
    val expected = ColorDrawable(Color.YELLOW)
    val options = ImageOptions.create().placeholder(expected).placeholderScaleType(null).build()
    val result = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(result).isEqualTo(expected)
  }

  @Test
  fun testBuildPlaceholderDrawableScale() {
    val expected = ColorDrawable(Color.YELLOW)
    val options =
        ImageOptions.create()
            .placeholder(expected)
            .placeholderScaleType(ScalingUtils.ScaleType.CENTER)
            .build()
    val result = hierarcher.buildPlaceholderDrawable(resources, options)
    if (result == null) {
      fail("not null value expected")
      return
    }
    assertThat(result).isExactlyInstanceOf(ScaleTypeDrawable::class.java)
    assertThat(result.current).isEqualTo(expected)
  }

  @Test
  fun testApplyRoundingOptions_whenRoundAsCircle_thenReturnDrawable() {
    val drawable = ColorDrawable(Color.YELLOW)
    whenever(resources.getDrawable(RES_ID)).thenReturn(drawable)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.asCircle())
    whenever(options.placeholderDrawable).thenReturn(drawable)
    whenever(options.placeholderApplyRoundingOptions).thenReturn(true)
    var result = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(result).isExactlyInstanceOf(RoundedColorDrawable::class.java)
    whenever(options.placeholderDrawable).thenReturn(null)
    whenever(options.placeholderRes).thenReturn(RES_ID)
    result = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(result).isExactlyInstanceOf(RoundedColorDrawable::class.java)
  }

  @Test
  fun testApplyRoundingOptions_whenRoundWithCornerRadius_thenReturnDrawable() {
    val drawable = mock<BitmapDrawable>()
    val bitmap = mock<Bitmap>()
    whenever(drawable.bitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.forCornerRadiusPx(123f))
    whenever(options.placeholderDrawable).thenReturn(drawable)
    whenever(options.placeholderApplyRoundingOptions).thenReturn(true)
    val result = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(result).isNotNull()
    assertThat(result).isInstanceOf(RoundedBitmapDrawable::class.java)
    val roundedBitmapDrawable = result as RoundedBitmapDrawable
    assertThat(roundedBitmapDrawable.radii)
        .isEqualTo(floatArrayOf(123f, 123f, 123f, 123f, 123f, 123f, 123f, 123f))
  }

  @Test
  fun testApplyRoundingOptions_whenRoundWithCornerRadii_thenReturnDrawable() {
    val drawable = mock<BitmapDrawable>()
    val bitmap = mock<Bitmap>()
    whenever(drawable.bitmap).thenReturn(bitmap)
    val options = mock<ImageOptions>()
    whenever(options.roundingOptions).thenReturn(RoundingOptions.forCornerRadii(1f, 2f, 3f, 4f))
    whenever(options.placeholderDrawable).thenReturn(drawable)
    whenever(options.placeholderApplyRoundingOptions).thenReturn(true)
    val result = hierarcher.buildPlaceholderDrawable(resources, options)
    assertThat(result).isNotNull()
    assertThat(result).isInstanceOf(RoundedBitmapDrawable::class.java)
    val roundedBitmapDrawable = result as RoundedBitmapDrawable
    assertThat(roundedBitmapDrawable.radii).isEqualTo(floatArrayOf(1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f))
  }

  @Test
  fun testBuildErrorDrawable_whenNoScaleTypeSet_thenUseDefaultScaleType() {
    val options = ImageOptions.create().errorRes(RES_ID).build()
    val errorDrawable = hierarcher.buildErrorDrawable(resources, options)
    assertThat(errorDrawable).isNotNull()
    assertThat(errorDrawable).isInstanceOf(ScaleTypeDrawable::class.java)
    val scaleTypeDrawable = errorDrawable as ScaleTypeDrawable
    assertThat(scaleTypeDrawable.scaleType).isEqualTo(ImageOptions.defaults().errorScaleType)
    assertThat(scaleTypeDrawable.focusPoint).isEqualTo(ImageOptions.defaults().errorFocusPoint)
    assertThat(scaleTypeDrawable.current).isEqualTo(drawable)
  }

  @Test
  fun testBuildErrorDrawable_whenScaleTypeNull_thenDoNotWrapDrawable() {
    val options = ImageOptions.create().errorRes(RES_ID).errorScaleType(null).build()
    val errorDrawable = hierarcher.buildErrorDrawable(resources, options)
    assertThat(errorDrawable).isEqualTo(drawable)
  }

  @Test(expected = Resources.NotFoundException::class)
  fun testBuildErrorDrawable_whenInvalidResId_thenThrowNotFoundException() {
    val options = ImageOptions.create().errorRes(INVALID_RES_ID).build()
    hierarcher.buildErrorDrawable(resources, options)
  }

  @Test
  fun testBuildErrorDrawable_whenScaleTypeSet_thenReturnScaleTypeDrawable() {
    val focusPoint = PointF(100f, 234f)
    val options =
        ImageOptions.create()
            .errorRes(RES_ID)
            .errorScaleType(ScalingUtils.ScaleType.FOCUS_CROP)
            .errorFocusPoint(focusPoint)
            .build()
    val errorDrawable = hierarcher.buildErrorDrawable(resources, options)
    assertThat(errorDrawable).isNotNull()
    assertThat(errorDrawable).isInstanceOf(ScaleTypeDrawable::class.java)
    val scaleTypeDrawable = errorDrawable as ScaleTypeDrawable
    assertThat(scaleTypeDrawable.scaleType).isEqualTo(ScalingUtils.ScaleType.FOCUS_CROP)
    assertThat(scaleTypeDrawable.focusPoint).isEqualTo(focusPoint)
    assertThat(scaleTypeDrawable.current).isEqualTo(drawable)
  }

  @Test
  fun testBuildErrorDrawable_whenNotSet_thenReturnNopDrawable() {
    val options = ImageOptions.create().build()
    val errorDrawable = hierarcher.buildErrorDrawable(resources, options)
    assertThat(errorDrawable).isNull()
  }

  @Test
  fun testBuildProgressDrawable() {
    val drawable = ColorDrawable(0x0)
    val imageOptions =
        ImageOptions.create()
            .progress(drawable)
            .progressScaleType(ScalingUtils.ScaleType.FIT_CENTER)
            .build()
    val actual = hierarcher.buildProgressDrawable(resources, imageOptions)
    assertThat(actual).isInstanceOf(ScaleTypeDrawable::class.java)
    val scaleTypeActual = actual as ScaleTypeDrawable
    assertThat(scaleTypeActual.scaleType).isEqualTo(ScalingUtils.ScaleType.FIT_CENTER)
    assertThat(scaleTypeActual.current).isEqualTo(drawable)
  }

  @Test
  fun testBuildActualImageWrapper() {
    val expectedFocusPoint = PointF(1f, 2f)
    val imageOptions =
        ImageOptions.create()
            .scale(ScalingUtils.ScaleType.FIT_CENTER)
            .focusPoint(expectedFocusPoint)
            .build()
    val actual: Drawable = hierarcher.buildActualImageWrapper(imageOptions, null)
    assertThat(actual).isInstanceOf(ScaleTypeDrawable::class.java)
    val scaleTypeActual = actual as ScaleTypeDrawable
    assertThat(scaleTypeActual.scaleType).isEqualTo(ScalingUtils.ScaleType.FIT_CENTER)
    assertThat(scaleTypeActual.focusPoint).isEqualTo(expectedFocusPoint)
  }

  @Test
  fun testBuildOverlayRes_whenUnset_thenReturnNull() {
    val options = ImageOptions.create().build()
    val overlayDrawable = hierarcher.buildOverlayDrawable(resources, options)
    assertThat(overlayDrawable).isNull()
  }

  @Test
  fun testBuildOverlayRes_whenSet_thenReturnDrawable() {
    val options = ImageOptions.create().overlayRes(RES_ID).build()
    val overlayDrawable = hierarcher.buildOverlayDrawable(resources, options)
    assertThat(overlayDrawable).isEqualTo(drawable)
  }

  @Test(expected = Resources.NotFoundException::class)
  fun testBuildOverlayDrawable_whenInvalidResId_thenThrowNotFoundException() {
    val options = ImageOptions.create().overlayRes(INVALID_RES_ID).build()
    hierarcher.buildOverlayDrawable(resources, options)
  }

  @Test
  fun testApplyRoundingOptions_whenFilterRoundedBitmaps_thenFiltersRoundedBitmap() {
    val drawable = mock<BitmapDrawable>()
    whenever(drawable.bitmap).thenReturn(mock<Bitmap>())
    val rounded = ImageOptions.create().round(RoundingOptions.forCornerRadiusPx(123f))

    val filtered =
        hierarcher.applyRoundingOptions(
            resources,
            drawable,
            rounded.filterRoundedBitmaps(true).build(),
        )
    val unfiltered =
        hierarcher.applyRoundingOptions(
            resources,
            drawable,
            rounded.filterRoundedBitmaps(false).build(),
        )

    assertThat((filtered as RoundedBitmapDrawable).paintFilterBitmap).isTrue()
    assertThat((unfiltered as RoundedBitmapDrawable).paintFilterBitmap).isFalse()
  }

  companion object {
    private const val RES_ID = 123
    private const val INVALID_RES_ID = 999
  }
}
