/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.util.DisplayMetrics
import com.facebook.drawee.drawable.RoundedBitmapDrawable
import com.facebook.drawee.drawable.RoundedColorDrawable
import com.facebook.drawee.drawable.RoundedNinePatchDrawable
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.RoundingOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoundingUtilsTest {

  private lateinit var resources: Resources
  private lateinit var displayMetrics: DisplayMetrics

  @Before
  fun setup() {
    resources = mock()
    displayMetrics = DisplayMetrics()
    whenever(resources.displayMetrics).thenReturn(displayMetrics)
  }

  @Test
  fun testRoundedDrawablesWithoutBorder_withBitmap_withNotAlreadyRounded_thenReturnBitmapDrawable() {
    val roundingUtils = RoundingUtils
    val bitmap = mock<Bitmap>()
    val drawable =
        roundingUtils.roundedDrawable(resources, bitmap, null, RoundingOptions.asCircle())
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((drawable as RoundedBitmapDrawable).isCircle).isTrue
  }

  @Test
  fun testRoundedDrawablesWithBorder_withBitmap_withNotAlreadyRounded_thenReturnBitmapDrawable() {
    val roundingUtils = RoundingUtils
    val bitmap = mock<Bitmap>()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)
    val drawable =
        roundingUtils.roundedDrawable(resources, bitmap, borderOptions, RoundingOptions.asCircle())
    assertThat(drawable).isNotNull
    assertThat(drawable).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((drawable as RoundedBitmapDrawable).borderWidth).isEqualTo(borderOptions.width)
    assertThat(drawable.borderColor).isEqualTo(borderOptions.color)
    assertThat(drawable.isCircle).isTrue
  }

  @Test
  fun testRoundedDrawablesWithoutBorder_withDrawable_thenReturnBitmapDrawable() {
    val roundingUtils = RoundingUtils
    val roundingOptions = RoundingOptions.asCircle()
    var drawable: Drawable = mock<BitmapDrawable>()
    var result = roundingUtils.roundedDrawable(resources, drawable, null, roundingOptions)
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((result as RoundedBitmapDrawable).isCircle).isTrue

    drawable = mock<ColorDrawable>()
    result = roundingUtils.roundedDrawable(resources, drawable, null, roundingOptions)
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedColorDrawable::class.java)
    assertThat((result as RoundedColorDrawable).isCircle).isTrue

    drawable = mock<NinePatchDrawable>()
    result = roundingUtils.roundedDrawable(resources, drawable, null, roundingOptions)
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedNinePatchDrawable::class.java)
    assertThat((result as RoundedNinePatchDrawable).isCircle).isTrue
  }

  @Test
  fun testRoundedDrawablesWithBorder_withDrawable_thenReturnBitmapDrawable() {
    val roundingUtils = RoundingUtils
    var drawable: Drawable = mock<BitmapDrawable>()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)
    var result =
        roundingUtils.roundedDrawable(
            resources,
            drawable,
            borderOptions,
            RoundingOptions.asCircle(),
        )
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedBitmapDrawable::class.java)
    assertThat((result as RoundedBitmapDrawable).borderWidth).isEqualTo(borderOptions.width)
    assertThat(result.borderColor).isEqualTo(borderOptions.color)
    assertThat(result.isCircle).isTrue

    drawable = mock<ColorDrawable>()
    result =
        roundingUtils.roundedDrawable(
            resources,
            drawable,
            borderOptions,
            RoundingOptions.asCircle(),
        )
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedColorDrawable::class.java)
    assertThat((result as RoundedColorDrawable).borderWidth).isEqualTo(borderOptions.width)
    assertThat(result.borderColor).isEqualTo(borderOptions.color)
    assertThat(result.isCircle).isTrue

    drawable = mock<NinePatchDrawable>()
    result =
        roundingUtils.roundedDrawable(
            resources,
            drawable,
            borderOptions,
            RoundingOptions.asCircle(),
        )
    assertThat(result).isNotNull
    assertThat(result).isInstanceOf(RoundedNinePatchDrawable::class.java)
    assertThat((result as RoundedNinePatchDrawable).borderWidth).isEqualTo(borderOptions.width)
    assertThat(result.borderColor).isEqualTo(borderOptions.color)
    assertThat(result.isCircle).isTrue
  }

  @Test
  fun testRoundedDrawable_withBitmap_thenFiltersOnlyWhenAsked() {
    val bitmap = mock<Bitmap>()
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)
    val shapes = listOf<Pair<BorderOptions?, RoundingOptions?>>(
        null to RoundingOptions.asCircle(),
        null to RoundingOptions.forCornerRadiusPx(8f),
        borderOptions to RoundingOptions.forCornerRadii(1f, 2f, 3f, 4f),
        borderOptions to null,
    )

    shapes.forEach { (border, rounding) ->
      val filtered =
          RoundingUtils.roundedDrawable(resources, bitmap, border, rounding, filterBitmap = true)
      val unfiltered = RoundingUtils.roundedDrawable(resources, bitmap, border, rounding)
      assertThat((filtered as RoundedBitmapDrawable).paintFilterBitmap).isTrue
      assertThat((unfiltered as RoundedBitmapDrawable).paintFilterBitmap).isFalse
    }
  }

  @Test
  fun testRoundedDrawable_withDrawable_thenFiltersOnlyRoundedBitmaps() {
    val borderOptions = BorderOptions.create(Color.YELLOW, 10f)
    val roundingOptions = RoundingOptions.asCircle()

    val bitmap =
        RoundingUtils.roundedDrawable(
            resources,
            mock<BitmapDrawable>(),
            null,
            roundingOptions,
            filterBitmap = true,
        )
    val bitmapWithBorder =
        RoundingUtils.roundedDrawable(
            resources,
            mock<BitmapDrawable>(),
            borderOptions,
            roundingOptions,
            filterBitmap = true,
        )
    val color =
        RoundingUtils.roundedDrawable(
            resources,
            mock<ColorDrawable>(),
            null,
            roundingOptions,
            filterBitmap = true,
        )

    assertThat((bitmap as RoundedBitmapDrawable).paintFilterBitmap).isTrue
    assertThat((bitmapWithBorder as RoundedBitmapDrawable).paintFilterBitmap).isTrue
    assertThat((color as RoundedColorDrawable).paintFilterBitmap).isFalse
  }

  @Test
  fun testRoundedDrawable_whenDrawnWithFilterBitmap_thenPaintFiltersBitmap() {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
    val roundingOptions = RoundingOptions.forCornerRadiusPx(2f)
    val filtered =
        RoundingUtils.roundedDrawable(resources, bitmap, null, roundingOptions, filterBitmap = true)
            as RoundedBitmapDrawable
    val unfiltered =
        RoundingUtils.roundedDrawable(resources, bitmap, null, roundingOptions)
            as RoundedBitmapDrawable

    listOf(filtered, unfiltered).forEach { drawable ->
      drawable.setBounds(0, 0, 4, 4)
      drawable.draw(canvas)
    }

    assertThat(filtered.paint.isFilterBitmap).isTrue
    assertThat(unfiltered.paint.isFilterBitmap).isFalse
  }
}
