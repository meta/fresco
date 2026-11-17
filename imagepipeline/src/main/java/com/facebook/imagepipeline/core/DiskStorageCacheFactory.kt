/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.core

import com.facebook.cache.disk.DiskCacheConfig
import com.facebook.cache.disk.DiskStorage
import com.facebook.cache.disk.DiskStorageCache
import com.facebook.cache.disk.FileCache
import com.facebook.infer.annotation.Nullsafe
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Factory for the default implementation of the FileCacheFactory. */
@Nullsafe(Nullsafe.Mode.LOCAL)
class DiskStorageCacheFactory(private val mDiskStorageFactory: DiskStorageFactory) :
    FileCacheFactory {
  override fun get(diskCacheConfig: DiskCacheConfig): FileCache {
    return buildDiskStorageCache(diskCacheConfig, mDiskStorageFactory.get(diskCacheConfig))
  }

  companion object {
    private fun buildDiskStorageCache(
        diskCacheConfig: DiskCacheConfig,
        diskStorage: DiskStorage,
        executorForBackgroundInit: Executor = Executors.newSingleThreadExecutor(),
    ): DiskStorageCache {
      val params =
          DiskStorageCache.Params(
              diskCacheConfig.minimumSizeLimit,
              diskCacheConfig.lowDiskSpaceSizeLimit,
              diskCacheConfig.defaultSizeLimit,
          )

      return DiskStorageCache(
          diskStorage,
          diskCacheConfig.entryEvictionComparatorSupplier,
          params,
          diskCacheConfig.cacheEventListener,
          diskCacheConfig.cacheErrorLogger,
          diskCacheConfig.diskTrimmableRegistry,
          executorForBackgroundInit,
          diskCacheConfig.indexPopulateAtStartupEnabled,
      )
    }
  }
}
