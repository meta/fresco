/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.animated.impl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo.BlendOperation
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo.DisposalMethod
import com.facebook.imagepipeline.animated.base.AnimatedImage
import com.facebook.imagepipeline.animated.base.AnimatedImageFrame
import com.facebook.imagepipeline.animated.base.AnimatedImageResult
import com.facebook.imagepipeline.animated.util.AnimatedDrawableUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedDrawableBackendDisposalTest {
  @Test
  fun downscaledGifDisposesEveryPaintedPixel() {
    for (size in listOf(55, 121, 200)) {
      assertDisposed(size, supportsScaling = false)
    }
  }

  @Test
  fun scalingDecoderStillDisposesEveryPaintedPixel() {
    for (size in listOf(55, 121, 200)) {
      assertDisposed(size, supportsScaling = true)
    }
  }

  private fun assertDisposed(size: Int, supportsScaling: Boolean) {
    val firstInfo = AnimatedDrawableFrameInfo(
        0,
        15,
        54,
        160,
        146,
        BlendOperation.BLEND_WITH_PREVIOUS,
        DisposalMethod.DISPOSE_TO_BACKGROUND,
    )
    val secondInfo = AnimatedDrawableFrameInfo(
        1,
        0,
        0,
        200,
        200,
        BlendOperation.BLEND_WITH_PREVIOUS,
        DisposalMethod.DISPOSE_DO_NOT,
    )
    val infos = listOf(firstInfo, secondInfo)
    val image = mock<AnimatedImage>()
    whenever(image.width).thenReturn(200)
    whenever(image.height).thenReturn(200)
    whenever(image.frameCount).thenReturn(2)
    whenever(image.frameDurations).thenReturn(intArrayOf(50, 50))
    whenever(image.doesRenderSupportScaling()).thenReturn(supportsScaling)
    for ((index, info) in infos.withIndex()) {
      whenever(image.getFrameInfo(index)).thenReturn(info)
      whenever(image.getFrame(index))
          .thenReturn(
              object : AnimatedImageFrame {
                override fun getWidth() = info.width

                override fun getHeight() = info.height

                override fun getXOffset() = info.xOffset

                override fun getYOffset() = info.yOffset

                override fun getDurationMs() = 50

                override fun dispose() = Unit

                override fun renderFrame(width: Int, height: Int, bitmap: Bitmap) {
                  if (index == 0) {
                    val paint = android.graphics.Paint().apply { color = Color.RED }
                    Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                  }
                }
              },
          )
    }
    val backend = AnimatedDrawableBackendImpl(
        AnimatedDrawableUtil(),
        AnimatedImageResult.forAnimatedImage(image),
        Rect(0, 0, 200, 200),
        true,
    )
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      backend.renderDeltas(0, canvas)
      val pixels = IntArray(size * size)
      bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
      assertThat(pixels.count { it != Color.TRANSPARENT }).isPositive()
      backend.renderDeltas(1, canvas)
      bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
      assertThat(pixels.count { it != Color.TRANSPARENT })
          .describedAs("remaining pixels at %s px, scaling decoder=%s", size, supportsScaling)
          .isZero()
    } finally {
      backend.dropCaches()
      bitmap.recycle()
    }
  }
}
