/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.facebook.common.logging.FLog
import com.facebook.common.logging.FLogDefaultLoggingDelegate
import com.facebook.common.logging.LoggingDelegate
import com.facebook.fresco.vito.core.impl.ImagePipelineUtilsImpl.CircularBitmapRounding
import com.facebook.fresco.vito.options.BitmapConfig
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.options.ImageOptionsDrawableFactory
import com.facebook.fresco.vito.options.RoundingOptions
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder
import com.facebook.imagepipeline.request.Postprocessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
@TargetApi(Build.VERSION_CODES.O)
class DefaultImageDecodeOptionsProviderImplTest {

  private val decodeTimeRoundingOptions = mock<ImageDecodeOptions>()
  private val provider =
      DefaultImageDecodeOptionsProviderImpl(CircularBitmapRounding { decodeTimeRoundingOptions })
  private val loggingDelegate = mock<LoggingDelegate> { on { isLoggable(any()) } doReturn true }
  private val uri: Uri = Uri.parse("https://example.com/image.png")
  private val originalDefaults = ImageOptions.defaults()

  @Before
  fun setup() {
    FLog.setLoggingDelegate(loggingDelegate)
  }

  @After
  fun tearDown() {
    ImageOptions.setDefaults(originalDefaults)
    FLog.setLoggingDelegate(FLogDefaultLoggingDelegate.getInstance())
  }

  @Test
  fun testCreate_whenHardwareWithCornerRadiusByDefault_thenLogsWtfAndDecodesSoftware() {
    assertThat(create(hardwareOptions().round(CORNER_RADIUS))).isNull()
    verifyWtf()
  }

  @Test
  fun testCreate_whenHardwareWithCircleByDefault_thenLogsWtfAndDecodesSoftware() {
    assertThat(create(hardwareOptions().round(CIRCLE))).isNull()
    verifyWtf()
  }

  @Test
  fun testCreate_whenHardwareWithCornerRadiusAndRenderTimeRounding_thenKeepsHardware() {
    val decodeOptions = create(renderTimeRoundingOptions().round(CORNER_RADIUS))

    assertThat(decodeOptions?.bitmapConfig).isEqualTo(Bitmap.Config.HARDWARE)
    assertThat(decodeOptions?.bitmapTransformation).isNull()
    verifyNoWtf()
  }

  @Test
  fun testCreate_whenHardwareWithCircleAndRenderTimeRounding_thenKeepsHardwareAndSkipsDecodeRounding() {
    val decodeOptions = create(renderTimeRoundingOptions().round(CIRCLE))

    assertThat(decodeOptions?.bitmapConfig).isEqualTo(Bitmap.Config.HARDWARE)
    assertThat(decodeOptions).isNotSameAs(decodeTimeRoundingOptions)
    assertThat(decodeOptions?.bitmapTransformation).isNull()
    verifyNoWtf()
  }

  @Test
  fun testCreate_whenHardwareWithRenderTimeRounding_thenMatchesUnroundedRequest() {
    val unrounded = create(hardwareOptions())

    assertThat(create(renderTimeRoundingOptions().round(CORNER_RADIUS))).isEqualTo(unrounded)
    assertThat(create(renderTimeRoundingOptions().round(CIRCLE))).isEqualTo(unrounded)
  }

  @Test
  fun testCreate_whenHardwareWithRoundingAndPostprocessor_thenLogsWtfAndDecodesSoftware() {
    val options =
        renderTimeRoundingOptions().round(CORNER_RADIUS).postprocess(mock<Postprocessor>())

    assertThat(create(options)).isNull()
    verifyWtf()
  }

  @Test
  fun testCreate_whenSoftwareConfigWithCircleAndRenderTimeRounding_thenKeepsDecodeRounding() {
    val options =
        ImageOptions.create()
            .bitmapConfig(BitmapConfig.ARGB_8888)
            .round(CIRCLE)
            .roundHardwareBitmapsAtRenderTime(true)

    assertThat(create(options)).isSameAs(decodeTimeRoundingOptions)
  }

  @Test
  fun testCreate_whenDefaultsRoundAtRenderTime_thenRequestCanOptOut() {
    ImageOptions.setDefaults(
        ImageOptions.defaults()
            .extend()
            .bitmapConfig(BitmapConfig.HARDWARE)
            .roundHardwareBitmapsAtRenderTime(true)
            .build(),
    )

    val fromDefaults = create(ImageOptions.create().round(CORNER_RADIUS))
    verifyNoWtf()
    val optedOut =
        create(ImageOptions.create().round(CORNER_RADIUS).roundHardwareBitmapsAtRenderTime(false))

    assertThat(fromDefaults?.bitmapConfig).isEqualTo(Bitmap.Config.HARDWARE)
    assertThat(optedOut).isNull()
    verifyWtf()
  }

  @Test
  fun testCreate_whenRenderTimeRoundingCornerWithCustomDrawableFactory_thenLogsWtfAndDecodesSoftware() {
    val options =
        renderTimeRoundingOptions()
            .round(CORNER_RADIUS)
            .customDrawableFactory(mock<ImageOptionsDrawableFactory>())

    assertThat(create(options)).isNull()
    verifyWtf()
  }

  @Test
  fun testCreate_whenRenderTimeRoundingCircleWithCustomDrawableFactory_thenLogsWtfAndDecodesSoftware() {
    val options =
        renderTimeRoundingOptions()
            .round(CIRCLE)
            .customDrawableFactory(mock<ImageOptionsDrawableFactory>())

    assertThat(create(options)).isNull()
    verifyWtf()
  }

  @Test
  fun testBuildImageRequest_whenRenderTimeRoundingWithCustomDrawableFactory_thenDecodesSoftware() {
    val imageRequest =
        ImagePipelineUtilsImpl(provider)
            .buildImageRequest(
                uri,
                renderTimeRoundingOptions()
                    .round(CORNER_RADIUS)
                    .customDrawableFactory(mock<ImageOptionsDrawableFactory>())
                    .build(),
            )

    assertThat(imageRequest?.imageDecodeOptions?.bitmapConfig).isEqualTo(Bitmap.Config.ARGB_8888)
  }

  @Test
  fun testCreate_whenDefaultsRoundAtRenderTimeWithCustomDrawableFactory_thenLogsWtf() {
    ImageOptions.setDefaults(
        ImageOptions.defaults()
            .extend()
            .bitmapConfig(BitmapConfig.HARDWARE)
            .roundHardwareBitmapsAtRenderTime(true)
            .build(),
    )

    val options =
        ImageOptions.create()
            .round(CORNER_RADIUS)
            .customDrawableFactory(mock<ImageOptionsDrawableFactory>())

    assertThat(create(options)).isNull()
    verifyWtf()
  }

  @Test
  fun testBuildImageRequest_whenHardwareWithRenderTimeRounding_thenRequestDecodesHardware() {
    val imageRequest =
        ImagePipelineUtilsImpl(provider)
            .buildImageRequest(uri, renderTimeRoundingOptions().round(CORNER_RADIUS).build())

    assertThat(imageRequest?.imageDecodeOptions?.bitmapConfig).isEqualTo(Bitmap.Config.HARDWARE)
  }

  private fun create(options: ImageOptions.Builder): ImageDecodeOptions? =
      provider.create(ImageRequestBuilder.newBuilderWithSource(uri), options.build())

  private fun hardwareOptions(): ImageOptions.Builder =
      ImageOptions.create().bitmapConfig(BitmapConfig.HARDWARE)

  private fun renderTimeRoundingOptions(): ImageOptions.Builder =
      hardwareOptions().roundHardwareBitmapsAtRenderTime(true)

  private fun verifyWtf() {
    verify(loggingDelegate).wtf(TAG, "Trying to use bitmap config incompatible with rounding.")
  }

  private fun verifyNoWtf() {
    verify(loggingDelegate, never()).wtf(any(), any())
  }

  private companion object {
    const val TAG = "DefaultImageOptionsProvider"
    val CORNER_RADIUS: RoundingOptions = RoundingOptions.forCornerRadiusPx(8f)
    val CIRCLE: RoundingOptions = RoundingOptions.asCircle()
  }
}
