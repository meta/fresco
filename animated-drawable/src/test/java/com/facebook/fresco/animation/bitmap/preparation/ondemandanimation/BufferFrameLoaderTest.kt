/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.animation.bitmap.preparation.ondemandanimation

import android.graphics.Bitmap
import com.facebook.common.references.CloseableReference
import com.facebook.common.references.ResourceReleaser
import com.facebook.fresco.animation.backend.AnimationInformation
import com.facebook.fresco.animation.bitmap.BitmapFrameRenderer
import com.facebook.fresco.animation.bitmap.preparation.loadframe.FpsCompressorInfo
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferFrameLoaderTest {

  private val bitmap: Bitmap = mock()
  private val bitmapReleaser: ResourceReleaser<Bitmap> = mock()
  private val platformBitmapFactory: PlatformBitmapFactory = mock()
  private val bitmapFrameRenderer: BitmapFrameRenderer = mock {
    on { renderFrame(any(), any()) } doReturn true
  }

  @Test
  fun getFrame_singleFrameRenderingEnabled_rendersAndCachesFrame() {
    val firstBitmapReference = CloseableReference.of(bitmap, bitmapReleaser)
    val secondBitmapReference = CloseableReference.of(bitmap, bitmapReleaser)
    whenever(platformBitmapFactory.createBitmap(WIDTH, HEIGHT))
        .thenReturn(firstBitmapReference, secondBitmapReference)
    val frameLoader = createFrameLoader(frameCount = 1, enableSingleFrameRendering = true)

    val firstResult = frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)
    val cachedResult = frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)

    assertThat(firstResult.type).isEqualTo(FrameResult.FrameType.SUCCESS)
    assertThat(firstResult.bitmapRef).isNotNull
    assertThat(cachedResult.type).isEqualTo(FrameResult.FrameType.SUCCESS)
    assertThat(cachedResult.bitmapRef).isNotNull
    verify(platformBitmapFactory).createBitmap(WIDTH, HEIGHT)
    verify(bitmapFrameRenderer).renderFrame(0, bitmap)

    CloseableReference.closeSafely(firstResult.bitmapRef)
    CloseableReference.closeSafely(cachedResult.bitmapRef)
    frameLoader.clear()

    val reloadedResult = frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)

    assertThat(reloadedResult.type).isEqualTo(FrameResult.FrameType.SUCCESS)
    verify(platformBitmapFactory, times(2)).createBitmap(WIDTH, HEIGHT)
    verify(bitmapFrameRenderer, times(2)).renderFrame(0, bitmap)
    CloseableReference.closeSafely(reloadedResult.bitmapRef)
    frameLoader.clear()
  }

  @Test
  fun getFrame_singleFrameRendererThrows_releasesCreatedBitmapOnceAndRethrows() {
    val failedBitmapReference = CloseableReference.of(bitmap, bitmapReleaser)
    val retriedBitmapReference = CloseableReference.of(bitmap, bitmapReleaser)
    whenever(platformBitmapFactory.createBitmap(WIDTH, HEIGHT))
        .thenReturn(failedBitmapReference, retriedBitmapReference)
    val renderFailure = IllegalStateException("render failed")
    whenever(bitmapFrameRenderer.renderFrame(any(), any()))
        .thenThrow(renderFailure)
        .thenReturn(true)
    val frameLoader = createFrameLoader(frameCount = 1, enableSingleFrameRendering = true)

    val thrown =
        assertThrows(IllegalStateException::class.java) {
          frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)
        }

    assertThat(thrown).isSameAs(renderFailure)
    verify(bitmapReleaser, times(1)).release(bitmap)
    assertThat(failedBitmapReference.isValid).isFalse

    val retryResult = frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)

    assertThat(retryResult.type).isEqualTo(FrameResult.FrameType.SUCCESS)
    verify(platformBitmapFactory, times(2)).createBitmap(WIDTH, HEIGHT)
    verify(bitmapReleaser, times(1)).release(bitmap)
    CloseableReference.closeSafely(retryResult.bitmapRef)
    frameLoader.clear()
    verify(bitmapReleaser, times(2)).release(bitmap)
  }

  @Test
  fun getFrame_multiFrameAnimation_doesNotUseSingleFramePath() {
    val frameLoader = createFrameLoader(frameCount = 2, enableSingleFrameRendering = true)

    val result = frameLoader.getFrame(frameNumber = 0, width = WIDTH, height = HEIGHT)

    assertThat(result.type).isEqualTo(FrameResult.FrameType.MISSING)
    assertThat(result.bitmapRef).isNull()
    verify(platformBitmapFactory, times(0)).createBitmap(any<Int>(), any<Int>())
  }

  private fun createFrameLoader(
      frameCount: Int,
      enableSingleFrameRendering: Boolean,
  ): BufferFrameLoader {
    val animationInformation: AnimationInformation = mock {
      on { this.frameCount } doReturn frameCount
      on { loopDurationMs } doReturn 1_000
      on { loopCount } doReturn 1
    }
    return BufferFrameLoader(
        platformBitmapFactory = platformBitmapFactory,
        bitmapFrameRenderer = bitmapFrameRenderer,
        fpsCompressor = FpsCompressorInfo(maxFpsLimit = 30),
        animationInformation = animationInformation,
        bufferLengthMilliseconds = 1_000,
        enableBufferFrameLoaderFix = true,
        enableSingleFrameRendering = enableSingleFrameRendering,
    )
  }

  companion object {
    private const val WIDTH = 100
    private const val HEIGHT = 80
  }
}
