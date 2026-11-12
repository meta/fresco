/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.net.Uri
import com.facebook.cache.common.SimpleCacheKey
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.common.RotationOptions
import com.facebook.imagepipeline.request.BasePostprocessor
import com.facebook.imagepipeline.request.ImageRequestBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UnifiedCacheKeyFactoryTest {
  private val normalizer =
      object : UriNormalizer {
        override fun normalize(uri: Uri, callerContext: Any?) = NormalizedUri("normalized")
      }
  private val dimensions =
      object : DimensionExtractor {
        override fun extractDimensions(
            request: com.facebook.imagepipeline.request.ImageRequest,
            sourceUri: Uri,
            callerContext: Any?,
        ) = 100 to 200
      }
  private val config = CacheKeyConfig(
      includeDimensionsInBitmapKey = true,
      includeDimensionsInEncodedKey = true,
      hashThreshold = 250,
      dimensionExtractor = dimensions,
  )
  private val factory = UnifiedCacheKeyFactory(normalizer, config)

  @Test
  fun cacheKeyConfig_preservesLegacyPositionalArguments() {
    val positionalConfig = CacheKeyConfig(false, false, Int.MAX_VALUE, true)

    assertThat(positionalConfig.excludeBitmapConfigFromComparison).isTrue()
  }

  @Test
  fun publicApi_preservesReleasedJvmSignatures() {
    assertThat(CacheKeyConfig::class.java.declaredConstructors.map { it.parameterTypes.size })
        .contains(8)
    assertThat(
        CacheKeyConfig::class.java.declaredMethods.single { it.name == "copy" }.parameterTypes.size,
    )
        .isEqualTo(8)
    assertThat(
        UnifiedCacheKeyFactory::class
            .java
            .getConstructor(
                UriNormalizer::class.java,
                CacheKeyConfig::class.java,
            ),
    )
        .isNotNull()
  }

  @Test
  fun frescoAdapter_preservesBitmapAndEncodedKeys() {
    val resize = ResizeOptions(40, 50)
    val rotation = RotationOptions.forceRotation(90)
    val request =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("https://example.com/image.jpg"))
            .setResizeOptions(resize)
            .setRotationOptions(rotation)
            .build()

    assertThat(factory.getBitmapCacheKey(request, null))
        .isEqualTo(
            BitmapMemoryCacheKey(
                "normalized100_200",
                resize,
                rotation,
                request.imageDecodeOptions,
                null,
                null,
            ),
        )
    assertThat(factory.getEncodedCacheKey(request, null))
        .isEqualTo(SimpleCacheKey("normalized100_200"))
  }

  @Test
  fun frescoAdapter_nullResizeFilterResultPreservesResizeOptions() {
    val resizeOptions = ResizeOptions(40, 50)
    val request =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("https://example.com/image.jpg"))
            .setResizeOptions(resizeOptions)
            .build()
    val filteringFactory = UnifiedCacheKeyFactory(
        normalizer,
        config.copy(resizeOptionsFilter = { _, _ -> null }),
    )

    val cacheKey = filteringFactory.getBitmapCacheKey(request, null) as BitmapMemoryCacheKey

    assertThat(cacheKey.resizeOptions).isEqualTo(resizeOptions)
  }

  @Test
  fun frescoAdapter_separatesBitmapKeysByResizeTranscoder() {
    fun bitmapKey(transcoder: ResizeOptions.Transcoder) =
        factory.getBitmapCacheKey(
            ImageRequestBuilder.newBuilderWithSource(Uri.parse("https://example.com/image.jpg"))
                .setResizeOptions(ResizeOptions(40, 50, transcoder = transcoder))
                .build(),
            null,
        )

    assertThat(bitmapKey(ResizeOptions.Transcoder.NONE))
        .isNotEqualTo(bitmapKey(ResizeOptions.Transcoder.DEFAULT))
  }

  @Test
  fun frescoAdapter_preservesCustomAndPostprocessorIdentity() {
    val postprocessor =
        object : BasePostprocessor() {
          override fun getPostprocessorCacheKey() = SimpleCacheKey("postprocessor")
        }
    val request =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("https://example.com/image.jpg"))
            .setCustomCacheKey("custom")
            .setPostprocessor(postprocessor)
            .build()

    assertThat(factory.getEncodedCacheKey(request, null)).isEqualTo(SimpleCacheKey("custom"))
    assertThat(factory.getPostprocessedBitmapCacheKey(request, null))
        .isEqualTo(
            BitmapMemoryCacheKey(
                "custom",
                null,
                request.rotationOptions,
                request.imageDecodeOptions,
                SimpleCacheKey("postprocessor"),
                postprocessor.javaClass.name,
            ),
        )
  }

  @Test
  fun frescoAdapter_supportsEncodedKeyOverridesOutsideCacheKeyConfig() {
    val encodedDimensions =
        object : DimensionExtractor {
          override fun extractDimensions(
              request: com.facebook.imagepipeline.request.ImageRequest,
              sourceUri: Uri,
              callerContext: Any?,
          ) = 10 to 20
        }
    val overrideFactory = UnifiedCacheKeyFactory(
        normalizer,
        config.copy(hashThreshold = 1),
        hashEncodedKey = false,
        encodedDimensionExtractor = encodedDimensions,
    )
    val request =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("https://example.com/image.jpg")).build()

    assertThat(overrideFactory.getEncodedCacheKey(request, null))
        .isEqualTo(SimpleCacheKey("normalized10_20"))
  }
}
