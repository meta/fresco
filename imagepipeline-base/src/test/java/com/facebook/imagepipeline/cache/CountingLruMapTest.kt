/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import com.facebook.common.internal.Predicate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CountingLruMapTest {
  private lateinit var countingLruMap: CountingLruMap<String?, Int?>

  @Before
  fun setUp() {
    val valueDescriptor: ValueDescriptor<Int?> =
        object : ValueDescriptor<Int?> {
          override fun getSizeInBytes(value: Int?): Int {
            return value ?: 0
          }
        }
    countingLruMap = CountingLruMap<String?, Int?>(valueDescriptor)
  }

  @Test
  fun testInitialState() {
    assertThat(countingLruMap.count).isEqualTo(0)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(0)
  }

  @Test
  fun testPut() {
    // last inserted element should be last in the queue
    countingLruMap.put("key1", 110)
    assertThat(countingLruMap.count).isEqualTo(1)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(110)
    assertKeyOrder("key1")
    assertValueOrder(110)

    countingLruMap.put("key2", 120)
    assertThat(countingLruMap.count).isEqualTo(2)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(230)
    assertKeyOrder("key1", "key2")
    assertValueOrder(110, 120)

    countingLruMap.put("key3", 130)
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)
  }

  @Test
  fun testPut_SameKeyTwice() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)

    // last inserted element should be last in the queue
    countingLruMap.put("key2", 150)
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(390)
    assertKeyOrder("key1", "key3", "key2")
    assertValueOrder(110, 130, 150)
  }

  @Test
  fun testGet() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)

    // get shouldn't affect the ordering, nor the size
    assertThat(countingLruMap.get("key2")).isEqualTo(120)
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.get("key1")).isEqualTo(110)
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.get("key4")).isNull()
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.get("key3")).isEqualTo(130)
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)
  }

  @Test
  fun testContains() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)

    // contains shouldn't affect the ordering, nor the size
    assertThat(countingLruMap.contains("key2")).isTrue()
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.contains("key1")).isTrue()
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.contains("key4")).isFalse()
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)

    assertThat(countingLruMap.contains("key3")).isTrue()
    assertThat(countingLruMap.count).isEqualTo(3)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(360)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)
  }

  @Test
  fun testRemove() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)

    assertThat(countingLruMap.remove("key2")).isEqualTo(120)
    assertThat(countingLruMap.count).isEqualTo(2)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(240)
    assertKeyOrder("key1", "key3")
    assertValueOrder(110, 130)

    assertThat(countingLruMap.remove("key3")).isEqualTo(130)
    assertThat(countingLruMap.count).isEqualTo(1)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(110)
    assertKeyOrder("key1")
    assertValueOrder(110)

    assertThat(countingLruMap.remove("key4")).isNull()
    assertThat(countingLruMap.count).isEqualTo(1)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(110)
    assertKeyOrder("key1")
    assertValueOrder(110)

    assertThat(countingLruMap.remove("key1")).isEqualTo(110)
    assertThat(countingLruMap.count).isEqualTo(0)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(0)
    assertKeyOrder()
    assertValueOrder()
  }

  @Test
  fun testRemoveAll() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)
    countingLruMap.put("key4", 140)

    countingLruMap.removeAll(
        object : Predicate<String?> {
          override fun apply(key: String): Boolean {
            return key == "key2" || key == "key3"
          }
        },
    )
    assertThat(countingLruMap.count).isEqualTo(2)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(250)
    assertKeyOrder("key1", "key4")
    assertValueOrder(110, 140)
  }

  @Test
  fun testClear() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)
    countingLruMap.put("key4", 140)

    countingLruMap.clear()
    assertThat(countingLruMap.count).isEqualTo(0)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(0)
    assertKeyOrder()
    assertValueOrder()
  }

  @Test
  fun testGetMatchingEntries() {
    countingLruMap.put("key1", 110)
    countingLruMap.put("key2", 120)
    countingLruMap.put("key3", 130)
    countingLruMap.put("key4", 140)

    val entries: MutableList<MutableMap.MutableEntry<String?, Int?>> =
        countingLruMap.getMatchingEntries(
            object : Predicate<String?> {
              override fun apply(key: String): Boolean {
                return key == "key2" || key == "key3"
              }
            },
        )
    assertThat(entries).isNotNull()
    assertThat(entries.size).isEqualTo(2)
    assertThat(entries[0]?.key).isEqualTo("key2")
    assertThat(entries[0]?.value).isEqualTo(120)
    assertThat(entries[1]?.key).isEqualTo("key3")
    assertThat(entries[1]?.value).isEqualTo(130)
    // getting entries should not affect the order nor the size
    assertThat(countingLruMap.count).isEqualTo(4)
    assertThat(countingLruMap.sizeInBytes).isEqualTo(500)
    assertKeyOrder("key1", "key2", "key3", "key4")
    assertValueOrder(110, 120, 130, 140)
  }

  @Test
  fun testGetFirstKey() {
    countingLruMap.put("key1", 110)
    assertKeyOrder("key1")
    assertValueOrder(110)
    assertThat(countingLruMap.firstKey).isEqualTo("key1")

    countingLruMap.put("key2", 120)
    assertKeyOrder("key1", "key2")
    assertValueOrder(110, 120)
    assertThat(countingLruMap.firstKey).isEqualTo("key1")

    countingLruMap.put("key3", 130)
    assertKeyOrder("key1", "key2", "key3")
    assertValueOrder(110, 120, 130)
    assertThat(countingLruMap.firstKey).isEqualTo("key1")

    countingLruMap.put("key1", 140)
    assertKeyOrder("key2", "key3", "key1")
    assertValueOrder(120, 130, 140)
    assertThat(countingLruMap.firstKey).isEqualTo("key2")

    countingLruMap.remove("key3")
    assertKeyOrder("key2", "key1")
    assertValueOrder(120, 140)
    assertThat(countingLruMap.firstKey).isEqualTo("key2")

    countingLruMap.remove("key2")
    assertKeyOrder("key1")
    assertValueOrder(140)
    assertThat(countingLruMap.firstKey).isEqualTo("key1")

    countingLruMap.remove("key1")
    assertKeyOrder()
    assertValueOrder()
    assertThat(countingLruMap.firstKey).isNull()
  }

  private fun assertKeyOrder(vararg expectedKeys: String?) {
    assertThat(countingLruMap.keys.toTypedArray()).isEqualTo(expectedKeys)
  }

  private fun assertValueOrder(vararg expectedValues: Int?) {
    assertThat(countingLruMap.values.toTypedArray()).isEqualTo(expectedValues)
  }
}
