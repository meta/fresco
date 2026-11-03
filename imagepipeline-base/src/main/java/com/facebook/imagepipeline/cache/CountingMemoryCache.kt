/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import com.facebook.common.memory.MemoryTrimmable
import com.facebook.common.references.CloseableReference

interface CountingMemoryCache<K, V> : MemoryCache<K, V>, MemoryTrimmable {

  fun cache(
      key: K,
      valueRef: CloseableReference<V>,
      observer: EntryStateObserver<K>?,
  ): CloseableReference<V>?

  fun reuse(key: K): CloseableReference<V>?

  /**
   * Removes the exclusively owned items until the cache constraints are met.
   *
   * This method invokes the external [CloseableReference#close] method, so it must not be called
   * while holding the `this` lock.
   */
  fun maybeEvictEntries()

  /** Gets the total size in bytes of the cached items that are used by at least one client. */
  val inUseSizeInBytes: Int

  /** Gets the total size in bytes of the cached items that are used by at least one client. */
  val evictionQueueCount: Int

  /** Gets the total size in bytes of the exclusively owned items. */
  val evictionQueueSizeInBytes: Int

  /** Removes all the items from the cache. */
  fun clear()

  val memoryCacheParams: MemoryCacheParams

  val cachedEntries: CountingLruMap<K, Entry<K, V>>?

  val otherEntries: Map<Bitmap, Any>?

  /** Interface used to observe the state changes of an entry. */
  fun interface EntryStateObserver<K> {
    /**
     * Called when the exclusivity status of the entry changes.
     *
     * The item can be reused if it is exclusively owned by the cache.
     */
    fun onExclusivityChanged(key: K, isExclusive: Boolean)
  }

  /** The internal representation of a key-value pair stored by the cache. */
  @VisibleForTesting
  class Entry<K, V>
  private constructor(
      key: K,
      valueRef: CloseableReference<V>,
      @JvmField val observer: EntryStateObserver<K>?,
      @JvmField var size: Int,
  ) {
    @JvmField val key: K = checkNotNull(key)

    @JvmField
    val valueRef: CloseableReference<V> = checkNotNull(CloseableReference.cloneOrNull(valueRef))

    // The number of clients that reference the value.
    @JvmField var clientCount: Int = 0

    // Whether or not this entry is tracked by this cache. Orphans are not tracked by the cache and
    // as soon as the last client of an orphaned entry closes their reference, the entry's copy is
    // closed too.
    @JvmField var isOrphan: Boolean = false

    // automatically generated for Java compatibility, please inline it as soon as possible
    fun setIsOrphan(value: Boolean) {
      this.isOrphan = value
    }

    @JvmField var accessCount: Int = 0

    companion object {
      /** Creates a new entry with the usage count of 0. */
      @JvmStatic
      @VisibleForTesting
      fun <K, V> of(
          key: K,
          valueRef: CloseableReference<V>,
          observer: EntryStateObserver<K>?,
      ): Entry<K, V> = of(key, valueRef, -1, observer)

      /** Creates a new entry with the usage count of 0. */
      @JvmStatic
      @VisibleForTesting
      fun <K, V> of(
          key: K,
          valueRef: CloseableReference<V>,
          size: Int,
          observer: EntryStateObserver<K>?,
      ): Entry<K, V> = CountingMemoryCache.Entry(key, valueRef, observer, size)
    }
  }
}
