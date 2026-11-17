/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.app.ActivityManager
import com.facebook.common.internal.Supplier
import com.facebook.common.util.ByteConstants
import com.facebook.infer.annotation.Nullsafe
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Supplies [MemoryCacheParams] for the bitmap memory cache. */
@Nullsafe(Nullsafe.Mode.LOCAL)
class DefaultBitmapMemoryCacheParamsSupplier(private val mActivityManager: ActivityManager) :
    Supplier<MemoryCacheParams> {
  override fun get(): MemoryCacheParams {
    return MemoryCacheParams(
        maxCacheSize,
        MAX_CACHE_ENTRIES,
        MAX_EVICTION_QUEUE_SIZE,
        MAX_EVICTION_QUEUE_ENTRIES,
        MAX_CACHE_ENTRY_SIZE,
        PARAMS_CHECK_INTERVAL_MS,
    )
  }

  private val maxCacheSize: Int
    get() {
      val maxMemory = min(
          mActivityManager.memoryClass * ByteConstants.MB,
          Int.Companion.MAX_VALUE,
      )
      return if (maxMemory < 32 * ByteConstants.MB) {
        4 * ByteConstants.MB
      } else if (maxMemory < 64 * ByteConstants.MB) {
        6 * ByteConstants.MB
      } else {
        maxMemory / 4
      }
    }

  companion object {
    private const val MAX_CACHE_ENTRIES = 256
    private const val MAX_EVICTION_QUEUE_SIZE = Int.Companion.MAX_VALUE
    private const val MAX_EVICTION_QUEUE_ENTRIES = Int.Companion.MAX_VALUE
    private const val MAX_CACHE_ENTRY_SIZE = Int.Companion.MAX_VALUE
    private val PARAMS_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)
  }
}
