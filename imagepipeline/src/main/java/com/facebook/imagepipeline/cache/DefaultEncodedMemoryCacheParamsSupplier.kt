/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.common.internal.Supplier
import com.facebook.common.util.ByteConstants
import com.facebook.infer.annotation.Nullsafe
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Supplies [MemoryCacheParams] for the encoded image memory cache */
@Nullsafe(Nullsafe.Mode.LOCAL)
class DefaultEncodedMemoryCacheParamsSupplier : Supplier<MemoryCacheParams> {
  override fun get(): MemoryCacheParams {
    val maxCacheSize = this.maxCacheSize
    val maxCacheEntrySize = maxCacheSize / 8
    return MemoryCacheParams(
        maxCacheSize,
        MAX_CACHE_ENTRIES,
        maxCacheSize,
        MAX_EVICTION_QUEUE_ENTRIES,
        maxCacheEntrySize,
        PARAMS_CHECK_INTERVAL_MS,
    )
  }

  private val maxCacheSize: Int
    get() {
      val maxMemory = min(
          Runtime.getRuntime().maxMemory(),
          Int.Companion.MAX_VALUE.toLong(),
      )
          .toInt()
      return if (maxMemory < 16 * ByteConstants.MB) {
        1 * ByteConstants.MB
      } else if (maxMemory < 32 * ByteConstants.MB) {
        2 * ByteConstants.MB
      } else {
        4 * ByteConstants.MB
      }
    }

  companion object {
    // We want memory cache to be bound only by its memory consumption
    private const val MAX_CACHE_ENTRIES = Int.Companion.MAX_VALUE
    private const val MAX_EVICTION_QUEUE_ENTRIES = MAX_CACHE_ENTRIES
    private val PARAMS_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
  }
}
