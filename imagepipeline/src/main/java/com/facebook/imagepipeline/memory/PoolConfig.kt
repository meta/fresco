/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.memory

import com.facebook.common.memory.MemoryTrimmableRegistry
import com.facebook.common.memory.NoOpMemoryTrimmableRegistry
import com.facebook.imagepipeline.systrace.FrescoSystrace.beginSection
import com.facebook.imagepipeline.systrace.FrescoSystrace.endSection
import com.facebook.imagepipeline.systrace.FrescoSystrace.isTracing

/** Configuration class for pools. */
class PoolConfig private constructor(builder: Builder) {
  // There are a lot of parameters in this class. Please follow strict alphabetical order.
  val bitmapPoolParams: PoolParams
  val bitmapPoolStatsTracker: PoolStatsTracker
  val flexByteArrayPoolParams: PoolParams
  val memoryTrimmableRegistry: MemoryTrimmableRegistry
  val memoryChunkPoolParams: PoolParams
  val memoryChunkPoolStatsTracker: PoolStatsTracker
  val smallByteArrayPoolParams: PoolParams
  val smallByteArrayPoolStatsTracker: PoolStatsTracker
  val bitmapPoolType: String
  val bitmapPoolMaxPoolSize: Int
  val bitmapPoolMaxBitmapSize: Int
  val isRegisterLruBitmapPoolAsMemoryTrimmable: Boolean

  init {
    if (isTracing()) {
      beginSection("PoolConfig()")
    }
    bitmapPoolParams = builder.bitmapPoolParams ?: DefaultBitmapPoolParams.get()
    bitmapPoolStatsTracker = builder.bitmapPoolStatsTracker ?: NoOpPoolStatsTracker.getInstance()
    flexByteArrayPoolParams =
        builder.flexByteArrayPoolParams ?: DefaultFlexByteArrayPoolParams.get()
    memoryTrimmableRegistry =
        builder.memoryTrimmableRegistry ?: NoOpMemoryTrimmableRegistry.getInstance()
    memoryChunkPoolParams =
        builder.memoryChunkPoolParams ?: DefaultNativeMemoryChunkPoolParams.get()
    memoryChunkPoolStatsTracker =
        builder.memoryChunkPoolStatsTracker ?: NoOpPoolStatsTracker.getInstance()
    smallByteArrayPoolParams = builder.smallByteArrayPoolParams ?: DefaultByteArrayPoolParams.get()
    smallByteArrayPoolStatsTracker =
        builder.smallByteArrayPoolStatsTracker ?: NoOpPoolStatsTracker.getInstance()

    bitmapPoolType = builder.bitmapPoolType ?: BitmapPoolType.DEFAULT
    bitmapPoolMaxPoolSize = builder.bitmapPoolMaxPoolSize
    bitmapPoolMaxBitmapSize =
        if (builder.bitmapPoolMaxBitmapSize > 0) builder.bitmapPoolMaxBitmapSize
        else BITMAP_POOL_MAX_BITMAP_SIZE_DEFAULT
    isRegisterLruBitmapPoolAsMemoryTrimmable = builder.registerLruBitmapPoolAsMemoryTrimmable
    if (isTracing()) {
      endSection()
    }
  }

  class Builder {
    internal var bitmapPoolParams: PoolParams? = null
    internal var bitmapPoolStatsTracker: PoolStatsTracker? = null
    internal var flexByteArrayPoolParams: PoolParams? = null
    internal var memoryTrimmableRegistry: MemoryTrimmableRegistry? = null
    internal var memoryChunkPoolParams: PoolParams? = null
    internal var memoryChunkPoolStatsTracker: PoolStatsTracker? = null
    internal var smallByteArrayPoolParams: PoolParams? = null
    internal var smallByteArrayPoolStatsTracker: PoolStatsTracker? = null
    internal var bitmapPoolType: String? = null
    internal var bitmapPoolMaxPoolSize: Int = 0
    internal var bitmapPoolMaxBitmapSize: Int = 0
    internal var registerLruBitmapPoolAsMemoryTrimmable: Boolean = false

    fun setBitmapPoolParams(bitmapPoolParams: PoolParams): Builder {
      this.bitmapPoolParams = bitmapPoolParams
      return this
    }

    fun setBitmapPoolStatsTracker(bitmapPoolStatsTracker: PoolStatsTracker): Builder {
      this.bitmapPoolStatsTracker = bitmapPoolStatsTracker
      return this
    }

    fun setFlexByteArrayPoolParams(flexByteArrayPoolParams: PoolParams?): Builder {
      this.flexByteArrayPoolParams = flexByteArrayPoolParams
      return this
    }

    fun setMemoryTrimmableRegistry(memoryTrimmableRegistry: MemoryTrimmableRegistry?): Builder {
      this.memoryTrimmableRegistry = memoryTrimmableRegistry
      return this
    }

    fun setNativeMemoryChunkPoolParams(memoryChunkPoolParams: PoolParams): Builder {
      this.memoryChunkPoolParams = memoryChunkPoolParams
      return this
    }

    fun setNativeMemoryChunkPoolStatsTracker(
        memoryChunkPoolStatsTracker: PoolStatsTracker,
    ): Builder {
      this.memoryChunkPoolStatsTracker = memoryChunkPoolStatsTracker
      return this
    }

    fun setSmallByteArrayPoolParams(commonByteArrayPoolParams: PoolParams): Builder {
      smallByteArrayPoolParams = commonByteArrayPoolParams
      return this
    }

    fun setSmallByteArrayPoolStatsTracker(
        smallByteArrayPoolStatsTracker: PoolStatsTracker,
    ): Builder {
      this.smallByteArrayPoolStatsTracker = smallByteArrayPoolStatsTracker
      return this
    }

    fun build(): PoolConfig {
      return PoolConfig(this)
    }

    fun setBitmapPoolType(bitmapPoolType: String?): Builder {
      this.bitmapPoolType = bitmapPoolType
      return this
    }

    fun setBitmapPoolMaxPoolSize(bitmapPoolMaxPoolSize: Int): Builder {
      this.bitmapPoolMaxPoolSize = bitmapPoolMaxPoolSize
      return this
    }

    fun setBitmapPoolMaxBitmapSize(bitmapPoolMaxBitmapSize: Int): Builder {
      this.bitmapPoolMaxBitmapSize = bitmapPoolMaxBitmapSize
      return this
    }

    fun setRegisterLruBitmapPoolAsMemoryTrimmable(
        registerLruBitmapPoolAsMemoryTrimmable: Boolean,
    ): Builder {
      this.registerLruBitmapPoolAsMemoryTrimmable = registerLruBitmapPoolAsMemoryTrimmable
      return this
    }
  }

  companion object {
    // = 1024 * 1024 * BitmapUtil.ARGB_8888_BYTES_PER_PIXEL (spelled out for source-only ABI)
    const val BITMAP_POOL_MAX_BITMAP_SIZE_DEFAULT: Int = 1024 * 1024 * 4

    @JvmStatic
    fun newBuilder(): Builder {
      return Builder()
    }
  }
}
