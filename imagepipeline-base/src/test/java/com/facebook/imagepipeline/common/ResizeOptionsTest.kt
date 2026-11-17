/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.common

import com.facebook.imagepipeline.common.ResizeOptions.Companion.forDimensions
import com.facebook.imagepipeline.common.ResizeOptions.Companion.forSquareSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResizeOptionsTest {
  @Test
  fun testStaticConstructorWithValidWidthAndHeight() {
    val resizeOptions = forDimensions(1, 2)

    assertThat(resizeOptions).isNotNull()
    assertThat(resizeOptions?.width).isEqualTo(1)
    assertThat(resizeOptions?.height).isEqualTo(2)
  }

  @Test
  fun testStaticConstructorWithInvalidWidth() {
    val resizeOptions = forDimensions(0, 2)

    assertThat(resizeOptions).isNull()
  }

  @Test
  fun testStaticConstructorWithInvalidHeight() {
    val resizeOptions = forDimensions(1, 0)

    assertThat(resizeOptions).isNull()
  }

  @Test
  fun testStaticConstructorWithValidSquareSize() {
    val resizeOptions = forSquareSize(1)

    assertThat(resizeOptions).isNotNull()
    assertThat(resizeOptions?.width).isEqualTo(1)
    assertThat(resizeOptions?.height).isEqualTo(1)
  }

  @Test
  fun testStaticConstructorWithInvalidSquareSize() {
    val resizeOptions = forSquareSize(0)

    assertThat(resizeOptions).isNull()
  }

  @Test
  fun testKotlinDefaultConstructorPreservesBinaryCompatibility() {
    val constructor =
        ResizeOptions::class
            .java
            .getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
            )
    val defaults = constructor.newInstance(1, 2, 0f, 0f, 12, null)
    val expected = ResizeOptions(1, 2)

    assertThat(defaults.maxBitmapDimension).isEqualTo(expected.maxBitmapDimension)
    assertThat(defaults.roundUpFraction).isEqualTo(expected.roundUpFraction)
    assertThat(defaults.transcoder).isEqualTo(ResizeOptions.Transcoder.DEFAULT)

    val explicit = constructor.newInstance(1, 2, 100f, 0.5f, 0, null)

    assertThat(explicit.maxBitmapDimension).isEqualTo(100f)
    assertThat(explicit.roundUpFraction).isEqualTo(0.5f)
    assertThat(explicit.transcoder).isEqualTo(ResizeOptions.Transcoder.DEFAULT)
  }

  @Test
  fun testTranscoderParticipatesInEquality() {
    val defaultTranscoder = ResizeOptions(1, 2)
    val noTranscoder = ResizeOptions(1, 2, transcoder = ResizeOptions.Transcoder.NONE)

    assertThat(noTranscoder).isNotEqualTo(defaultTranscoder)
    assertThat(noTranscoder.hashCode()).isNotEqualTo(defaultTranscoder.hashCode())
    assertThat(noTranscoder)
        .isEqualTo(ResizeOptions(1, 2, transcoder = ResizeOptions.Transcoder.NONE))
  }
}
