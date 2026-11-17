/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.common.logging.FLog
import com.facebook.common.memory.MemoryTrimType
import com.facebook.imagepipeline.cache.MemoryCache.CacheTrimStrategy
import com.facebook.infer.annotation.Nullsafe

/**
 * CountingMemoryCache eviction strategy appropriate for caches that store resources off the Dalvik
 * heap.
 *
 * In case of OnCloseToDalvikHeapLimit nothing will be done. In case of other trim types eviction
 * queue of the cache will be cleared.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
class NativeMemoryCacheTrimStrategy : CacheTrimStrategy {
  override fun getTrimRatio(trimType: MemoryTrimType): Double {
    when (trimType) {
      MemoryTrimType
          .OnCloseToDalvikHeapLimit -> // Resources cached on native heap do not consume Dalvik
          // heap, so no trimming here.
          return 0.0

      MemoryTrimType.OnAppBackgrounded,
      MemoryTrimType.OnSystemMemoryCriticallyLowWhileAppInForeground,
      MemoryTrimType.OnSystemLowMemoryWhileAppInForeground,
      MemoryTrimType.OnSystemLowMemoryWhileAppInBackgroundLowSeverity -> return 1.0
      else -> {
        FLog.wtf(TAG, "unknown trim type: %s", trimType)
        return 0.0
      }
    }
  }

  companion object {
    private const val TAG = "NativeMemoryCacheTrimStrategy"
  }
}
