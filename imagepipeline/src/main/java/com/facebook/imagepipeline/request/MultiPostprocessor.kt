/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request

import android.graphics.Bitmap
import com.facebook.cache.common.CacheKey
import com.facebook.cache.common.MultiCacheKey
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.cache.FullPostprocessorCacheKeyProvider

/** Runs a list of [Postprocessor]s in sequence, each operating on the previous one's result. */
class MultiPostprocessor(private val postprocessors: List<Postprocessor>) :
    BasePostprocessor(), FullPostprocessorCacheKeyProvider {

  override fun getName(): String =
      "MultiPostprocessor(${postprocessors.joinToString(",") { it.name }})"

  override fun getPostprocessorCacheKey(): CacheKey? {
    val keys = ArrayList<CacheKey>(postprocessors.size)
    for (postprocessor in postprocessors) {
      // A null component key means "not cacheable"; don't fabricate a composite key.
      keys.add(postprocessor.postprocessorCacheKey ?: return null)
    }
    return MultiCacheKey(keys)
  }

  override fun getFullPostprocessorCacheKey(): CacheKey? {
    val keys = ArrayList<CacheKey>(postprocessors.size)
    for (postprocessor in postprocessors) {
      val key =
          if (postprocessor is FullPostprocessorCacheKeyProvider)
              postprocessor.getFullPostprocessorCacheKey()
          else postprocessor.postprocessorCacheKey
      keys.add(key ?: return null)
    }
    return MultiCacheKey(keys)
  }

  override fun process(
      sourceBitmap: Bitmap,
      bitmapFactory: PlatformBitmapFactory,
  ): CloseableReference<Bitmap> {
    var current: CloseableReference<Bitmap>? = null
    try {
      for (postprocessor in postprocessors) {
        val next = postprocessor.process(current?.get() ?: sourceBitmap, bitmapFactory)
        CloseableReference.closeSafely(current)
        current = next
      }
      return checkNotNull(CloseableReference.cloneOrNull(current)) {
        "MultiPostprocessor requires at least one postprocessor"
      }
    } finally {
      CloseableReference.closeSafely(current)
    }
  }
}
