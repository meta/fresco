/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.net.Uri

/** Normalizes a [Uri] for cache key generation. */
fun interface UriNormalizer {
  fun normalize(uri: Uri, callerContext: Any?): NormalizedUri
}

data class NormalizedUri(
    val cacheKeyString: String,
)
