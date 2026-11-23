/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.textspan

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Looper
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.widget.text.span.BetterImageSpan

/**
 * A [VitoSpan] whose image request does not start when [VitoSpanLoader.showWhenAttachedOrDrawn]
 * sets it up. The text that holds the span starts it through [imageFetchCommand] when the text is
 * attached, or [startFetchIfPending] starts it. When neither has happened by the span's first draw
 * on the main thread, that draw starts it.
 */
class DeferredFetchVitoSpan(
    resources: Resources,
    drawableInterface: FrescoDrawableInterface,
    @BetterImageSpan.BetterImageSpanAlignment verticalAlignment: Int,
    imageSpan: BetterImageSpan,
    customCallback: Drawable.Callback?,
) : VitoSpan(resources, drawableInterface, verticalAlignment, imageSpan, customCallback) {

  /** True from [VitoSpanLoader.showWhenAttachedOrDrawn] until the request first starts. */
  @Volatile internal var isFetchPending: Boolean = false

  /** Starts the request now, unless it has already started. */
  fun startFetchIfPending() {
    if (isFetchPending) {
      imageFetchCommand?.invoke()
    }
  }

  override fun draw(
      canvas: Canvas,
      text: CharSequence?,
      start: Int,
      end: Int,
      x: Float,
      top: Int,
      y: Int,
      bottom: Int,
      paint: Paint,
  ) {
    // Litho also draws text off the main thread to warm its glyphs; such a draw starts nothing.
    if (isFetchPending && Looper.myLooper() == Looper.getMainLooper()) {
      imageFetchCommand?.invoke()
    }
    super.draw(canvas, text, start, end, x, top, y, bottom, paint)
  }
}
