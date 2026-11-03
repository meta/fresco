/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import androidx.annotation.VisibleForTesting
import com.facebook.common.internal.Predicate
import javax.annotation.concurrent.GuardedBy
import javax.annotation.concurrent.ThreadSafe

/** Map that keeps track of the elements order (according to the LRU policy) and their size. */
@ThreadSafe
class CountingLruMap<K, V>(private val valueDescriptor: ValueDescriptor<V>) {

  @GuardedBy("this") private val map = LinkedHashMap<K, V>()

  /** Gets the total size in bytes of the elements in the map. */
  @get:Synchronized
  @GuardedBy("this")
  var sizeInBytes: Int = 0
    private set

  @get:Synchronized
  @get:VisibleForTesting
  val keys: ArrayList<K>
    get() = ArrayList(map.keys)

  @get:Synchronized
  @get:VisibleForTesting
  val values: ArrayList<V>
    get() = ArrayList(map.values)

  @get:Synchronized
  val count: Int
    /** Gets the count of the elements in the map. */
    get() = map.size

  @get:Synchronized
  val firstKey: K?
    /** Gets the key of the first element in the map. */
    get() = if (map.isEmpty()) null else map.keys.iterator().next()

  /** Gets the all matching elements. */
  @Synchronized
  fun getMatchingEntries(
      predicate: Predicate<K>?,
  ): ArrayList<MutableMap.MutableEntry<K, V>> {
    val matchingEntries: ArrayList<MutableMap.MutableEntry<K, V>> =
        ArrayList<MutableMap.MutableEntry<K, V>>(map.entries.size)
    for (entry in map.entries) {
      if (predicate == null || predicate.apply(entry.key!!)) {
        matchingEntries.add(entry)
      }
    }
    return matchingEntries
  }

  /** Returns whether the map contains an element with the given key. */
  @Synchronized fun contains(key: K): Boolean = map.containsKey(key)

  /** Gets the element from the map. */
  @Synchronized operator fun get(key: K): V? = map[key]

  /** Adds the element to the map, and removes the old element with the same key if any. */
  @Synchronized
  fun put(key: K, value: V): V? {
    // We do remove and insert instead of just replace, in order to cause a structural change
    // to the map, as we always want the latest inserted element to be last in the queue.
    val oldValue = map.remove(key)
    sizeInBytes -= getValueSizeInBytes(oldValue)
    map[key] = value
    sizeInBytes += getValueSizeInBytes(value)
    return oldValue
  }

  /** Removes the element from the map. */
  @Synchronized
  fun remove(key: K): V? {
    val oldValue = map.remove(key)
    sizeInBytes -= getValueSizeInBytes(oldValue)
    return oldValue
  }

  /** Removes all the matching elements from the map. */
  @Synchronized
  fun removeAll(predicate: Predicate<K>?): ArrayList<V> {
    val oldValues = ArrayList<V>()
    val iterator: MutableIterator<MutableMap.MutableEntry<K, V>> = map.entries.iterator()
    while (iterator.hasNext()) {
      val entry: MutableMap.MutableEntry<K, V> = iterator.next()
      if (predicate == null || predicate.apply(entry.key!!)) {
        oldValues.add(entry.value)
        sizeInBytes -= getValueSizeInBytes(entry.value)
        iterator.remove()
      }
    }
    return oldValues
  }

  /** Clears the map. */
  @Synchronized
  fun clear(): ArrayList<V> {
    val oldValues = ArrayList(map.values)
    map.clear()
    sizeInBytes = 0
    return oldValues
  }

  @Synchronized
  fun resetSize() {
    if (map.isEmpty()) {
      sizeInBytes = 0
    }
  }

  private fun getValueSizeInBytes(value: V?): Int =
      if (value == null) 0 else valueDescriptor.getSizeInBytes(value)
}
