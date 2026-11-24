/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.tools.liveeditor

import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.fresco.vito.options.BitmapConfig
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.RoundingOptions
import kotlin.Pair

object ImageOptionsSampleValues {

  data class Entry<T>(
      val name: String,
      val data: List<Pair<String, T>>,
      val updateFunction: (ImageOptions.Builder, T) -> @JvmSuppressWildcards ImageOptions.Builder,
  )

  val roundingOptions: ImageOptionsSampleValues.Entry<RoundingOptions?> = Entry(
      "Rounding",
      listOf(
          "none" to null,
          "as circle" to RoundingOptions.asCircle(),
          "corner radius" to RoundingOptions.forCornerRadiusPx(20f),
          "different radii" to RoundingOptions.forCornerRadii(0f, 20f, 30f, 40f),
      ),
  ) { b, v ->
    b.round(v)
  }

  val borderOptions: ImageOptionsSampleValues.Entry<BorderOptions?> = Entry(
      "Border",
      listOf(
          "none" to null,
          "red border" to BorderOptions.create(Color.RED, 20f),
          "blue border" to BorderOptions.create(Color.BLUE, 40f),
          "border with no padding" to BorderOptions.create(Color.GREEN, 20f),
          "border with small padding" to BorderOptions.create(Color.GREEN, 20f, 10f),
          "border with same padding" to BorderOptions.create(Color.GREEN, 20f, 20f),
          "border with more padding" to BorderOptions.create(Color.GREEN, 20f, 40f),
      ),
  ) { b, v ->
    b.borders(v)
  }

  val scaleTypes: ImageOptionsSampleValues.Entry<Pair<ScalingUtils.ScaleType?, PointF?>> = Entry(
      "Scale type",
      listOf(
          "center" to Pair(ScalingUtils.ScaleType.CENTER, null),
          "center_crop" to Pair(ScalingUtils.ScaleType.CENTER_CROP, null),
          "center_inside" to Pair(ScalingUtils.ScaleType.CENTER_INSIDE, null),
          "fit_center" to Pair(ScalingUtils.ScaleType.FIT_CENTER, null),
          "fit_start" to Pair(ScalingUtils.ScaleType.FIT_START, null),
          "fit_end" to Pair(ScalingUtils.ScaleType.FIT_END, null),
          "fit_xy" to Pair(ScalingUtils.ScaleType.FIT_XY, null),
          "focus_crop (0, 0)" to Pair(ScalingUtils.ScaleType.FOCUS_CROP, PointF(0f, 0f)),
          "focus_crop (1, 0.5)" to Pair(ScalingUtils.ScaleType.FOCUS_CROP, PointF(1f, 0.5f)),
          "null" to Pair(null, null),
      ),
  ) { b, v ->
    b.scale(v.first).focusPoint(v.second)
  }

  val colorFilters: ImageOptionsSampleValues.Entry<PorterDuffColorFilter> = Entry(
      "Color filter",
      listOf(
          "none" to PorterDuffColorFilter(Color.TRANSPARENT, PorterDuff.Mode.ADD),
          "red" to PorterDuffColorFilter(Color.RED, PorterDuff.Mode.DARKEN),
          "green" to PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.DARKEN),
          "blue" to PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.DARKEN),
          "tint: black" to PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP),
          "tint: white" to PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP),
          "tint: gray" to PorterDuffColorFilter(Color.GRAY, PorterDuff.Mode.SRC_ATOP),
          "tint: red" to PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_ATOP),
          "tint: green" to PorterDuffColorFilter(Color.GREEN, PorterDuff.Mode.SRC_ATOP),
          "tint: blue" to PorterDuffColorFilter(Color.BLUE, PorterDuff.Mode.SRC_ATOP),
      ),
  ) { b, v ->
    b.colorFilter(v)
  }

  val fadingOptions: ImageOptionsSampleValues.Entry<Int> = Entry(
      "Fading",
      listOf(
          "none" to 0,
          "50 ms" to 50,
          "100 ms" to 100,
          "200 ms" to 200,
          "500 ms" to 500,
          "1000 ms" to 1000,
          "2000 ms" to 2000,
          "5000 ms" to 5000,
          "10000 ms" to 10000,
      ),
  ) { b, v ->
    b.fadeDurationMs(v)
  }

  val autoPlay: ImageOptionsSampleValues.Entry<Boolean> =
      Entry("Autoplay", listOf("off" to false, "on" to true)) { b, v -> b.autoPlay(v) }

  val bitmapConfig: ImageOptionsSampleValues.Entry<BitmapConfig> = Entry(
      "Bitmap config",
      BitmapConfig.values().map { it.name to it },
  ) { b, v ->
    b.bitmapConfig(v)
  }

  val resizeToViewportConfig: ImageOptionsSampleValues.Entry<Boolean> =
      Entry("Resize to viewport", listOf("off" to false, "on" to true)) { b, v ->
        b.resizeToViewport(v)
      }

  val localThumbnailPreviewsEnabledConfig: ImageOptionsSampleValues.Entry<Boolean> =
      Entry("Local thumbnail preview", listOf("off" to false, "on" to true)) { b, v ->
        b.localThumbnailPreviewsEnabled(v)
      }
  val progressiveRenderingEnabledConfig: ImageOptionsSampleValues.Entry<Boolean> =
      Entry("Progressive rendering", listOf("off" to false, "on" to true)) { b, v ->
        b.progressiveRendering(v)
      }

  val placeholderColors: ImageOptionsSampleValues.Entry<Int> = Entry(
      "Placeholder color",
      listOf(
          "Black" to Color.BLACK,
          "Dark Gray" to Color.DKGRAY,
          "Gray" to Color.GRAY,
          "Light Gray" to Color.LTGRAY,
          "White" to Color.WHITE,
          "Red" to Color.RED,
          "Green" to Color.GREEN,
          "Blue" to Color.BLUE,
          "Yellow" to Color.YELLOW,
          "Cyan" to Color.CYAN,
          "Magenta" to Color.MAGENTA,
          "Transparent" to Color.TRANSPARENT,
      ),
  ) { builder, colorInt ->
    builder.placeholderColor(colorInt)
  }

  fun <T> nameForValue(data: List<Pair<String, T?>>, value: T?): String {
    return data.find { value == it.second }?.first ?: "unknown"
  }
}
