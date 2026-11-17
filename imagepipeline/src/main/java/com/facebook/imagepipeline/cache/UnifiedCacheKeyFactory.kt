/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.net.Uri
import com.facebook.cache.common.CacheKey
import com.facebook.cache.common.SimpleCacheKey
import com.facebook.imagepipeline.common.ImageDecodeOptionsBuilder
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.request.Postprocessor

/**
 * A single cache key factory that can serve multiple apps (FB4A, IG, etc.) with different
 * configurations via [UriNormalizer] and [CacheKeyConfig].
 *
 * This replaces app-specific subclasses of [DefaultCacheKeyFactory] with one composable
 * implementation.
 */
class UnifiedCacheKeyFactory
@JvmOverloads
constructor(
    private val uriNormalizer: UriNormalizer,
    private val config: CacheKeyConfig,
    private val hashEncodedKey: Boolean = true,
    private val encodedDimensionExtractor: DimensionExtractor? = null,
    private val postprocessorCacheKeyOverride: ((Postprocessor) -> CacheKey?)? = null,
) : CacheKeyFactory {

  override fun getBitmapCacheKey(request: ImageRequest, callerContext: Any?): CacheKey {
    return createBitmapCacheKey(request, callerContext, null, null)
  }

  override fun getPostprocessedBitmapCacheKey(
      request: ImageRequest,
      callerContext: Any?,
  ): CacheKey {
    val postprocessor = request.postprocessor
    return if (postprocessor != null) {
      createBitmapCacheKey(
          request,
          callerContext,
          if (postprocessorCacheKeyOverride != null) postprocessorCacheKeyOverride(postprocessor)
          else postprocessor.postprocessorCacheKey,
          postprocessor.javaClass.name,
      )
    } else {
      createBitmapCacheKey(request, callerContext, null, null)
    }
  }

  override fun getEncodedCacheKey(request: ImageRequest, callerContext: Any?): CacheKey {
    return getEncodedCacheKey(request, request.sourceUri, callerContext)
  }

  override fun getEncodedCacheKey(
      request: ImageRequest,
      sourceUri: Uri,
      callerContext: Any?,
  ): CacheKey {
    val customCacheKey = request.customCacheKey
    if (customCacheKey != null) {
      return config.encodedKeyEnricher?.enrich(customCacheKey, callerContext, sourceUri)
          ?: SimpleCacheKey(customCacheKey)
    }

    val dimensions =
        if (config.includeDimensionsInEncodedKey)
            (encodedDimensionExtractor ?: config.dimensionExtractor)?.extractDimensions(
                request,
                sourceUri,
                callerContext,
            )
        else null
    val keyString =
        generator
            .resolve(
                UnifiedCacheKeyInput(
                    uriNormalizer.normalize(sourceUri, callerContext),
                    encodedDimensions = dimensions?.let { CacheKeyDimensions(it.first, it.second) },
                ),
            )
            .encodedKey

    return config.encodedKeyEnricher?.enrich(keyString, callerContext, sourceUri)
        ?: SimpleCacheKey(keyString)
  }

  private fun createBitmapCacheKey(
      request: ImageRequest,
      callerContext: Any?,
      postprocessorCacheKey: CacheKey?,
      postprocessorName: String?,
  ): CacheKey {
    val customKey = request.customCacheKey
    val normalized =
        if (customKey == null) uriNormalizer.normalize(request.sourceUri, callerContext)
        else NormalizedUri(customKey)
    val dimensions =
        if (customKey == null) {
          if (config.includeDimensionsInBitmapKey)
              config.dimensionExtractor?.extractDimensions(
                  request,
                  request.sourceUri,
                  callerContext,
              )
          else null
        } else null
    val sourceString =
        generator
            .resolve(
                UnifiedCacheKeyInput(
                    normalized,
                    customKey,
                    bitmapDimensions = dimensions?.let { CacheKeyDimensions(it.first, it.second) },
                ),
            )
            .bitmapSourceKey

    val resizeOptions =
        config.resizeOptionsFilter?.invoke(request.sourceUriType, request.resizeOptions)
            ?: request.resizeOptions

    val decodeOptions =
        if (config.excludeBitmapConfigFromComparison) {
          ImageDecodeOptionsBuilder<ImageDecodeOptionsBuilder<*>>()
              .setFrom(request.imageDecodeOptions)
              .setExcludeBitmapConfigFromComparison(true)
              .build()
        } else {
          request.imageDecodeOptions
        }

    val cacheKey = BitmapMemoryCacheKey(
        sourceString,
        resizeOptions,
        request.rotationOptions,
        decodeOptions,
        postprocessorCacheKey,
        postprocessorName,
    )

    config.debugImageTracker?.invoke(request, cacheKey)
    return cacheKey
  }

  private val generator = UnifiedCacheKeyGenerator(
      UnifiedCacheKeyGeneratorConfig(
          config.includeDimensionsInBitmapKey,
          config.includeDimensionsInEncodedKey,
          config.hashThreshold,
          hashEncodedKey = hashEncodedKey,
      ),
  )
}
