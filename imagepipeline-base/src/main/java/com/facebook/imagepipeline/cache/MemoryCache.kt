/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.cache.common.HasDebugData
import com.facebook.common.internal.Predicate
import com.facebook.common.memory.MemoryTrimType
import com.facebook.common.memory.MemoryTrimmable
import com.facebook.common.references.CloseableReference

/**
 * Interface for the image pipeline memory cache.
 *
 * @param <K> the key type
 * @param <V> the value type </V></K>
 */
interface MemoryCache<K, V> : MemoryTrimmable, HasDebugData {

  /** Interface used to specify the trimming strategy for the cache. */
  fun interface CacheTrimStrategy {
    fun getTrimRatio(trimType: MemoryTrimType): Double
  }

  /**
   * Caches the the given key-value pair.
   *
   * The cache returns a new copy of the provided reference which should be used instead of the
   * original one. The client should close the returned reference when it is not required anymore.
   *
   * If the cache failed to cache the given value, then the null reference is returned.
   *
   * @param key
   * @param value
   * @return a new reference to be used, or null if the caching failed
   */
  fun cache(key: K, value: CloseableReference<V>): CloseableReference<V>?

  /**
   * Gets the item with the given key, or null if there is no such item.
   *
   * @param key
   * @return a reference to the cached value, or null if the item was not found
   */
  operator fun get(key: K): CloseableReference<V>?

  /**
   * Gets the item with the given key for debug purposes. For instance, for LRU caches this will not
   * change the LRU order. Use [get(K)] instead.
   *
   * @param key
   * @return a cached value or null if the item was not found
   */
  fun inspect(key: K): V?

  /**
   * Probes whether the object corresponding to the key is in the cache. Note that the act of
   * probing touches the item (if present in cache), thus changing its LRU timestamp.
   *
   * @param key
   */
  fun probe(key: K)

  /**
   * Removes all the items from the cache whose keys match the specified predicate.
   *
   * @param predicate returns true if an item with the given key should be removed
   * @return number of the items removed from the cache
   */
  fun removeAll(predicate: Predicate<K>): Int

  /**
   * Find if any of the items from the cache whose keys match the specified predicate.
   *
   * @param predicate returns true if an item with the given key matches
   * @return true if the predicate was found in the cache, false otherwise
   */
  operator fun contains(predicate: Predicate<K>): Boolean

  /**
   * Check if the cache contains an item for the given key.
   *
   * @param key
   * @return true if the key was found in the cache, false otherwise
   */
  operator fun contains(key: K): Boolean

  /** Gets the total number of all currently cached items. */
  val count: Int

  /** Gets the total size in bytes of all currently cached items. */
  val sizeInBytes: Int
}

/** Optional capability for memory caches that support direct key removal. */
fun interface MemoryCacheWithExactKeyRemoval<K> {
  fun remove(key: K): Int
}
