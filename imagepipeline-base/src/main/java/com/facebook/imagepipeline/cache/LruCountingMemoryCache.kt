/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.facebook.cache.common.HasDebugData
import com.facebook.common.internal.Objects
import com.facebook.common.internal.Predicate
import com.facebook.common.internal.Supplier
import com.facebook.common.memory.MemoryTrimType
import com.facebook.common.references.CloseableReference
import com.facebook.common.references.ResourceReleaser
import com.facebook.imagepipeline.cache.CountingMemoryCache.EntryStateObserver
import com.facebook.imagepipeline.cache.MemoryCache.CacheTrimStrategy
import java.util.WeakHashMap
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.ThreadSafe
import kotlin.math.max
import kotlin.math.min

/**
 * Layer of memory cache stack responsible for managing eviction of the the cached items.
 *
 * This layer is responsible for LRU eviction strategy and for maintaining the size boundaries of
 * the cached items.
 *
 * Only the exclusively owned elements, i.e. the elements not referenced by any client, can be
 * evicted.
 *
 * @param <K> the key type
 * @param <V> the value type </V></K>
 */
@ThreadSafe
open class LruCountingMemoryCache<K : Any, V : Any>(
    private val valueDescriptor: ValueDescriptor<V>,
    cacheTrimStrategy: CacheTrimStrategy,
    memoryCacheParamsSupplier: Supplier<MemoryCacheParams>,
    entryStateObserver: EntryStateObserver<K>?,
    storeEntrySize: Boolean,
    ignoreSizeMismatch: Boolean,
) : CountingMemoryCache<K, V>, MemoryCache<K, V>, MemoryCacheWithExactKeyRemoval<K>, HasDebugData {

  private val entryStateObserver: EntryStateObserver<K>?

  // Contains the items that are not being used by any client and are hence viable for eviction.
  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  val mExclusiveEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>> = CountingLruMap(
      wrapValueDescriptor(
          valueDescriptor,
      ),
  )

  // Contains all the cached items including the exclusively owned ones.
  @GuardedBy("this")
  @VisibleForTesting
  val mCachedEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>> = CountingLruMap(
      wrapValueDescriptor(
          valueDescriptor,
      ),
  )

  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  val mOtherEntries: Map<Bitmap, Any> = WeakHashMap()

  private val cacheTrimStrategy: CacheTrimStrategy

  // Cache size constraints.
  private val memoryCacheParamsSupplier: Supplier<MemoryCacheParams>

  @GuardedBy("this") internal var mMemoryCacheParams: MemoryCacheParams

  @GuardedBy("this") private var lastCacheParamsCheck: Long

  private val storeEntrySize: Boolean
  private val ignoreSizeMismatch: Boolean

  private fun wrapValueDescriptor(
      evictableValueDescriptor: ValueDescriptor<V>,
  ): ValueDescriptor<CountingMemoryCache.Entry<K, V>> {
    return object : ValueDescriptor<CountingMemoryCache.Entry<K, V>> {
      override fun getSizeInBytes(value: CountingMemoryCache.Entry<K, V>): Int {
        if (storeEntrySize) {
          return value.size
        }
        return evictableValueDescriptor.getSizeInBytes(value.valueRef.get())
      }
    }
  }

  /**
   * Caches the given key-value pair.
   *
   * Important: the client should use the returned reference instead of the original one. It is the
   * caller's responsibility to close the returned reference once not needed anymore.
   *
   * @return the new reference to be used, null if the value cannot be cached
   */
  override fun cache(key: K, value: CloseableReference<V>): CloseableReference<V>? =
      cache(key, value, entryStateObserver)

  /**
   * Caches the given key-value pair.
   *
   * Important: the client should use the returned reference instead of the original one. It is the
   * caller's responsibility to close the returned reference once not needed anymore.
   *
   * @return the new reference to be used, null if the value cannot be cached
   */
  override fun cache(
      key: K,
      valueRef: CloseableReference<V>,
      observer: EntryStateObserver<K>?,
  ): CloseableReference<V>? {
    checkNotNull(key)
    checkNotNull(valueRef)

    maybeUpdateCacheParams()

    val oldExclusive: CountingMemoryCache.Entry<K, V>?
    var oldRefToClose: CloseableReference<V>? = null
    var clientRef: CloseableReference<V>? = null
    synchronized(this) {
      // remove the old item (if any) as it is stale now
      oldExclusive = mExclusiveEntries.remove(key)
      val oldEntry = mCachedEntries.remove(key)
      if (oldEntry != null) {
        makeOrphan(oldEntry)
        oldRefToClose = referenceToClose(oldEntry)
      }

      val value = valueRef.get()
      val size = valueDescriptor.getSizeInBytes(value)
      if (canCacheNewValueOfSize(size)) {
        val newEntry =
            if (storeEntrySize) {
              CountingMemoryCache.Entry.of(key, valueRef, size, observer)
            } else {
              CountingMemoryCache.Entry.of(key, valueRef, observer)
            }
        mCachedEntries.put(key, newEntry)
        clientRef = newClientReference(newEntry)
      }
    }
    CloseableReference.closeSafely(oldRefToClose)
    maybeNotifyExclusiveEntryRemoval(oldExclusive)

    maybeEvictEntries()
    return clientRef
  }

  /**
   * Checks the cache constraints to determine whether the new value of given size can be cached or
   * not.
   */
  @Synchronized
  private fun canCacheNewValueOfSize(newValueSize: Int): Boolean =
      (newValueSize <= mMemoryCacheParams.maxCacheEntrySize) &&
          (inUseCount <= mMemoryCacheParams.maxCacheEntries - 1) &&
          (inUseSizeInBytes <= mMemoryCacheParams.maxCacheSize - newValueSize)

  /**
   * Gets the item with the given key, or null if there is no such item.
   *
   * It is the caller's responsibility to close the returned reference once not needed anymore.
   */
  override fun get(key: K): CloseableReference<V>? {
    checkNotNull(key)
    val oldExclusive: CountingMemoryCache.Entry<K, V>?
    var clientRef: CloseableReference<V>? = null
    synchronized(this) {
      oldExclusive = mExclusiveEntries.remove(key)
      val entry = mCachedEntries.get(key)
      if (entry != null) {
        clientRef = newClientReference(entry)
      }
    }
    maybeNotifyExclusiveEntryRemoval(oldExclusive)
    maybeUpdateCacheParams()
    maybeEvictEntries()
    return clientRef
  }

  @Synchronized
  override fun inspect(key: K): V? {
    val entry = mCachedEntries.get(key) ?: return null
    return entry.valueRef.get()
  }

  /**
   * Probes whether the object corresponding to the key is in the cache. Note that the act of
   * probing touches the item (if present in cache), thus changing its LRU timestamp.
   */
  override fun probe(key: K) {
    checkNotNull(key)
    val oldExclusive: CountingMemoryCache.Entry<K, V>?
    synchronized(this) {
      oldExclusive = mExclusiveEntries.remove(key)
      if (oldExclusive != null) {
        mExclusiveEntries.put(key, oldExclusive)
      }
    }
  }

  /** Creates a new reference for the client. */
  @Synchronized
  private fun newClientReference(entry: CountingMemoryCache.Entry<K, V>): CloseableReference<V> {
    increaseClientCount(entry)
    return CloseableReference.of(
        entry.valueRef.get(),
        object : ResourceReleaser<V> {
          override fun release(unused: V) {
            releaseClientReference(entry)
          }
        },
    )
  }

  /** Called when the client closes its reference. */
  private fun releaseClientReference(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    val isExclusiveAdded: Boolean
    val oldRefToClose: CloseableReference<V>?
    synchronized(this) {
      decreaseClientCount(entry)
      isExclusiveAdded = maybeAddToExclusives(entry)
      oldRefToClose = referenceToClose(entry)
    }
    CloseableReference.closeSafely(oldRefToClose)
    maybeNotifyExclusiveEntryInsertion(if (isExclusiveAdded) entry else null)
    maybeUpdateCacheParams()
    maybeEvictEntries()
  }

  /** Adds the entry to the exclusively owned queue if it is viable for eviction. */
  @Synchronized
  private fun maybeAddToExclusives(entry: CountingMemoryCache.Entry<K, V>): Boolean {
    if (!entry.isOrphan && entry.clientCount == 0) {
      mExclusiveEntries.put(entry.key, entry)
      return true
    }
    return false
  }

  /**
   * Gets the value with the given key to be reused, or null if there is no such value.
   *
   * The item can be reused only if it is exclusively owned by the cache.
   */
  override fun reuse(key: K): CloseableReference<V>? {
    checkNotNull(key)
    var clientRef: CloseableReference<V>? = null
    var removed = false
    var oldExclusive: CountingMemoryCache.Entry<K, V>? = null
    synchronized(this) {
      oldExclusive = mExclusiveEntries.remove(key)
      if (oldExclusive != null) {
        val entry = mCachedEntries.remove(key)
        checkNotNull(entry)
        check(entry.clientCount == 0)
        // optimization: instead of cloning and then closing the original reference,
        // we just do a move
        clientRef = entry.valueRef
        removed = true
      }
    }
    if (removed) {
      maybeNotifyExclusiveEntryRemoval(oldExclusive)
    }
    return clientRef
  }

  /**
   * Removes all the items from the cache whose key matches the specified predicate.
   *
   * @param predicate returns true if an item with the given key should be removed
   * @return number of the items removed from the cache
   */
  override fun removeAll(predicate: Predicate<K>): Int {
    val oldExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>
    synchronized(this) {
      oldExclusives = mExclusiveEntries.removeAll(predicate)
      oldEntries = mCachedEntries.removeAll(predicate)
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntryRemoval(oldExclusives)
    maybeUpdateCacheParams()
    maybeEvictEntries()
    return oldEntries.size
  }

  override fun remove(key: K): Int {
    checkNotNull(key)
    val oldExclusive: CountingMemoryCache.Entry<K, V>?
    val oldEntry: CountingMemoryCache.Entry<K, V>?
    var oldRefToClose: CloseableReference<V>? = null
    synchronized(this) {
      oldExclusive = mExclusiveEntries.remove(key)
      oldEntry = mCachedEntries.remove(key)
      if (oldEntry != null) {
        makeOrphan(oldEntry)
        oldRefToClose = referenceToClose(oldEntry)
      }
    }
    CloseableReference.closeSafely(oldRefToClose)
    maybeNotifyExclusiveEntryRemoval(oldExclusive)
    maybeUpdateCacheParams()
    maybeEvictEntries()
    return if (oldEntry == null) 0 else 1
  }

  /** Removes all the items from the cache. */
  override fun clear() {
    val oldExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>
    synchronized(this) {
      oldExclusives = mExclusiveEntries.clear()
      oldEntries = mCachedEntries.clear()
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntryRemoval(oldExclusives)
    maybeUpdateCacheParams()
  }

  /**
   * Check if any items from the cache whose key matches the specified predicate.
   *
   * @param predicate returns true if an item with the given key matches
   * @return true is any items matches from the cache
   */
  @Synchronized
  override fun contains(predicate: Predicate<K>): Boolean =
      !mCachedEntries.getMatchingEntries(predicate).isEmpty()

  /**
   * Check if an item with the given cache key is currently in the cache.
   *
   * @param key returns true if an item with the given key matches
   * @return true is any items matches from the cache
   */
  @Synchronized override fun contains(key: K): Boolean = mCachedEntries.contains(key)

  /** Trims the cache according to the specified trimming strategy and the given trim type. */
  override fun trim(trimType: MemoryTrimType) {
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val trimRatio = cacheTrimStrategy.getTrimRatio(trimType)
    synchronized(this) {
      val targetCacheSize = (mCachedEntries.sizeInBytes * (1 - trimRatio)).toInt()
      val targetEvictionQueueSize = max(0, targetCacheSize - inUseSizeInBytes)
      oldEntries = trimExclusivelyOwnedEntries(Int.Companion.MAX_VALUE, targetEvictionQueueSize)
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntryRemoval(oldEntries)
    maybeUpdateCacheParams()
    maybeEvictEntries()
  }

  /** Updates the cache params (constraints) if enough time has passed since the last update. */
  @Synchronized
  private fun maybeUpdateCacheParams() {
    if (
        lastCacheParamsCheck + mMemoryCacheParams.paramsCheckIntervalMs > SystemClock.uptimeMillis()
    ) {
      return
    }
    lastCacheParamsCheck = SystemClock.uptimeMillis()
    mMemoryCacheParams =
        checkNotNull(memoryCacheParamsSupplier.get()) { "mMemoryCacheParamsSupplier returned null" }
  }

  override val memoryCacheParams: MemoryCacheParams
    get() = mMemoryCacheParams

  override val cachedEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>?
    get() = mCachedEntries

  override val otherEntries: Map<Bitmap, Any>?
    get() = mOtherEntries

  /**
   * Removes the exclusively owned items until the cache constraints are met.
   *
   * This method invokes the external [CloseableReference#close] method, so it must not be called
   * while holding the `this` lock.
   */
  override fun maybeEvictEntries() {
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    synchronized(this) {
      val maxCount = min(
          mMemoryCacheParams.maxEvictionQueueEntries,
          mMemoryCacheParams.maxCacheEntries - inUseCount,
      )
      val maxSize = min(
          mMemoryCacheParams.maxEvictionQueueSize,
          mMemoryCacheParams.maxCacheSize - inUseSizeInBytes,
      )
      oldEntries = trimExclusivelyOwnedEntries(maxCount, maxSize)
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntryRemoval(oldEntries)
  }

  /**
   * Removes the exclusively owned items until there is at most `count` of them and they occupy no
   * more than `size` bytes.
   *
   * This method returns the removed items instead of actually closing them, so it is safe to be
   * called while holding the `this` lock.
   */
  @Synchronized
  private fun trimExclusivelyOwnedEntries(
      count: Int,
      size: Int,
  ): ArrayList<CountingMemoryCache.Entry<K, V>>? {
    var count = count
    var size = size
    count = max(count, 0)
    size = max(size, 0)
    // fast path without array allocation if no eviction is necessary
    if (mExclusiveEntries.count <= count && mExclusiveEntries.sizeInBytes <= size) {
      return null
    }
    val oldEntries = ArrayList<CountingMemoryCache.Entry<K, V>>()
    while (mExclusiveEntries.count > count || mExclusiveEntries.sizeInBytes > size) {
      val key = mExclusiveEntries.firstKey
      if (key == null) {
        if (ignoreSizeMismatch) {
          mExclusiveEntries.resetSize()
          break
        }
        throw IllegalStateException(
            String.format(
                "key is null, but exclusiveEntries count: %d, size: %d",
                mExclusiveEntries.count,
                mExclusiveEntries.sizeInBytes,
            ),
        )
      }
      mExclusiveEntries.remove(key)
      oldEntries.add(mCachedEntries.remove(key)!!)
    }
    return oldEntries
  }

  /**
   * Notifies the client that the cache no longer tracks the given items.
   *
   * This method invokes the external [CloseableReference#close] method, so it must not be called
   * while holding the `this` lock.
   */
  private fun maybeClose(oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?) {
    if (oldEntries != null) {
      for (oldEntry in oldEntries) {
        CloseableReference.closeSafely(referenceToClose(oldEntry))
      }
    }
  }

  private fun maybeNotifyExclusiveEntryRemoval(
      entries: ArrayList<CountingMemoryCache.Entry<K, V>>?,
  ) {
    if (entries != null) {
      for (entry in entries) {
        maybeNotifyExclusiveEntryRemoval(entry)
      }
    }
  }

  /** Marks the given entries as orphans. */
  @Synchronized
  private fun makeOrphans(oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?) {
    if (oldEntries != null) {
      for (oldEntry in oldEntries) {
        makeOrphan(oldEntry)
      }
    }
  }

  /** Marks the entry as orphan. */
  @Synchronized
  private fun makeOrphan(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    check(!entry.isOrphan)
    entry.isOrphan = true
  }

  /** Increases the entry's client count. */
  @Synchronized
  private fun increaseClientCount(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    check(!entry.isOrphan)
    entry.clientCount++
  }

  /** Decreases the entry's client count. */
  @Synchronized
  private fun decreaseClientCount(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    check(entry.clientCount > 0)
    entry.clientCount--
  }

  /** Returns the value reference of the entry if it should be closed, null otherwise. */
  @Synchronized
  private fun referenceToClose(entry: CountingMemoryCache.Entry<K, V>): CloseableReference<V>? {
    checkNotNull(entry)
    return if (entry.isOrphan && entry.clientCount == 0) entry.valueRef else null
  }

  @get:Synchronized
  override val count: Int
    /** Gets the total number of all currently cached items. */
    get() = mCachedEntries.count

  @get:Synchronized
  override val sizeInBytes: Int
    /** Gets the total size in bytes of all currently cached items. */
    get() = mCachedEntries.sizeInBytes

  @get:Synchronized
  val inUseCount: Int
    /** Gets the number of the cached items that are used by at least one client. */
    get() = mCachedEntries.count - mExclusiveEntries.count

  @get:Synchronized
  override val inUseSizeInBytes: Int
    /** Gets the total size in bytes of the cached items that are used by at least one client. */
    get() = mCachedEntries.sizeInBytes - mExclusiveEntries.sizeInBytes

  @get:Synchronized
  override val evictionQueueCount: Int
    /** Gets the number of the exclusively owned items. */
    get() = mExclusiveEntries.count

  @get:Synchronized
  override val evictionQueueSizeInBytes: Int
    /** Gets the total size in bytes of the exclusively owned items. */
    get() = mExclusiveEntries.sizeInBytes

  @get:Synchronized
  override val debugData: String?
    get() =
        Objects.toStringHelper("CountingMemoryCache")
            .add("cached_entries_count", mCachedEntries.count)
            .add("cached_entries_size_bytes", mCachedEntries.sizeInBytes)
            .add("exclusive_entries_count", mExclusiveEntries.count)
            .add("exclusive_entries_size_bytes", mExclusiveEntries.sizeInBytes)
            .toString()

  init {

    this.cacheTrimStrategy = cacheTrimStrategy
    this.memoryCacheParamsSupplier = memoryCacheParamsSupplier
    mMemoryCacheParams =
        checkNotNull(this.memoryCacheParamsSupplier.get()) {
          "mMemoryCacheParamsSupplier returned null"
        }
    lastCacheParamsCheck = SystemClock.uptimeMillis()
    this.entryStateObserver = entryStateObserver
    this.storeEntrySize = storeEntrySize
    this.ignoreSizeMismatch = ignoreSizeMismatch
  }

  companion object {
    private fun <K : Any, V> maybeNotifyExclusiveEntryRemoval(
        entry: CountingMemoryCache.Entry<K, V>?,
    ) {
      if (entry?.observer != null) {
        entry.observer.onExclusivityChanged(entry.key, false)
      }
    }

    private fun <K : Any, V> maybeNotifyExclusiveEntryInsertion(
        entry: CountingMemoryCache.Entry<K, V>?,
    ) {
      if (entry?.observer != null) {
        entry.observer.onExclusivityChanged(entry.key, true)
      }
    }
  }
}
