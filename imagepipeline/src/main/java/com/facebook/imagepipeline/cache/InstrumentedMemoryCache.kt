/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.common.internal.Predicate
import com.facebook.common.memory.MemoryTrimType
import com.facebook.common.references.CloseableReference
import com.facebook.infer.annotation.Nullsafe

@Nullsafe(Nullsafe.Mode.LOCAL)
class InstrumentedMemoryCache<K, V>(
    private val mDelegate: MemoryCache<K, V>,
    private val mTracker: MemoryCacheTracker<K>,
) : MemoryCache<K, V> {
  override fun get(key: K): CloseableReference<V>? {
    val result = mDelegate[key]
    if (result == null) {
      mTracker.onCacheMiss(key)
    } else {
      mTracker.onCacheHit(key)
    }
    return result
  }

  override fun inspect(key: K): V? {
    return mDelegate.inspect(key)
  }

  override fun probe(key: K) {
    mDelegate.probe(key)
  }

  override fun cache(key: K, value: CloseableReference<V>): CloseableReference<V>? {
    mTracker.onCachePut(key)
    return mDelegate.cache(key, value)
  }

  override fun removeAll(predicate: Predicate<K>): Int {
    return mDelegate.removeAll(predicate)
  }

  override fun contains(predicate: Predicate<K>): Boolean {
    return mDelegate.contains(predicate)
  }

  override fun contains(key: K): Boolean {
    return mDelegate.contains(key)
  }

  override val count: Int
    get() = mDelegate.count

  override val sizeInBytes: Int
    get() = mDelegate.sizeInBytes

  override fun trim(trimType: MemoryTrimType) {
    mDelegate.trim(trimType)
  }

  override val debugData: String?
    get() = mDelegate.debugData
}
