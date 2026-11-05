/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.net.Uri
import com.facebook.cache.common.CacheKey
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequest

/**
 * Configuration for [UnifiedCacheKeyFactory].
 *
 * Each field is optional and defaults to the simplest behavior matching [DefaultCacheKeyFactory].
 * App-specific wiring (FB4A, IG, etc.) provides non-default values as needed.
 */
data class CacheKeyConfig(
    /** Whether to append image dimensions to bitmap cache key sourceString. */
    val includeDimensionsInBitmapKey: Boolean = false,
    /** Whether to append image dimensions to encoded cache keys. */
    val includeDimensionsInEncodedKey: Boolean = false,
    /** Key strings longer than this are hashed to their hashCode. */
    val hashThreshold: Int = Int.MAX_VALUE,
    /** Whether to exclude bitmap config from ImageDecodeOptions comparison in cache keys. */
    val excludeBitmapConfigFromComparison: Boolean = false,
    /**
     * Optional filter applied to resize options before they are included in the bitmap cache key.
     * Receives the [SourceUriType] and the original [ResizeOptions].
     */
    val resizeOptionsFilter: ((Int, ResizeOptions?) -> ResizeOptions?)? = null,
    /** Extracts image dimensions from a request for encoded cache key generation. */
    val dimensionExtractor: DimensionExtractor? = null,
    /** Optional enricher for encoded cache keys (e.g. adding caller context metadata). */
    val encodedKeyEnricher: EncodedCacheKeyEnricher? = null,
    /** Optional callback invoked after creating a bitmap cache key, for debug tracking. */
    val debugImageTracker: ((ImageRequest, CacheKey) -> Unit)? = null,
)

/** Extracts width/height dimensions from a request for cache key generation. */
fun interface DimensionExtractor {
  fun extractDimensions(
      request: ImageRequest,
      sourceUri: Uri,
      callerContext: Any?,
  ): Pair<Int, Int>?
}

/**
 * Produces an app-specific [CacheKey] from an encoded cache key string.
 *
 * Implementations may transform the key string and attach metadata for downstream consumers (e.g.
 * debugging tools or disk cache eviction). Any transformation must be deterministic because the
 * returned [CacheKey] defines cache lookup equality.
 */
fun interface EncodedCacheKeyEnricher {
  fun enrich(keyString: String, callerContext: Any?, sourceUri: Uri): CacheKey
}
