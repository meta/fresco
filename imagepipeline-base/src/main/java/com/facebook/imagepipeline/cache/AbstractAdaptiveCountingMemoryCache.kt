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
import com.facebook.common.internal.Predicate
import com.facebook.common.internal.Supplier
import com.facebook.common.logging.FLog
import com.facebook.common.memory.MemoryTrimType
import com.facebook.common.references.CloseableReference
import com.facebook.common.references.ResourceReleaser
import com.facebook.imagepipeline.cache.CountingMemoryCache.EntryStateObserver
import com.facebook.imagepipeline.cache.MemoryCache.CacheTrimStrategy
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.ThreadSafe
import kotlin.math.max
import kotlin.math.min

/**
 * Layer of memory cache stack responsible for managing eviction of the the cached items.
 *
 * This layer is responsible for Adaptive Replacement Cache (ARC) eviction strategy and for
 * maintaining the size boundaries of the cached items.
 *
 * Only the exclusively owned elements, i.e. the elements not referenced by any client, can be
 * evicted.
 *
 * @param <K> the key type
 * @param <V> the value type </V></K>
 */
@ThreadSafe
abstract class AbstractAdaptiveCountingMemoryCache<K, V>(
    memoryCacheParamsSupplier: Supplier<MemoryCacheParams>,
    cacheTrimStrategy: CacheTrimStrategy,
    valueDescriptor: ValueDescriptor<V>,
    adaptiveRatePromil: Int,
    frequentlyUsedThreshold: Int,
    ghostListMaxSize: Int,
    lfuFractionPromil: Int,
) : CountingMemoryCache<K, V> {

  // Contains the least frequently used items out of all the items in the cache that are not being
  // used by any client and are hence viable for eviction.
  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  val mLeastFrequentlyUsedExclusiveEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>

  // Contains the most frequently used items out of all the items in the cache that are not being
  // used by any client and are hence viable for eviction.
  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  val mMostFrequentlyUsedExclusiveEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>

  // Contains all the cached items including the exclusively owned ones.
  @GuardedBy("this")
  @VisibleForTesting
  val mCachedEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>

  private val valueDescriptor: ValueDescriptor<V>

  private val cacheTrimStrategy: CacheTrimStrategy

  // Cache size constraints.
  private val memoryCacheParamsSupplier: Supplier<MemoryCacheParams>

  // 0 < mLFUFractionPromil < 1000
  // The mLFUFractionPromil/1000 is the the percentage of the cache allocated for the least
  // frequently used values. The rest of the cache, which is the (1 - mLFUFractionPromil/1000) is
  // allocated for the most frequently used values.
  @JvmField @GuardedBy("this") @VisibleForTesting var mLFUFractionPromil: Int = 0

  // Threshold is used to determine if the value is most (or least) frequently used.
  // if the accessCount is less or equals to threshold, the value is considered as a least
  // frequently used. Otherwise, most frequently used.
  private val frequentlyUsedThreshold: Int

  // The learning rate for adapting the cache partitions; this determines how much we
  // increase/decrease the fraction
  @JvmField @GuardedBy("this") @VisibleForTesting val mAdaptiveRatePromil: Int

  // Tracks the most recently evicted keys from the least frequently used cache.
  @JvmField
  @GuardedBy("this")
  @VisibleForTesting
  val mLeastFrequentlyUsedKeysGhostList: IntMapArrayList<K>

  // Tracks the most recently evicted keys from the most frequently used cache.
  @JvmField @GuardedBy("this") @VisibleForTesting val mMostFrequentlyUsedKeysGhostList: ArrayList<K>

  // The maximum size of the ghost lists.
  @JvmField @GuardedBy("this") @VisibleForTesting val mGhostListMaxSize: Int

  internal enum class ArrayListType {
    LFU, // Least Frequently Used
    MFU, // Most Frequently Used
  }

  @GuardedBy("this")
  final override var memoryCacheParams: MemoryCacheParams
    protected set

  @GuardedBy("this") private var lastCacheParamsCheck: Long

  private fun wrapValueDescriptor(
      evictableValueDescriptor: ValueDescriptor<V>,
  ): ValueDescriptor<CountingMemoryCache.Entry<K, V>> {
    return object : ValueDescriptor<CountingMemoryCache.Entry<K, V>> {
      override fun getSizeInBytes(value: CountingMemoryCache.Entry<K, V>): Int =
          evictableValueDescriptor.getSizeInBytes(value.valueRef.get())
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
      cache(key, value, null)

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

    val oldLFUExclusive: CountingMemoryCache.Entry<K, V>?
    val oldMFUExclusive: CountingMemoryCache.Entry<K, V>?
    var oldRefToClose: CloseableReference<V>? = null
    var clientRef: CloseableReference<V>? = null
    synchronized(this) {
      // remove the old item (if any) as it is stale now
      oldLFUExclusive = mLeastFrequentlyUsedExclusiveEntries.remove(key)
      oldMFUExclusive = mMostFrequentlyUsedExclusiveEntries.remove(key)
      val notPresentInBoth = oldLFUExclusive == null || oldMFUExclusive == null
      check(notPresentInBoth)
      val oldEntry = mCachedEntries.remove(key)
      if (oldEntry != null) {
        makeOrphan(oldEntry)
        oldRefToClose = referenceToClose(oldEntry)
      }
      if (canCacheNewValue(valueRef.get())) {
        val newEntry = CountingMemoryCache.Entry.of(key, valueRef, observer)
        // check if the key was recently evicted and restore its state
        val storedAccessCount = mLeastFrequentlyUsedKeysGhostList.getValue(key)
        newEntry.accessCount = storedAccessCount ?: 0
        mCachedEntries.put(key, newEntry)
        clientRef = newClientReference(newEntry)
      }
    }
    CloseableReference.closeSafely(oldRefToClose)
    maybeNotifyExclusiveEntryRemoval(oldLFUExclusive, oldMFUExclusive)

    maybeEvictEntries()
    return clientRef
  }

  /** Checks the cache constraints to determine whether the new value can be cached or not. */
  @Synchronized
  private fun canCacheNewValue(value: V): Boolean {
    val newValueSize = valueDescriptor.getSizeInBytes(value)
    return (newValueSize <= memoryCacheParams.maxCacheEntrySize) &&
        (inUseCount <= memoryCacheParams.maxCacheEntries - 1) &&
        (inUseSizeInBytes <= memoryCacheParams.maxCacheSize - newValueSize)
  }

  /**
   * Gets the item with the given key, or null if there is no such item.
   *
   * It is the caller's responsibility to close the returned reference once not needed anymore.
   */
  override fun get(key: K): CloseableReference<V>? {
    checkNotNull(key)
    val oldLFUExclusive: CountingMemoryCache.Entry<K, V>?
    val oldMFUExclusive: CountingMemoryCache.Entry<K, V>?
    var clientRef: CloseableReference<V>? = null
    synchronized(this) {
      oldLFUExclusive = mLeastFrequentlyUsedExclusiveEntries.remove(key)
      oldMFUExclusive = mMostFrequentlyUsedExclusiveEntries.remove(key)
      val entry = mCachedEntries.get(key)
      if (entry != null) {
        clientRef = newClientReference(entry)
      } else {
        maybeUpdateCacheFraction(key)
      }
    }
    maybeNotifyExclusiveEntryRemoval(oldLFUExclusive, oldMFUExclusive)
    maybeUpdateCacheParams()
    maybeEvictEntries()
    return clientRef
  }

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
    var oldExclusive: CountingMemoryCache.Entry<K, V>?
    synchronized(this) {
      oldExclusive = mLeastFrequentlyUsedExclusiveEntries.remove(key)
      if (oldExclusive == null) {
        oldExclusive = mMostFrequentlyUsedExclusiveEntries.remove(key)
      }
      if (oldExclusive != null) {
        increaseAccessCount(oldExclusive)
        maybeAddToExclusives(oldExclusive)
      }
    }
  }

  /** Changes the relative size of LFU and MFU if necessary and updates the ghost lists. */
  @Synchronized
  private fun maybeUpdateCacheFraction(key: K) {
    if (mLeastFrequentlyUsedKeysGhostList.contains(key)) {
      if (mLFUFractionPromil + mAdaptiveRatePromil <= MAX_FRACTION_PROMIL) {
        // key was recently evicted from LFU, if we had had larger LFU, we would have hit the
        // cache
        mLFUFractionPromil += mAdaptiveRatePromil
      }
      // update the key's accessCount and move it to the youngest position since it should have been
      // accessed by now.
      mLeastFrequentlyUsedKeysGhostList.increaseValueIfExists(key)
    } else if (
        mLFUFractionPromil - mAdaptiveRatePromil >= MIN_FRACTION_PROMIL &&
            mMostFrequentlyUsedKeysGhostList.contains(key)
    ) {
      // key was recently evicted from MFU, if we had had larger MFU, we would have hit the
      // cache
      mLFUFractionPromil -= mAdaptiveRatePromil
    }
  }

  /** Creates a new reference for the client. */
  @Synchronized
  private fun newClientReference(entry: CountingMemoryCache.Entry<K, V>): CloseableReference<V> {
    increaseCounters(entry)
    return CloseableReference.of(
        entry.valueRef.get(),
        object : ResourceReleaser<V> {
          override fun release(unused: V & Any) {
            releaseClientReference(entry)
          }
        },
    )
  }

  /**
   * Adds a key to the ghost list and removes the eldest elemenet if the size of the list is bigger
   * than mGhostListMaxSize
   */
  @Synchronized
  private fun addElementToGhostList(
      key: K,
      accessCount: Int,
      evictionType: ArrayListType,
  ) {
    if (evictionType == ArrayListType.LFU) {
      mLeastFrequentlyUsedKeysGhostList.addPair(key, accessCount)
    } else {
      if (mMostFrequentlyUsedKeysGhostList.size == mGhostListMaxSize) {
        mMostFrequentlyUsedKeysGhostList.removeAt(0)
      }
      mMostFrequentlyUsedKeysGhostList.add(key)
    }
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
      if (entry.accessCount > frequentlyUsedThreshold) {
        mMostFrequentlyUsedExclusiveEntries.put(entry.key, entry)
      } else {
        mLeastFrequentlyUsedExclusiveEntries.put(entry.key, entry)
      }
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
      oldExclusive = mLeastFrequentlyUsedExclusiveEntries.remove(key)
      if (oldExclusive == null) {
        oldExclusive = mMostFrequentlyUsedExclusiveEntries.remove(key)
      }
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
    val oldLFUExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldMFUExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    synchronized(this) {
      oldLFUExclusives = mLeastFrequentlyUsedExclusiveEntries.removeAll(predicate)
      oldMFUExclusives = mMostFrequentlyUsedExclusiveEntries.removeAll(predicate)
      oldEntries = mCachedEntries.removeAll(predicate)
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntriesRemoval(oldLFUExclusives, oldMFUExclusives)
    maybeUpdateCacheParams()
    maybeEvictEntries()
    return oldEntries!!.size
  }

  /** Removes all the items from the cache. */
  override fun clear() {
    val oldLFUExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldMFUExclusives: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    synchronized(this) {
      oldLFUExclusives = mLeastFrequentlyUsedExclusiveEntries.clear()
      oldMFUExclusives = mMostFrequentlyUsedExclusiveEntries.clear()
      oldEntries = mCachedEntries.clear()
      makeOrphans(oldEntries)
    }
    maybeClose(oldEntries)
    maybeNotifyExclusiveEntriesRemoval(oldLFUExclusives, oldMFUExclusives)
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

  /**
   * Trims the cache according to the specified trimming strategy and the given trim type. We first
   * trim the LFU cache, if we need to trim more, we continue to trim the MFU cache.
   */
  override fun trim(trimType: MemoryTrimType) {
    val oldLFUEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldMFUEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val trimRatio = cacheTrimStrategy.getTrimRatio(trimType)
    synchronized(this) {
      val targetCacheSize = (mCachedEntries.sizeInBytes * (1 - trimRatio)).toInt()
      val targetEvictionQueueSize = max(0, targetCacheSize - inUseSizeInBytes)
      var MFUTargetEvictionQueueSize = mMostFrequentlyUsedExclusiveEntries.sizeInBytes
      var LFUTargetEvictionQueueSize = max(0, targetEvictionQueueSize - MFUTargetEvictionQueueSize)
      if (targetEvictionQueueSize <= MFUTargetEvictionQueueSize) {
        MFUTargetEvictionQueueSize = targetEvictionQueueSize
        LFUTargetEvictionQueueSize = 0
      }
      oldLFUEntries =
          trimExclusivelyOwnedEntries(
              Int.Companion.MAX_VALUE,
              LFUTargetEvictionQueueSize,
              mLeastFrequentlyUsedExclusiveEntries,
              ArrayListType.LFU,
          )
      oldMFUEntries =
          trimExclusivelyOwnedEntries(
              Int.Companion.MAX_VALUE,
              MFUTargetEvictionQueueSize,
              mMostFrequentlyUsedExclusiveEntries,
              ArrayListType.MFU,
          )
      makeOrphans(oldLFUEntries, oldMFUEntries)
    }
    maybeClose(oldLFUEntries, oldMFUEntries)
    maybeNotifyExclusiveEntriesRemoval(oldLFUEntries, oldMFUEntries)
    maybeUpdateCacheParams()
    maybeEvictEntries()
  }

  /** Updates the cache params (constraints) if enough time has passed since the last update. */
  @Synchronized
  private fun maybeUpdateCacheParams() {
    if (
        lastCacheParamsCheck + memoryCacheParams.paramsCheckIntervalMs > SystemClock.uptimeMillis()
    ) {
      return
    }
    lastCacheParamsCheck = SystemClock.uptimeMillis()
    memoryCacheParams =
        checkNotNull(memoryCacheParamsSupplier.get()) { "mMemoryCacheParamsSupplier returned null" }
  }

  /**
   * Removes the exclusively owned items until the cache constraints are met.
   *
   * This method invokes the external [CloseableReference#close] method, so it must not be called
   * while holding the `this` lock.
   */
  override fun maybeEvictEntries() {
    val oldLFUEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    val oldMFUEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?
    synchronized(this) {
      val maxCount = min(
          memoryCacheParams.maxEvictionQueueEntries,
          memoryCacheParams.maxCacheEntries - inUseCount,
      )
      val maxSize = min(
          memoryCacheParams.maxEvictionQueueSize,
          memoryCacheParams.maxCacheSize - inUseSizeInBytes,
      )
      val LFUMaxCount =
          (maxCount.toLong() * mLFUFractionPromil /
                  AbstractAdaptiveCountingMemoryCache.TOTAL_PROMIL)
              .toInt()
      val LFUMaxSize =
          (maxSize.toLong() * mLFUFractionPromil / AbstractAdaptiveCountingMemoryCache.TOTAL_PROMIL)
              .toInt()
      oldLFUEntries =
          trimExclusivelyOwnedEntries(
              LFUMaxCount,
              LFUMaxSize,
              mLeastFrequentlyUsedExclusiveEntries,
              ArrayListType.LFU,
          )
      oldMFUEntries =
          trimExclusivelyOwnedEntries(
              maxCount - LFUMaxCount,
              maxSize - LFUMaxSize,
              mMostFrequentlyUsedExclusiveEntries,
              ArrayListType.MFU,
          )
      makeOrphans(oldLFUEntries, oldMFUEntries)
    }
    maybeClose(oldLFUEntries, oldMFUEntries)
    maybeNotifyExclusiveEntriesRemoval(oldLFUEntries, oldMFUEntries)
  }

  /**
   * Removes the exclusively owned, least recently used items until there is at most `count ` * of
   * them and they occupy no more than `size` bytes.
   *
   * This method returns the removed least frequently used items instead of actually closing them,
   * so it is safe to be called while holding the `this` lock.
   */
  @Synchronized
  private fun trimExclusivelyOwnedEntries(
      count: Int,
      size: Int,
      ExclusixeEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>,
      evictionType: ArrayListType,
  ): ArrayList<CountingMemoryCache.Entry<K, V>>? {
    var count = count
    var size = size
    count = max(count, 0)
    size = max(size, 0)
    // fast path without array allocation if no eviction is necessary
    if (ExclusixeEntries.count <= count && ExclusixeEntries.sizeInBytes <= size) {
      return null
    }
    val oldEntries = ArrayList<CountingMemoryCache.Entry<K, V>>()
    while (ExclusixeEntries.count > count || ExclusixeEntries.sizeInBytes > size) {
      val key: K = checkNotNull(ExclusixeEntries.firstKey)
      addElementToGhostList(
          key,
          checkNotNull(ExclusixeEntries.get(key)).accessCount,
          evictionType,
      )
      ExclusixeEntries.remove(key)
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
  private fun maybeClose(
      oldEntries1: ArrayList<CountingMemoryCache.Entry<K, V>>?,
      oldEntries2: ArrayList<CountingMemoryCache.Entry<K, V>>?,
  ) {
    maybeClose(oldEntries1)
    maybeClose(oldEntries2)
  }

  private fun maybeClose(oldEntries: ArrayList<CountingMemoryCache.Entry<K, V>>?) {
    if (oldEntries != null) {
      for (oldEntry in oldEntries) {
        CloseableReference.closeSafely(referenceToClose(oldEntry))
      }
    }
  }

  private fun maybeNotifyExclusiveEntriesRemoval(
      entries1: ArrayList<CountingMemoryCache.Entry<K, V>>?,
      entries2: ArrayList<CountingMemoryCache.Entry<K, V>>?,
  ) {
    maybeNotifyExclusiveEntryRemoval(entries1)
    maybeNotifyExclusiveEntryRemoval(entries2)
  }

  private fun maybeNotifyExclusiveEntryRemoval(
      entry1: CountingMemoryCache.Entry<K, V>?,
      entry2: CountingMemoryCache.Entry<K, V>?,
  ) {
    maybeNotifyExclusiveEntryRemoval(entry1)
    maybeNotifyExclusiveEntryRemoval(entry2)
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
  private fun makeOrphans(
      oldEntries1: ArrayList<CountingMemoryCache.Entry<K, V>>?,
      oldEntries2: ArrayList<CountingMemoryCache.Entry<K, V>>?,
  ) {
    makeOrphans(oldEntries1)
    makeOrphans(oldEntries2)
  }

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

  /** Increases the entry's client count and access count. */
  @Synchronized
  private fun increaseCounters(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    check(!entry.isOrphan)
    entry.clientCount++
    increaseAccessCount(entry)
  }

  /** Increases the entry's access count. */
  @Synchronized
  private fun increaseAccessCount(entry: CountingMemoryCache.Entry<K, V>) {
    checkNotNull(entry)
    check(!entry.isOrphan)
    entry.accessCount++
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
    get() =
        (mCachedEntries.count -
            mLeastFrequentlyUsedExclusiveEntries.count -
            mMostFrequentlyUsedExclusiveEntries.count)

  @get:Synchronized
  override val inUseSizeInBytes: Int
    /** Gets the total size in bytes of the cached items that are used by at least one client. */
    get() =
        (mCachedEntries.sizeInBytes -
            mLeastFrequentlyUsedExclusiveEntries.sizeInBytes -
            mMostFrequentlyUsedExclusiveEntries.sizeInBytes)

  @get:Synchronized
  override val evictionQueueCount: Int
    /** Gets the number of the exclusively owned items. */
    get() = (mLeastFrequentlyUsedExclusiveEntries.count + mMostFrequentlyUsedExclusiveEntries.count)

  @get:Synchronized
  override val evictionQueueSizeInBytes: Int
    /** Gets the total size in bytes of the exclusively owned items. */
    get() =
        (mLeastFrequentlyUsedExclusiveEntries.sizeInBytes +
            mMostFrequentlyUsedExclusiveEntries.sizeInBytes)

  /**
   * Mapping between E element and Integer value, implemented by using two bounded ArrayList. Each
   * pair is inserted, updated and removed together.
   */
  @VisibleForTesting
  inner class IntMapArrayList<E>(private val maxCapacity: Int) {
    private val firstList = ArrayList<E>(maxCapacity)
    private val secondList = ArrayList<Int>(maxCapacity)

    /**
     * When adding a new element, and the array size exceeds the mMaxCapccity, we remove the eldest
     * element and only then add the new element.
     */
    fun addPair(element: E, second: Int) {
      if (firstList.size == maxCapacity) {
        firstList.removeAt(0)
        secondList.removeAt(0)
      }
      firstList.add(element)
      secondList.add(second)
    }

    /** If the element exists in the List, increase its value and put it in the youngest position */
    fun increaseValueIfExists(element: E) {
      val index = firstList.indexOf(element)
      if (index < 0) {
        return
      }
      val newInt = secondList[index] + 1
      if (index == maxCapacity - 1) {
        secondList[maxCapacity - 1] = newInt
        return
      }
      firstList.removeAt(index)
      secondList.removeAt(index)
      firstList.add(element)
      secondList.add(newInt)
    }

    /** returns the value mapped to the passed element if it exists. Otherwise, returns null. */
    fun getValue(element: E): Int? {
      val index = firstList.indexOf(element)
      if (index < 0) {
        return null
      }
      return secondList[index]
    }

    fun contains(element: E): Boolean = firstList.contains(element)

    fun size(): Int = firstList.size
  }

  protected abstract fun logIllegalLfuFraction()

  protected abstract fun logIllegalAdaptiveRate()

  override val cachedEntries: CountingLruMap<K, CountingMemoryCache.Entry<K, V>>?
    get() = mCachedEntries

  override val otherEntries: Map<Bitmap, Any>?
    get() = emptyMap()

  init {
    FLog.d(TAG, "Create Adaptive Replacement Cache")
    this.valueDescriptor = valueDescriptor
    mLeastFrequentlyUsedExclusiveEntries =
        CountingLruMap<K, CountingMemoryCache.Entry<K, V>>(wrapValueDescriptor(valueDescriptor))
    mMostFrequentlyUsedExclusiveEntries =
        CountingLruMap<K, CountingMemoryCache.Entry<K, V>>(wrapValueDescriptor(valueDescriptor))
    mCachedEntries =
        CountingLruMap<K, CountingMemoryCache.Entry<K, V>>(wrapValueDescriptor(valueDescriptor))
    this.cacheTrimStrategy = cacheTrimStrategy
    this.memoryCacheParamsSupplier = memoryCacheParamsSupplier
    memoryCacheParams =
        checkNotNull(this.memoryCacheParamsSupplier.get()) {
          "mMemoryCacheParamsSupplier returned null"
        }
    lastCacheParamsCheck = SystemClock.uptimeMillis()
    this.frequentlyUsedThreshold = frequentlyUsedThreshold
    this.mGhostListMaxSize = ghostListMaxSize
    mLeastFrequentlyUsedKeysGhostList = IntMapArrayList(this.mGhostListMaxSize)
    mMostFrequentlyUsedKeysGhostList = ArrayList(this.mGhostListMaxSize)
    if (lfuFractionPromil < MIN_FRACTION_PROMIL || lfuFractionPromil > MAX_FRACTION_PROMIL) {
      this.mLFUFractionPromil = AbstractAdaptiveCountingMemoryCache.DEFAULT_LFU_FRACTION_PROMIL
      logIllegalLfuFraction()
    } else {
      this.mLFUFractionPromil = lfuFractionPromil
    }
    if (adaptiveRatePromil <= 0 || adaptiveRatePromil >= TOTAL_PROMIL) {
      this.mAdaptiveRatePromil = AbstractAdaptiveCountingMemoryCache.DEFAULT_ADAPTIVE_RATE_PROMIL
      logIllegalAdaptiveRate()
    } else {
      this.mAdaptiveRatePromil = adaptiveRatePromil
    }
  }

  companion object {
    private const val TAG = "AbstractArcCountingMemoryCache"

    // default LFU Fraction
    const val DEFAULT_LFU_FRACTION_PROMIL: Int = 500

    // These constants are used to define the smallest LFU/MFU fraction sizes.
    // considering the cache is partitioned [Cache] = [..LFU..|..MFU..]: LFU between 0 and N, MFU
    // between N and 1000.
    const val TOTAL_PROMIL: Int = 1_000

    @VisibleForTesting const val MIN_FRACTION_PROMIL: Int = 100
    const val MAX_FRACTION_PROMIL: Int = TOTAL_PROMIL - MIN_FRACTION_PROMIL

    // defualt adaptive rate
    const val DEFAULT_ADAPTIVE_RATE_PROMIL: Int = 10

    private fun <K, V> maybeNotifyExclusiveEntryRemoval(entry: CountingMemoryCache.Entry<K, V>?) {
      if (entry?.observer != null) {
        entry.observer.onExclusivityChanged(entry.key, false)
      }
    }

    private fun <K, V> maybeNotifyExclusiveEntryInsertion(
        entry: CountingMemoryCache.Entry<K, V>?,
    ) {
      if (entry?.observer != null) {
        entry.observer.onExclusivityChanged(entry.key, true)
      }
    }
  }
}
