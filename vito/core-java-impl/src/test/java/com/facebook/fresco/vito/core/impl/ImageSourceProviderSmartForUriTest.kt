/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.net.Uri
import com.facebook.drawee.drawable.SizingHint
import com.facebook.fresco.vito.source.DrawableResImageSource
import com.facebook.fresco.vito.source.EmptyImageSource
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.fresco.vito.source.SmartFetchOptIn
import com.facebook.fresco.vito.source.SmartImageSource
import com.facebook.imagepipeline.request.ImageRequestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageSourceProviderSmartForUriTest {

  private lateinit var originalUriParser: (String) -> Uri?
  private var originalTreatEmptyUriAsImageSource = false
  private var originalShortcutResUris = false

  @Before
  fun setup() {
    originalUriParser = ImageSourceProvider.uriParser
    originalTreatEmptyUriAsImageSource = ImageSourceProvider.treatEmptyUriAsImageSource
    originalShortcutResUris = ImageSourceProvider.shortcutResUris
  }

  @After
  fun tearDown() {
    ImageSourceProvider.uriParser = originalUriParser
    ImageSourceProvider.treatEmptyUriAsImageSource = originalTreatEmptyUriAsImageSource
    ImageSourceProvider.shortcutResUris = originalShortcutResUris
  }

  @Test
  fun testSmartForUri_whenUri_thenSmartImageSource() {
    assertThat(ImageSourceProvider.smartForUri(IMAGE_URL, SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isEqualTo(
            SmartImageSource(
                Uri.parse(IMAGE_URL),
                SizingHint.DEFAULT,
                null,
                SmartFetchOptIn.ENABLED_NO_FALLBACK,
            ),
        )
  }

  @Test
  fun testSmartForUri_whenSizingHintAndExtras_thenSmartImageSourceKeepsThem() {
    val source =
        ImageSourceProvider.smartForUri(
            Uri.parse(IMAGE_URL),
            SmartFetchOptIn.ENABLED_WITH_FALLBACK,
            SizingHint.LOW_QUALITY_FAST_LOADING,
            EXTRAS,
        )

    assertThat(source)
        .isEqualTo(
            SmartImageSource(
                Uri.parse(IMAGE_URL),
                SizingHint.LOW_QUALITY_FAST_LOADING,
                EXTRAS,
                SmartFetchOptIn.ENABLED_WITH_FALLBACK,
            ),
        )
  }

  @Test
  fun testSmartForUri_whenNullUri_thenSameEmptySourceAsForUri() {
    val stringSource =
        ImageSourceProvider.smartForUri(null as String?, SmartFetchOptIn.ENABLED_NO_FALLBACK)
    val uriSource =
        ImageSourceProvider.smartForUri(null as Uri?, SmartFetchOptIn.ENABLED_NO_FALLBACK)

    assertThat(stringSource).isSameAs(ImageSourceProvider.forUri(null as String?))
    assertThat(uriSource).isSameAs(ImageSourceProvider.forUri(null as Uri?))
  }

  @Test
  fun testSmartForUri_whenEmptyUriAndEmptyUriIsEmptySource_thenSameEmptySourceAsForUri() {
    ImageSourceProvider.treatEmptyUriAsImageSource = true

    assertThat(ImageSourceProvider.smartForUri(Uri.EMPTY, SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isInstanceOf(EmptyImageSource::class.java)
        .isSameAs(ImageSourceProvider.forUri(Uri.EMPTY))
    assertThat(ImageSourceProvider.smartForUri("", SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isInstanceOf(EmptyImageSource::class.java)
        .isSameAs(ImageSourceProvider.forUri(""))
  }

  @Test
  fun testSmartForUri_whenEmptyUriAndEmptyUriIsNotEmptySource_thenSmartImageSource() {
    ImageSourceProvider.treatEmptyUriAsImageSource = false

    assertThat(ImageSourceProvider.forUri("")).isNotInstanceOf(EmptyImageSource::class.java)
    assertThat(ImageSourceProvider.smartForUri("", SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isInstanceOf(SmartImageSource::class.java)
  }

  @Test
  fun testSmartForUri_whenUriParserReturnsNull_thenSameEmptySourceAsForUri() {
    ImageSourceProvider.uriParser = { null }

    assertThat(ImageSourceProvider.smartForUri(IMAGE_URL, SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isInstanceOf(EmptyImageSource::class.java)
        .isSameAs(ImageSourceProvider.forUri(IMAGE_URL))
  }

  @Test
  fun testSmartForUri_whenResUriAndShortcutResUris_thenSameDrawableResImageSourceAsForUri() {
    ImageSourceProvider.shortcutResUris = true

    assertThat(
        ImageSourceProvider.smartForUri(Uri.parse(RES_URL), SmartFetchOptIn.ENABLED_NO_FALLBACK),
    )
        .isEqualTo(DrawableResImageSource(RES_ID))
        .isEqualTo(ImageSourceProvider.forUri(Uri.parse(RES_URL)))
    assertThat(ImageSourceProvider.smartForUri(RES_URL, SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isEqualTo(ImageSourceProvider.forUri(RES_URL))
  }

  @Test
  fun testSmartForUri_whenIdIsLastOfSeveralSegmentsAndShortcutResUris_thenSameSourceAsForUri() {
    ImageSourceProvider.shortcutResUris = true

    for (url in listOf("res:/drawable/$RES_ID", "res:/$RES_ID/")) {
      val uri = Uri.parse(url)
      assertThat(ImageSourceProvider.smartForUri(uri, SmartFetchOptIn.ENABLED_NO_FALLBACK))
          .isEqualTo(DrawableResImageSource(RES_ID))
          .isEqualTo(ImageSourceProvider.forUri(uri))
    }
  }

  @Test
  fun testSmartForUri_whenBackslashBeforeIdAndShortcutResUris_thenEmptySource() {
    ImageSourceProvider.shortcutResUris = true

    assertThat(
        ImageSourceProvider.smartForUri(
            Uri.parse("res://host\\$RES_ID"),
            SmartFetchOptIn.ENABLED_NO_FALLBACK,
        ),
    )
        .isInstanceOf(EmptyImageSource::class.java)
  }

  @Test
  fun testSmartForUri_whenResUriPathIsNotAnInteger_thenEmptySourceForBothShortcutSettings() {
    for (shortcut in listOf(true, false)) {
      ImageSourceProvider.shortcutResUris = shortcut
      for (url in INVALID_RES_URLS) {
        assertThat(
            ImageSourceProvider.smartForUri(Uri.parse(url), SmartFetchOptIn.ENABLED_NO_FALLBACK),
        )
            .isInstanceOf(EmptyImageSource::class.java)
        assertThat(ImageSourceProvider.smartForUri(url, SmartFetchOptIn.ENABLED_NO_FALLBACK))
            .isInstanceOf(EmptyImageSource::class.java)
      }
    }
  }

  @Test
  fun testSmartForUri_whenResUriPathIsNotAnInteger_thenImageRequestBuilderThrows() {
    for (url in INVALID_RES_URLS) {
      // BuilderException for a hierarchical URI; NullPointerException for the opaque "res:123".
      assertThatThrownBy { ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).build() }
          .isInstanceOf(RuntimeException::class.java)
    }
  }

  @Test
  fun testSmartForUri_whenNotAResourceIdAndShortcutResUris_thenEmptySource() {
    ImageSourceProvider.shortcutResUris = true

    for (url in NOT_RESOURCE_ID_RES_URLS) {
      assertThat(
          ImageSourceProvider.smartForUri(Uri.parse(url), SmartFetchOptIn.ENABLED_NO_FALLBACK),
      )
          .isInstanceOf(EmptyImageSource::class.java)
      assertThat(ImageSourceProvider.smartForUri(url, SmartFetchOptIn.ENABLED_NO_FALLBACK))
          .isInstanceOf(EmptyImageSource::class.java)
    }
  }

  @Test
  fun testSmartForUri_whenNegativeResourceIdAndShortcutResUris_thenSameDrawableResImageSourceAsForUri() {
    ImageSourceProvider.shortcutResUris = true
    val uri = Uri.parse("res:/$NEGATIVE_RES_ID")

    assertThat(ImageSourceProvider.smartForUri(uri, SmartFetchOptIn.ENABLED_NO_FALLBACK))
        .isEqualTo(DrawableResImageSource(NEGATIVE_RES_ID))
        .isEqualTo(ImageSourceProvider.forUri(uri))
  }

  @Test
  fun testSmartForUri_whenResUriAndNoShortcutResUris_thenSameSourceAsForUri() {
    ImageSourceProvider.shortcutResUris = false

    for (url in listOf(RES_URL, "res:/0", "res:/$RES_ID?cstp=mx800x800&ctp=s800x800")) {
      val uri = Uri.parse(url)
      assertThat(
          ImageSourceProvider.smartForUri(
              uri,
              SmartFetchOptIn.ENABLED_NO_FALLBACK,
              SizingHint.DEFAULT,
              EXTRAS,
          ),
      )
          .isNotInstanceOf(SmartImageSource::class.java)
          .isEqualTo(ImageSourceProvider.forUri(uri, EXTRAS))
      assertThat(ImageRequestBuilder.newBuilderWithSource(uri).build().sourceUri).isEqualTo(uri)
    }
  }

  private companion object {
    const val RES_ID = 2131230720
    const val RES_URL = "res:/$RES_ID"
    const val NEGATIVE_RES_ID = 0x80010000.toInt()
    val INVALID_RES_URLS =
        listOf("res:///drawable/icon", "res:/", "res:/2147483648", "res:/drawable/123", "res:123")
    val NOT_RESOURCE_ID_RES_URLS =
        listOf("res:/0", "res:/123", "res:/16777216", "res:/0?cstp=mx2147483648x1&ctp=s100x100")
    const val IMAGE_URL = "https://scontent.xx.fbcdn.net/v/t39.30808-6/1_2_3_n.jpg?ctp=s160x160"
    val EXTRAS: Map<String, Any> = mapOf("key" to "value")
  }
}
