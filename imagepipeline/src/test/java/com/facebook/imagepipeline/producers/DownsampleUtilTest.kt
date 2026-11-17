/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import android.media.ExifInterface
import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.references.CloseableReference
import com.facebook.imageformat.DefaultImageFormats
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.common.RotationOptions.Companion.autoRotate
import com.facebook.imagepipeline.common.RotationOptions.Companion.disableRotation
import com.facebook.imagepipeline.common.RotationOptions.Companion.forceRotation
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.transcoder.DownsampleUtil
import com.facebook.imagepipeline.transcoder.DownsampleUtil.ratioToSampleSize
import com.facebook.imagepipeline.transcoder.DownsampleUtil.ratioToSampleSizeJPEG
import com.facebook.imagepipeline.transcoder.DownsampleUtil.roundToPowerOfTwo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito

@RunWith(JUnit4::class)
class DownsampleUtilTest {
  private lateinit var imageRequest: ImageRequest
  private lateinit var encodedImage: EncodedImage

  @Before
  fun setup() {
    imageRequest = Mockito.mock<ImageRequest>(ImageRequest::class.java)
  }

  @Test
  fun testDetermineSampleSize_NullResizeOptions() {
    whenImageWidthAndHeight(0, 0)
    // A default rotation is required for this field
    Mockito.`when`(imageRequest.getRotationOptions()).thenReturn(autoRotate())
    // Null resizeOptions
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)
  }

  @Test
  fun testDetermineSampleSize_NoEncodedImageDimensions() {
    whenImageWidthAndHeight(0, 0)
    whenRequestResizeWidthAndHeightWithExifRotation(1, 1)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)

    // Width or height of the encoded image are 0
    encodedImage.setWidth(100)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)
    encodedImage.setWidth(0)
    encodedImage.setHeight(100)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)
  }

  @Test
  fun testDetermineSampleSize_JPEG() {
    whenImageWidthAndHeight(100, 100)
    whenRequestResizeWidthAndHeightWithExifRotation(50, 50)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)

    whenRequestResizeWidthAndHeightWithExifRotation(50, 25)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)
  }

  @Test
  fun testDetermineSampleSize_PNG() {
    whenImageWidthAndHeight(150, 150)
    encodedImage.setImageFormat(DefaultImageFormats.PNG)
    whenRequestResizeWidthAndHeightWithExifRotation(50, 50)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(3)
  }

  @Test
  fun testDetermineSampleSize_WithRotation() {
    whenImageWidthHeightAndRotation(50, 100, 90)

    whenRequestResizeWidthAndHeightWithExifRotation(50, 25)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)

    whenRequestResizeWidthAndHeightWithExifRotation(25, 50)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)
  }

  @Test
  fun testDetermineSampleSize_WithRotationForcedByRequest() {
    whenImageWidthAndHeight(50, 100)

    // The rotation angles should be ignored as they're dealt with by the ResizeAndRotateProducer
    // 50,100 -> 50,25 = 1
    whenRequestResizeWidthHeightAndForcedRotation(50, 25, 90)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)

    // 50,100 -> 25,50 = 2
    whenRequestResizeWidthHeightAndForcedRotation(25, 50, 270)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)

    // 50,100 -> 10,20 = 5
    whenRequestResizeWidthHeightAndForcedRotation(10, 20, 180)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(5)
  }

  @Test
  fun testDetermineSampleSize_OverMaxPossibleSize() {
    whenImageWidthAndHeight(4000, 4000)

    whenRequestResizeWidthAndHeightWithExifRotation(4000, 4000)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)

    whenImageWidthAndHeight(8000, 8000)
    whenRequestResizeWidthAndHeightWithExifRotation(8000, 8000)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(4)
  }

  @Test
  fun testDetermineSampleSize_CustomMaxPossibleSize() {
    whenImageWidthAndHeight(4000, 4000)

    whenRequestResizeWidthHeightAndMaxBitmapSize(4000, 4000, 4096f)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(1)

    whenImageWidthAndHeight(8000, 8000)
    whenRequestResizeWidthHeightAndMaxBitmapSize(8000, 8000, 4096f)
    assertThat(
        DownsampleUtil.determineSampleSize(
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                encodedImage,
                MAX_BITMAP_SIZE,
            )
            .toLong(),
    )
        .isEqualTo(2)
  }

  @Test
  fun testRatioToSampleSize() {
    assertThat(ratioToSampleSize(1.000f).toLong()).isEqualTo(1)
    assertThat(ratioToSampleSize(0.667f).toLong()).isEqualTo(1)
    assertThat(ratioToSampleSize(0.665f).toLong()).isEqualTo(2)
    assertThat(ratioToSampleSize(0.389f).toLong()).isEqualTo(2)
    assertThat(ratioToSampleSize(0.387f).toLong()).isEqualTo(3)
    assertThat(ratioToSampleSize(0.278f).toLong()).isEqualTo(3)
    assertThat(ratioToSampleSize(0.276f).toLong()).isEqualTo(4)
    assertThat(ratioToSampleSize(0.2167f).toLong()).isEqualTo(4)
    assertThat(ratioToSampleSize(0.2165f).toLong()).isEqualTo(5)
    assertThat(ratioToSampleSize(0.1778f).toLong()).isEqualTo(5)
    assertThat(ratioToSampleSize(0.1776f).toLong()).isEqualTo(6)
    assertThat(ratioToSampleSize(0.1508f).toLong()).isEqualTo(6)
    assertThat(ratioToSampleSize(0.1506f).toLong()).isEqualTo(7)
    assertThat(ratioToSampleSize(0.131f).toLong()).isEqualTo(7)
    assertThat(ratioToSampleSize(0.1308f).toLong()).isEqualTo(8)
  }

  @Test
  fun testRatioToSampleSizeJPEG() {
    assertThat(ratioToSampleSizeJPEG(1.000f).toLong()).isEqualTo(1)
    assertThat(ratioToSampleSizeJPEG(0.667f).toLong()).isEqualTo(1)
    assertThat(ratioToSampleSizeJPEG(0.665f).toLong()).isEqualTo(2)
    assertThat(ratioToSampleSizeJPEG(0.334f).toLong()).isEqualTo(2)
    assertThat(ratioToSampleSizeJPEG(0.332f).toLong()).isEqualTo(4)
    assertThat(ratioToSampleSizeJPEG(0.1667f).toLong()).isEqualTo(4)
    assertThat(ratioToSampleSizeJPEG(0.1665f).toLong()).isEqualTo(8)
    assertThat(ratioToSampleSizeJPEG(0.0834f).toLong()).isEqualTo(8)
    assertThat(ratioToSampleSizeJPEG(0.0832f).toLong()).isEqualTo(16)
  }

  @Test
  fun testRoundToPowerOfTwo() {
    assertThat(roundToPowerOfTwo(1).toLong()).isEqualTo(1)
    assertThat(roundToPowerOfTwo(2).toLong()).isEqualTo(2)
    assertThat(roundToPowerOfTwo(3).toLong()).isEqualTo(4)
    assertThat(roundToPowerOfTwo(4).toLong()).isEqualTo(4)
    assertThat(roundToPowerOfTwo(5).toLong()).isEqualTo(8)
    assertThat(roundToPowerOfTwo(6).toLong()).isEqualTo(8)
    assertThat(roundToPowerOfTwo(7).toLong()).isEqualTo(8)
    assertThat(roundToPowerOfTwo(8).toLong()).isEqualTo(8)
  }

  private fun whenImageWidthAndHeight(width: Int, height: Int) {
    whenImageWidthHeightAndRotation(width, height, 0)
  }

  private fun whenImageWidthHeightAndRotation(width: Int, height: Int, rotationAngle: Int) {
    encodedImage =
        EncodedImage(
            CloseableReference.of<PooledByteBuffer?>(
                Mockito.mock<PooledByteBuffer>(PooledByteBuffer::class.java),
            ),
        )
    encodedImage.setWidth(width)
    encodedImage.setHeight(height)
    encodedImage.setRotationAngle(rotationAngle)
    encodedImage.setExifOrientation(ExifInterface.ORIENTATION_NORMAL)
  }

  private fun whenRequestResizeWidthAndHeightWithExifRotation(width: Int, height: Int) {
    whenRequestResizeWidthHeightAndForcedRotation(width, height, -1)
  }

  private fun whenRequestResizeWidthHeightAndForcedRotation(
      width: Int,
      height: Int,
      rotationAngle: Int,
  ) {
    Mockito.`when`(imageRequest.getPreferredWidth()).thenReturn(width)
    Mockito.`when`(imageRequest.getPreferredHeight()).thenReturn(height)
    Mockito.`when`(imageRequest.getResizeOptions()).thenReturn(ResizeOptions(width, height))
    Mockito.`when`(imageRequest.getRotationOptions()).thenReturn(forceRotation(rotationAngle))
  }

  private fun whenRequestResizeWidthHeightAndMaxBitmapSize(
      width: Int,
      height: Int,
      maxBitmapSize: Float,
  ) {
    Mockito.`when`(imageRequest.getPreferredWidth()).thenReturn(width)
    Mockito.`when`(imageRequest.getPreferredHeight()).thenReturn(height)
    Mockito.`when`(imageRequest.getResizeOptions())
        .thenReturn(ResizeOptions(width, height, maxBitmapSize))
    Mockito.`when`(imageRequest.getRotationOptions()).thenReturn(disableRotation())
  }

  companion object {
    private const val MAX_BITMAP_SIZE = 2024
  }
}
