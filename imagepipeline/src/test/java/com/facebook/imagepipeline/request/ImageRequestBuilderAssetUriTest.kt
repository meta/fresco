/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request

import android.net.Uri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageRequestBuilderAssetUriTest {

  @Test
  fun buildRejectsOpaqueAssetUriWithoutPath() {
    assertThatThrownBy {
          ImageRequestBuilder.newBuilderWithSource(Uri.parse("asset:logo.png")).build()
        }
        .isInstanceOf(ImageRequestBuilder.BuilderException::class.java)
        .hasMessage("Invalid request builder: Asset URI path must be absolute.")
  }

  @Test
  fun buildRejectsAssetUriWithEmptyPath() {
    assertThatThrownBy {
          ImageRequestBuilder.newBuilderWithSource(Uri.parse("asset://assets.example")).build()
        }
        .isInstanceOf(ImageRequestBuilder.BuilderException::class.java)
        .hasMessage("Invalid request builder: Asset URI path must be absolute.")
  }

  @Test
  fun buildAcceptsAssetUriWithAbsolutePath() {
    val uri = Uri.parse("asset:/logo.png")

    val imageRequest = ImageRequestBuilder.newBuilderWithSource(uri).build()

    assertThat(imageRequest.sourceUri).isEqualTo(uri)
  }
}
