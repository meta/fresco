/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.cache.common.CacheKey
import com.facebook.common.internal.Supplier
import com.facebook.common.memory.MemoryTrimmableRegistry
import com.facebook.imagepipeline.cache.CountingMemoryCache.EntryStateObserver
import com.facebook.imagepipeline.cache.MemoryCache.CacheTrimStrategy
import com.facebook.imagepipeline.image.CloseableImage

fun interface BitmapMemoryCacheFactory {
  fun create(
      bitmapMemoryCacheParamsSupplier: Supplier<MemoryCacheParams>,
      memoryTrimmableRegistry: MemoryTrimmableRegistry,
      trimStrategy: CacheTrimStrategy,
      storeEntrySize: Boolean,
      ignoreSizeMismatch: Boolean,
      observer: EntryStateObserver<CacheKey>?,
  ): CountingMemoryCache<CacheKey, CloseableImage>

  /**
   * True if the produced cache evicts WITHOUT relying on CloseableReference counts (i.e. safe to
   * use when bitmaps are in REF_TYPE_NOOP mode). Counting caches that evict via client-reference
   * exclusivity MUST return false (the default), because under NOOP their client count never
   * reaches zero and they would never evict -> unbounded growth.
   */
  fun isGcSafe(): Boolean = false
}
