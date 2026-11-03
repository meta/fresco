/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import android.os.SystemClock
import com.facebook.common.internal.Predicate
import com.facebook.common.internal.Supplier
import com.facebook.common.memory.MemoryTrimType
import com.facebook.common.references.CloseableReference
import com.facebook.common.references.ResourceReleaser
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.anyOrNull
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AbstractAdaptiveCountingMemoryCacheTest {

  companion object {
    private const val CACHE_MAX_SIZE = 1200
    private const val CACHE_MAX_COUNT = 4
    private const val CACHE_EVICTION_QUEUE_MAX_SIZE = 1100
    private const val CACHE_EVICTION_QUEUE_MAX_COUNT = 4
    private const val CACHE_ENTRY_MAX_SIZE = 1000
    private val PARAMS_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    private const val KEY = "KEY"
    private val KEYS = arrayOf("k0", "k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9")
    private const val initialLFUCacheFractionPromil = 500
  }

  @Mock lateinit var releaser: ResourceReleaser<Int>
  @Mock lateinit var cacheTrimStrategy: MemoryCache.CacheTrimStrategy
  @Mock lateinit var paramsSupplier: Supplier<MemoryCacheParams>
  @Mock lateinit var entryStateObserver: CountingMemoryCache.EntryStateObserver<String>

  private lateinit var params: MemoryCacheParams
  lateinit var cache: AbstractAdaptiveCountingMemoryCache<String, Int>
  private lateinit var valueDescriptor: ValueDescriptor<Int>
  private lateinit var mockedSystemClock: MockedStatic<SystemClock>

  @Before
  fun setUp() {
    mockedSystemClock = mockStatic(SystemClock::class.java)
    MockitoAnnotations.openMocks(this).close()
    mockedSystemClock.`when`<Long> { SystemClock.uptimeMillis() }.thenReturn(0L)
    valueDescriptor =
        object : ValueDescriptor<Int> {
          override fun getSizeInBytes(value: Int): Int = value
        }
    params =
        MemoryCacheParams(
            CACHE_MAX_SIZE,
            CACHE_MAX_COUNT,
            CACHE_EVICTION_QUEUE_MAX_SIZE,
            CACHE_EVICTION_QUEUE_MAX_COUNT,
            CACHE_ENTRY_MAX_SIZE,
            PARAMS_CHECK_INTERVAL_MS,
        )
    `when`(paramsSupplier.get()).thenReturn(params)
  }

  /**
   * test: pass illegal adaptive rate and illegal initial LFU cache fraction. expected result: fall
   * back and use the default adaptive rate and initial LFU fraction values.
   */
  @After
  fun tearDownStaticMocks() {
    mockedSystemClock.close()
  }

  @Test
  fun testPassIllegalArgumentsToTheCacheConstructor() {
    val illegalAdaptiveRate = -1
    val illegalLFUCacheFractionPromil = AbstractAdaptiveCountingMemoryCache.MIN_FRACTION_PROMIL - 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            illegalAdaptiveRate,
            1,
            2,
            illegalLFUCacheFractionPromil,
        )
    assertThat(cache).isNotNull()
    assertThat(cache.mLFUFractionPromil)
        .isEqualTo(AbstractAdaptiveCountingMemoryCache.DEFAULT_LFU_FRACTION_PROMIL)
    assertThat(cache.mAdaptiveRatePromil.toString())
        .isEqualTo(AbstractAdaptiveCountingMemoryCache.DEFAULT_ADAPTIVE_RATE_PROMIL.toString())
  }

  @Test
  fun testCache() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    cache.cache(KEY, newReference(100))
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 1)
    verify(releaser, never()).release(anyInt())
  }

  @Test
  fun testClosingOriginalReference() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val originalRef = newReference(100)
    cache.cache(KEY, originalRef)
    // cache should make its own copy and closing the original reference after caching
    // should not affect the cached value
    originalRef.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 1)
    verify(releaser, never()).release(anyInt())
  }

  @Test
  fun testClosingClientReference() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val cachedRef = cache.cache(KEY, newReference(100))
    // cached item should get exclusively owned
    cachedRef?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)
    assertExclusivelyOwned(KEY, 100)
    verify(releaser, never()).release(anyInt())
  }

  @Test
  fun testNotExclusiveAtFirst() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    cache.cache(KEY, newReference(100), entryStateObserver)
    verify(entryStateObserver, never()).onExclusivityChanged(anyOrNull<String>(), anyBoolean())
  }

  @Test
  fun testToggleExclusive() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val cachedRef = cache.cache(KEY, newReference(100), entryStateObserver)
    cachedRef?.close()
    verify(entryStateObserver).onExclusivityChanged(KEY, true)
    cache.get(KEY)
    verify(entryStateObserver).onExclusivityChanged(KEY, false)
  }

  @Test
  fun testCantReuseNonExclusive() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val cachedRef = cache.cache(KEY, newReference(100), entryStateObserver)
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    val reusedRef = cache.reuse(KEY)
    assertThat(reusedRef).isNull()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    verify(entryStateObserver, never()).onExclusivityChanged(anyOrNull<String>(), anyBoolean())
    cachedRef?.close()
  }

  @Test
  fun testCanReuseExclusive() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    var cachedRef = cache.cache(KEY, newReference(100), entryStateObserver)
    cachedRef?.close()
    verify(entryStateObserver).onExclusivityChanged(KEY, true)
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)
    cachedRef = cache.reuse(KEY)
    assertThat(cachedRef).isNotNull()
    verify(entryStateObserver).onExclusivityChanged(KEY, false)
    assertTotalSize(0, 0)
    assertExclusivelyOwnedSize(0, 0)
    cachedRef?.close()
    verify(entryStateObserver).onExclusivityChanged(KEY, true)
  }

  @Test
  fun testReuseExclusive_CacheSameItem() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    var cachedRef = cache.cache(KEY, newReference(100), entryStateObserver)
    cachedRef?.close()
    verify(entryStateObserver).onExclusivityChanged(KEY, true)
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)
    cachedRef = cache.reuse(KEY)
    assertThat(cachedRef).isNotNull()
    verify(entryStateObserver).onExclusivityChanged(KEY, false)
    assertTotalSize(0, 0)
    assertExclusivelyOwnedSize(0, 0)
    cachedRef?.let { ref ->
      val newItem = cache.cache(KEY, ref)
      ref.close()
      assertTotalSize(1, 100)
      assertExclusivelyOwnedSize(0, 0)
      newItem?.close()
      verify(entryStateObserver).onExclusivityChanged(KEY, true)
      assertTotalSize(1, 100)
      assertExclusivelyOwnedSize(1, 100)
    }
  }

  @Test
  fun testReuseExclusive_CacheSameItemWithDifferentKey() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    var cachedRef = cache.cache(KEY, newReference(100), entryStateObserver)
    cachedRef?.close()
    verify(entryStateObserver).onExclusivityChanged(KEY, true)
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)
    cachedRef = cache.reuse(KEY)
    assertThat(cachedRef).isNotNull()
    verify(entryStateObserver).onExclusivityChanged(KEY, false)
    assertTotalSize(0, 0)
    assertExclusivelyOwnedSize(0, 0)
    cachedRef?.let { ref ->
      val newItem = cache.cache(KEYS[2], ref)
      ref.close()
      assertTotalSize(1, 100)
      assertExclusivelyOwnedSize(0, 0)
      newItem?.close()
      verify(entryStateObserver).onExclusivityChanged(KEY, true)
      assertTotalSize(1, 100)
      assertExclusivelyOwnedSize(1, 100)
    }
  }

  @Test
  fun testInUseCount() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val cachedRef1 = cache.cache(KEY, newReference(100))

    val cachedRef2a = cache.get(KEY)
    val cachedRef2b = cachedRef2a?.clone()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 2)

    val cachedRef3a = cache.get(KEY)
    val cachedRef3b = cachedRef3a?.clone()
    val cachedRef3c = cachedRef3b?.clone()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 3)

    cachedRef1?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 2)

    // all copies of cachedRef2a need to be closed for usage count to drop
    cachedRef2a?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 2)
    cachedRef2b?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 1)

    // all copies of cachedRef3a need to be closed for usage count to drop
    cachedRef3c?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 1)
    cachedRef3b?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEY, 100, 1)
    cachedRef3a?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)
    assertExclusivelyOwned(KEY, 100)
  }

  @Test
  fun testCachingSameKeyTwice() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val originalRef1 = newReference(110)
    val cachedRef1 = cache.cache(KEY, originalRef1)
    val cachedRef2a = cache.get(KEY)
    val cachedRef2b = cachedRef2a?.clone()
    val cachedRef3 = cache.get(KEY)
    val entry1 = cache.mCachedEntries.get(KEY)

    val cachedRef2 = cache.cache(KEY, newReference(120))
    val entry2 = cache.mCachedEntries.get(KEY)
    assertThat(entry1).isNotSameAs(entry2)
    entry1?.let { assertOrphanWithCount(it, 3) }
    assertSharedWithCount(KEY, 120, 1)

    // release the orphaned reference only when all clients are gone
    originalRef1.close()
    cachedRef2b?.close()
    entry1?.let { assertOrphanWithCount(it, 3) }
    cachedRef2a?.close()
    entry1?.let { assertOrphanWithCount(it, 2) }
    cachedRef1?.close()
    entry1?.let { assertOrphanWithCount(it, 1) }
    verify(releaser, never()).release(anyInt())
    cachedRef3?.close()
    entry1?.let { assertOrphanWithCount(it, 0) }
    verify(releaser).release(110)
  }

  @Test
  fun testDoesNotCacheBigValues() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    assertThat(cache.cache(KEY, newReference(CACHE_ENTRY_MAX_SIZE + 1))).isNull()
  }

  @Test
  fun testDoesCacheNotTooBigValues() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    assertThat(cache.cache(KEY, newReference(CACHE_ENTRY_MAX_SIZE))).isNotNull()
  }

  @Test
  fun testEviction_ByTotalSize() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    // value 4 cannot fit the cache
    val originalRef1 = newReference(400)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    val originalRef2 = newReference(500)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    val originalRef3 = newReference(100)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    val originalRef4 = newReference(1000)
    var valueRef4 = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    assertTotalSize(3, 1000)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEYS[1], 400, 1)
    assertSharedWithCount(KEYS[2], 500, 1)
    assertSharedWithCount(KEYS[3], 100, 1)
    assertNotCached(KEYS[4], 1000)
    assertThat(valueRef4).isNull()

    // closing the clients of cached items will make them viable for eviction
    valueRef1?.close()
    valueRef2?.close()
    valueRef3?.close()
    assertTotalSize(1, 100)
    assertExclusivelyOwnedSize(1, 100)

    // value 4 can now fit after evicting value1 and value2
    valueRef4 = cache.cache(KEYS[4], newReference(1000))
    assertTotalSize(2, 1100)
    assertExclusivelyOwnedSize(1, 100)
    assertNotCached(KEYS[1], 400)
    assertNotCached(KEYS[2], 500)
    assertSharedWithCount(KEYS[4], 1000, 1)
    verify(releaser).release(400)
    verify(releaser).release(500)
    valueRef4?.close()
  }

  @Test
  fun testEviction_ByTotalCount() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    // value 5 cannot fit the cache
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    val originalRef4 = newReference(40)
    var valueRef4 = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    val originalRef5 = newReference(50)
    val valueRef5 = cache.cache(KEYS[5], originalRef5)
    originalRef5.close()
    assertTotalSize(4, 100)
    assertExclusivelyOwnedSize(0, 0)
    assertSharedWithCount(KEYS[1], 10, 1)
    assertSharedWithCount(KEYS[2], 20, 1)
    assertSharedWithCount(KEYS[3], 30, 1)
    assertSharedWithCount(KEYS[4], 40, 1)
    assertNotCached(KEYS[5], 50)
    assertThat(valueRef5).isNull()

    // closing the clients of cached items will make them viable for eviction
    valueRef1?.close()
    valueRef2?.close()
    valueRef3?.close()
    assertTotalSize(2, 70)
    assertExclusivelyOwnedSize(1, 30)
    assertNotCached(KEYS[1], 10)
    assertNotCached(KEYS[2], 20)

    // value 4 can now fit after evicting value1
    valueRef4 = cache.cache(KEYS[5], newReference(50))
    assertTotalSize(3, 120)
    assertExclusivelyOwnedSize(1, 30)
    assertNotCached(KEYS[1], 10)
    assertNotCached(KEYS[2], 20)
    assertExclusivelyOwned(KEYS[3], 30)
    assertSharedWithCount(KEYS[4], 40, 1)
    assertSharedWithCount(KEYS[5], 50, 1)
    verify(releaser).release(10)
    verify(releaser).release(20)
  }

  @Test
  fun testEviction_ByEvictionQueueSize() {
    // Cache has 4 entries; 3 for LFU and 1 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            750,
        )

    val originalRef1 = newReference(200)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1?.close()
    val originalRef2 = newReference(300)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    val originalRef3 = newReference(400)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    assertTotalSize(3, 900)
    assertExclusivelyOwnedSize(2, 500)
    assertExclusivelyOwned(KEYS[1], 200)
    assertExclusivelyOwned(KEYS[2], 300)
    assertSharedWithCount(KEYS[3], 400, 1)

    // closing the client reference for item3 will cause item1 to be evicted
    valueRef3?.close()
    assertTotalSize(2, 700)
    assertExclusivelyOwnedSize(2, 700)
    assertNotCached(KEYS[1], 200)
    assertExclusivelyOwned(KEYS[2], 300)
    assertExclusivelyOwned(KEYS[3], 400)
    verify(releaser).release(200)
  }

  @Test
  fun testEviction_ByEvictionQueueCount() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1?.close()
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3?.close()
    val originalRef4 = newReference(40)
    val valueRef4 = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    assertTotalSize(2, 70)
    assertExclusivelyOwnedSize(1, 30)
    assertNotCached(KEYS[1], 10)
    assertNotCached(KEYS[2], 20)
    assertExclusivelyOwned(KEYS[3], 30)
    assertSharedWithCount(KEYS[4], 40, 1)

    // closing the client reference for item4 will cause item2 to be evicted as well
    valueRef4?.close()
    assertTotalSize(2, 70)
    assertExclusivelyOwnedSize(2, 70)
    assertNotCached(KEYS[1], 10)
    assertNotCached(KEYS[2], 20)
    assertExclusivelyOwned(KEYS[3], 30)
    assertExclusivelyOwned(KEYS[4], 40)
    verify(releaser).release(10)
    verify(releaser).release(20)
  }

  @Test
  fun testUpdatesCacheParams() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val inOrder = inOrder(paramsSupplier)

    val originalRef = newReference(400)
    val cachedRef = cache.cache(KEYS[2], originalRef)
    originalRef.close()
    cachedRef?.close()

    cache.get(KEY)
    inOrder.verify(paramsSupplier).get()

    mockedSystemClock
        .`when`<Long> { SystemClock.uptimeMillis() }
        .thenReturn(PARAMS_CHECK_INTERVAL_MS - 1)
    cache.get(KEY)
    inOrder.verify(paramsSupplier, never()).get()
    cache.get(KEY)
    inOrder.verify(paramsSupplier, never()).get()

    assertTotalSize(1, 400)
    assertExclusivelyOwnedSize(1, 400)

    params =
        MemoryCacheParams(
            300, // cache max size
            CACHE_MAX_COUNT,
            CACHE_EVICTION_QUEUE_MAX_SIZE,
            CACHE_EVICTION_QUEUE_MAX_COUNT,
            CACHE_ENTRY_MAX_SIZE,
            PARAMS_CHECK_INTERVAL_MS,
        )
    `when`(paramsSupplier.get()).thenReturn(params)

    mockedSystemClock
        .`when`<Long> { SystemClock.uptimeMillis() }
        .thenReturn(PARAMS_CHECK_INTERVAL_MS)
    cache.get(KEY)
    inOrder.verify(paramsSupplier).get()

    assertTotalSize(0, 0)
    assertExclusivelyOwnedSize(0, 0)
    verify(releaser).release(400)
  }

  @Test
  fun testRemoveAllMatchingPredicate() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to MFU
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    val valueRef1a = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1?.close()
    valueRef1a?.close()

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()

    // insert item3 to the cache
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    val entry3 = cache.mCachedEntries.get(KEYS[3])

    val numEvictedEntries =
        cache.removeAll(
            object : Predicate<String> {
              override fun apply(key: String): Boolean {
                return key == KEYS[2] || key == KEYS[3]
              }
            },
        )

    assertThat(numEvictedEntries).isEqualTo(2)
    assertNotCached(KEYS[2], 20)
    assertNotCached(KEYS[3], 30)

    assertTotalSize(1, 10)
    assertExclusivelyOwnedSize(1, 10)
    entry3?.let { assertOrphanWithCount(it, 1) }

    verify(releaser).release(20)
    verify(releaser, never()).release(30)

    valueRef3?.close()
    verify(releaser).release(30)
  }

  @Test
  fun testClear() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    val originalRef1 = newReference(110)
    val cachedRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    val entry1 = cache.mCachedEntries.get(KEYS[1])
    val originalRef2 = newReference(120)
    val cachedRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    cachedRef2?.close()

    cache.clear()
    assertTotalSize(0, 0)
    assertExclusivelyOwnedSize(0, 0)
    entry1?.let { assertOrphanWithCount(it, 1) }
    assertNotCached(KEYS[2], 120)
    verify(releaser).release(120)

    cachedRef1?.close()
    verify(releaser).release(110)
  }

  @Test
  fun testTrimming() {
    val memoryTrimType = MemoryTrimType.OnCloseToDalvikHeapLimit
    params = MemoryCacheParams(2200, 16, 2200, 16, 110, PARAMS_CHECK_INTERVAL_MS)
    `when`(paramsSupplier.get()).thenReturn(params)
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    mockedSystemClock
        .`when`<Long> { SystemClock.uptimeMillis() }
        .thenReturn(PARAMS_CHECK_INTERVAL_MS)
    val inOrder = inOrder(releaser)

    // create original references
    val originalRefs = Array<CloseableReference<Int>?>(10) { null }
    for (i in 0 until 10) {
      originalRefs[i] = newReference(100 + i)
    }
    // cache items & close the original references
    val cachedRefs = Array<CloseableReference<Int>?>(10) { null }
    for (i in 0 until 10) {
      originalRefs[i]?.let { ref -> cachedRefs[i] = cache.cache(KEYS[i], ref) }
      originalRefs[i]?.close()
    }
    // cache should keep alive the items until evicted
    inOrder.verify(releaser, never()).release(anyInt())

    // trimming cannot evict shared entries
    `when`(cacheTrimStrategy.getTrimRatio(memoryTrimType)).thenReturn(1.00)
    cache.trim(memoryTrimType)
    assertSharedWithCount(KEYS[0], 100, 1)
    assertSharedWithCount(KEYS[1], 101, 1)
    assertSharedWithCount(KEYS[2], 102, 1)
    assertSharedWithCount(KEYS[3], 103, 1)
    assertSharedWithCount(KEYS[4], 104, 1)
    assertSharedWithCount(KEYS[5], 105, 1)
    assertSharedWithCount(KEYS[6], 106, 1)
    assertSharedWithCount(KEYS[7], 107, 1)
    assertSharedWithCount(KEYS[8], 108, 1)
    assertSharedWithCount(KEYS[9], 109, 1)
    assertTotalSize(10, 1045)
    assertExclusivelyOwnedSize(0, 0)

    // close 6 client references
    cachedRefs[4]?.close()
    cachedRefs[2]?.close()
    cachedRefs[7]?.close()
    cachedRefs[3]?.close()
    cachedRefs[6]?.close()
    cachedRefs[8]?.close()
    assertSharedWithCount(KEYS[0], 100, 1)
    assertSharedWithCount(KEYS[1], 101, 1)
    assertSharedWithCount(KEYS[5], 105, 1)
    assertSharedWithCount(KEYS[9], 109, 1)
    assertExclusivelyOwned(KEYS[8], 108)
    assertExclusivelyOwned(KEYS[2], 102)
    assertExclusivelyOwned(KEYS[7], 107)
    assertExclusivelyOwned(KEYS[3], 103)
    assertExclusivelyOwned(KEYS[6], 106)
    assertExclusivelyOwned(KEYS[4], 104)
    assertTotalSize(10, 1045)
    assertExclusivelyOwnedSize(6, 630)

    // Trim cache by 45%. This means that out of total of 1045 bytes cached, 574 should remain.
    // 415 bytes ars used by the clients, which leaves 159 for the exclusively owned items.
    // Only the the most recent exclusively owned item fits, and it occupies 108 bytes.
    `when`(cacheTrimStrategy.getTrimRatio(memoryTrimType)).thenReturn(0.45)
    cache.trim(memoryTrimType)
    assertSharedWithCount(KEYS[0], 100, 1)
    assertSharedWithCount(KEYS[1], 101, 1)
    assertSharedWithCount(KEYS[5], 105, 1)
    assertSharedWithCount(KEYS[9], 109, 1)
    assertExclusivelyOwned(KEYS[8], 108)
    assertNotCached(KEYS[4], 104)
    assertNotCached(KEYS[2], 102)
    assertNotCached(KEYS[7], 107)
    assertNotCached(KEYS[3], 103)
    assertNotCached(KEYS[6], 106)
    assertTotalSize(5, 523)
    assertExclusivelyOwnedSize(1, 108)
    inOrder.verify(releaser).release(102)
    inOrder.verify(releaser).release(107)
    inOrder.verify(releaser).release(103)
    inOrder.verify(releaser).release(106)

    cachedRefs[5]?.close()
    // Full trim. All exclusively owned items should be evicted.
    `when`(cacheTrimStrategy.getTrimRatio(memoryTrimType)).thenReturn(1.00)
    cache.trim(memoryTrimType)
    assertSharedWithCount(KEYS[0], 100, 1)
    assertSharedWithCount(KEYS[1], 101, 1)
    assertSharedWithCount(KEYS[9], 109, 1)
    assertNotCached(KEYS[8], 108)
    assertNotCached(KEYS[2], 102)
    assertNotCached(KEYS[7], 107)
    assertNotCached(KEYS[3], 103)
    assertNotCached(KEYS[6], 106)
    assertNotCached(KEYS[4], 104)
    assertNotCached(KEYS[5], 105)
    assertTotalSize(3, 310)
    assertExclusivelyOwnedSize(0, 0)
    inOrder.verify(releaser).release(105)
  }

  @Test
  fun testTrimmingMFU() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    val memoryTrimType = MemoryTrimType.OnCloseToDalvikHeapLimit
    params = MemoryCacheParams(2200, 16, 2200, 16, 110, PARAMS_CHECK_INTERVAL_MS)
    `when`(paramsSupplier.get()).thenReturn(params)
    mockedSystemClock
        .`when`<Long> { SystemClock.uptimeMillis() }
        .thenReturn(PARAMS_CHECK_INTERVAL_MS)
    val inOrder = inOrder(releaser)

    // insert item1 to MFU
    val originalRef1 = newReference(10)
    val cachedRef1 = cache.cache(KEYS[1], originalRef1)
    val cachedRef1a = cache.get(KEYS[1])
    originalRef1.close()
    cachedRef1?.close()
    cachedRef1a?.close()

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val cachedRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    cachedRef2?.close()

    // Trim 50% of the cache size
    `when`(cacheTrimStrategy.getTrimRatio(memoryTrimType)).thenReturn(0.5)
    cache.trim(memoryTrimType)
    assertExclusivelyOwnedSize(1, 10)
    assertMFUExclusivelyOwned(KEYS[1], 10)

    // insert item3 to LFU
    val originalRef3 = newReference(30)
    val cachedRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    cachedRef3?.close()

    // insert item2 to MFU
    val originalRef4 = newReference(40)
    val cachedRef4 = cache.cache(KEYS[4], originalRef4)
    val cachedRef4a = cache.get(KEYS[4])
    originalRef4.close()
    cachedRef4?.close()
    cachedRef4a?.close()

    assertExclusivelyOwnedSize(3, 80)
    // Trim 50% of the cache size, this will remove item3 from LFU and item1 from MFU
    `when`(cacheTrimStrategy.getTrimRatio(memoryTrimType)).thenReturn(0.5)
    cache.trim(memoryTrimType)
    assertNotCached(KEYS[1], 10)
    assertNotCached(KEYS[3], 30)
    assertMFUExclusivelyOwned(KEYS[4], 40)
  }

  @Test
  fun testContains() {
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )
    assertThat(cache.contains(KEY)).isFalse()

    val newRef = cache.cache(KEY, newReference(100))

    assertThat(cache.contains(KEY)).isTrue()
    assertThat(cache.contains(KEYS[0])).isFalse()

    newRef?.close()

    assertThat(cache.contains(KEY)).isTrue()
    assertThat(cache.contains(KEYS[0])).isFalse()

    val reuse = cache.reuse(KEY)
    reuse?.close()

    assertThat(cache.contains(KEY)).isFalse()
    assertThat(cache.contains(KEYS[0])).isFalse()
  }

  /**
   * Test: insert one item to the cache, access it twice, then close the first reference to the
   * item. Expected: after closing the reference, the ClientCount should be decreased while the
   * accessCount should not change.
   */
  @Test
  fun testAccessCountUpdate() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // add MFU item1
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    val valueRef1a = cache.get(KEYS[1])
    originalRef1.close()
    assertSharedWithCount(KEYS[1], 10, 2)
    assertSharedWithAccessCount(KEYS[1], 10, 2)

    // close the first reference
    valueRef1?.close()
    assertSharedWithCount(KEYS[1], 10, 1)
    assertSharedWithAccessCount(KEYS[1], 10, 2)
    valueRef1a?.close()
  }

  /**
   * Test: Add 2 frequently used elements, add a new frequently used element and make sure the cache
   * evicts the 1st item inserted to the cache (the least recently used one).
   */
  @Test
  fun testFrequentlyUsedEviction() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // add item1 to MFU
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    val valueRef1a = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1?.close()
    valueRef1a?.close()

    // add item2 to MFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    val valueRef2a = cache.get(KEYS[2])
    originalRef2.close()
    valueRef2?.close()
    assertSharedWithCount(KEYS[2], 20, 1)
    assertSharedWithAccessCount(KEYS[2], 20, 2)
    valueRef2a?.close()

    // both items are in the MFU
    assertExclusivelyOwned(KEYS[1], 10)
    assertExclusivelyOwned(KEYS[2], 20)

    // add item2 to MFU, as a result item1 should be evicted
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    val valueRef3a = cache.get(KEYS[3])
    originalRef3.close()
    valueRef3?.close()
    valueRef3a?.close()
    assertExclusivelyOwned(KEYS[2], 20)
    assertExclusivelyOwned(KEYS[3], 30)
  }

  /**
   * add 2 frequently used items and one LFU item. Then add a new frequently used item. Expected:
   * the cache should evict one of the MFU items and not the LFU item.
   */
  @Test
  fun testFrequentlyUsedEvictionWhithoutChangingLFU() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to MFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    val valueRef1b = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1a?.close()
    valueRef1b?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)

    // insert item3 to MFU
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    val valueRef3b = cache.get(KEYS[3])
    originalRef3.close()
    valueRef3a?.close()
    valueRef3b?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertMFUExclusivelyOwned(KEYS[3], 30)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertExclusivelyOwnedSize(3, 60)

    // insert item4 to MFU, this should evict item1 from the cache
    val originalRef4 = newReference(40)
    val valueRef4a = cache.cache(KEYS[4], originalRef4)
    val valueRef4b = cache.get(KEYS[4])
    originalRef4.close()
    valueRef4a?.close()
    valueRef4b?.close()
    assertNotCached(KEYS[1], 10)
    assertMFUExclusivelyOwned(KEYS[3], 30)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertMFUExclusivelyOwned(KEYS[4], 40)
  }

  /**
   * insert 2 LFU keys, and 1 MFU key. Make sure when adding a new LFU key, the cache evicts the LFU
   * LFU key and not the LRU MFU key.
   */
  @Test
  fun testEvictLFUandNotMFUKeys() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to MFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    val valueRef1b = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1a?.close()
    valueRef1b?.close() // item1 is in the MFU cache
    assertMFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2a = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2a?.close()

    // insert item3 to LFU
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3a?.close()

    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)

    // insert item4 to LFU, the cache should evict item2
    val originalRef4 = newReference(40)
    val valueRef4a = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    valueRef4a?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertLFUExclusivelyOwned(KEYS[4], 40)
  }

  /**
   * insert 1 MFU item and 1 LFU item, access the LFU item twice (to become MFU) and make sure it is
   * moved to the MFU cache.
   */
  @Test
  fun testMoveItemsFromLFUToMFU() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item2 to MFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    val valueRef1b = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1a?.close()
    valueRef1b?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)

    // access item2 one more time, this should result in moving item2 from LFU to MFU cache.
    val valueRef2a = cache.get(KEYS[2])
    valueRef2a?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertMFUExclusivelyOwned(KEYS[2], 20)
  }

  /**
   * This test makes sure that the accessCount state is updated for keys in the ghost list. Test:
   * add item1 to the ghost list, and try to access this element (after it was evicted), and check
   * that the access number was updated in the ghost list.
   */
  @Test
  fun testAccessCountUpdateInLFUGhostList() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to LFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    valueRef1a?.close()

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2a = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2a?.close()

    // insert item3 to LFU, this should evict item1 from LFU and insert it to the LFU ghost list
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3a?.close()
    assertExclusivelyOwnedSize(2, 50)
    assertKeyIsInLFUGhostList(KEYS[1], 1)

    // try to access the evicted item: item1, and check its accessCount is updated correctly
    val valueRef1b = cache.get(KEYS[1])
    assertThat(valueRef1b).isNull()
    assertExclusivelyOwnedSize(2, 50)
    assertKeyIsInLFUGhostList(KEYS[1], 2)

    // validate item1's state is correct after inserting the element again to the cache.
    val valueRef1c = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    assertSharedWithAccessCount(KEYS[1], 10, 3)
    // also, make sure it was added to the MFU now.
    valueRef1c?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
  }

  /** check if the MFU keys are inserted to the MFU ghost list after eviction. */
  @Test
  fun testAddKeyToMFHGhostList() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to MFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    val valueRef1b = cache.get(KEYS[1])
    originalRef1.close()
    valueRef1a?.close()
    valueRef1b?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to MFU
    val originalRef2 = newReference(20)
    val valueRef2a = cache.cache(KEYS[2], originalRef2)
    val valueRef2b = cache.get(KEYS[2])
    originalRef2.close()
    valueRef2a?.close()
    valueRef2b?.close()
    assertMFUExclusivelyOwned(KEYS[1], 10)
    assertMFUExclusivelyOwned(KEYS[2], 20)

    // insert item3 to MFU, the cache should evict item1 and insert it to the MFU ghost list
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    val valueRef3b = cache.get(KEYS[3])
    originalRef3.close()
    valueRef3a?.close()
    valueRef3b?.close()
    assertMFUExclusivelyOwned(KEYS[2], 20)
    assertMFUExclusivelyOwned(KEYS[3], 30)
    assertKeyIsInMFUGhostList(KEYS[1])
  }

  /** checks that MFU min size cannot get smaller than MIN_MFU_FRACTION_PROMIL. */
  @Test
  fun testCacheMargins() {
    // Cache has 4 entries; 3 for LFU and 1 for MFU
    // key is considered as MFU, if its accessCount > 1
    val initialLFUFractionPromil = 850
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUFractionPromil,
        )

    // insert item1 to LFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1a?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2a = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2a?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)

    // insert item3 to LFU
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3a?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)

    // insert item4 to LFU, this will evict item1
    val originalRef4 = newReference(40)
    val valueRef4a = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    valueRef4a?.close()
    assertNotCached(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertLFUExclusivelyOwned(KEYS[4], 40)

    // item1 was evicted, now in ghost list
    // this get should have increased the LFU fraction but since the MFU fraction will get under
    // MIN_MFU_FRACTION_PREOMIL, the fractions will not be updated
    val valueRef1b = cache.get(KEYS[1])
    assertThat(valueRef1b).isNull()
    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUFractionPromil)
  }

  /**
   * This test checks that the ghost list is actually an LRU list. It checks if the position of the
   * keys in the LFU ghost list is updated when trying to access them after being evicted (but they
   * were found it in the ghost list). ExpectedL the accessed key will be moved to the youngest
   * position in the ghost list.
   */
  @Test
  fun testPositionUpdateInGhostList() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to LFU
    val originalRef1 = newReference(10)
    val valueRef1a = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1a?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertGhostListsValidSize()

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2a = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2a?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertGhostListsValidSize()

    // insert item3 to ghost list, this will result in evicting item1 from the LFU and insert it to
    // the LFU ghost list.
    val originalRef3 = newReference(30)
    val valueRef3a = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3a?.close()
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertKeyIsInLFUGhostList(KEYS[1], 1)
    assertGhostListsValidSize()

    // insert item4 to LFU, this will result in evicting item2 from the LFU and inset it to the LFU
    // ghost list.
    val originalRef4 = newReference(40)
    val valueRef4a = cache.cache(KEYS[4], originalRef4)
    originalRef4.close()
    valueRef4a?.close()
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertLFUExclusivelyOwned(KEYS[4], 40)
    assertKeyIsInLFUGhostList(KEYS[1], 1)
    assertKeyIsInLFUGhostList(KEYS[2], 1)
    assertGhostListsValidSize()

    // try to access item1 (which is already evicted).
    val valueRef1b = cache.get(KEYS[1])
    assertThat(valueRef1b).isNull()
    assertKeyIsInLFUGhostList(KEYS[1], 2)
    assertGhostListsValidSize()

    // insert item5 to LFU, this will result in evicting item3 from LFU and insert it to the LFU
    // ghost list
    val originalRef5 = newReference(50)
    val valueRef5a = cache.cache(KEYS[5], originalRef5)
    originalRef5.close()
    valueRef5a?.close()

    // make sure item2 is removed from LFU ghost list and not item1 (since they have replaced
    // their positions in the ghost list)
    assertGhostListsValidSize()
    assertLFUExclusivelyOwned(KEYS[4], 40)
    assertLFUExclusivelyOwned(KEYS[5], 50)
    assertKeyIsInLFUGhostList(KEYS[1], 2)
    assertKeyIsInLFUGhostList(KEYS[3], 1)
    assertKeyIsNotInLFUGhostList(KEYS[2])
  }

  /**
   * This test maskes sure that the LFU cache fraction is being updated correctly when trying to
   * access a recently evicted key from LFU. Test: add 3 LFU items, the 1st item will be evicted and
   * inserted to the LFU ghost list (the LFU size is up to 2 keys). try to access the recently
   * evicted key and make sure that the LFU cache fraction is increased by the value of the
   * adaptiveRate.
   */
  @Test
  fun testFractionUpdate() {
    // Cache has 4 entries; 2 for LFU and 2 for MFU
    // key is considered as MFU, if its accessCount > 1
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            100,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // insert item1 to LFU
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)

    // insert item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)

    // insert item3 to LFU, item1 will be evicted from LFU and inserted to the LFU ghost list
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3?.close()
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertNotCached(KEYS[1], 10)
    assertKeyIsInLFUGhostList(KEYS[1], 1)
    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUCacheFractionPromil)

    // access item1, which was already evicted from LFU
    val valueRef1a = cache.get(KEYS[1])
    assertThat(valueRef1a).isNull()
    // checks if the LFU fraction has been increased by adativeRate value
    assertThat(cache.mLFUFractionPromil)
        .isEqualTo(initialLFUCacheFractionPromil + cache.mAdaptiveRatePromil)

    // make item2 "frequently used" by accessing it more than mFrequentlyUsedThreshiold
    // notice, MFU max size is 1 now (LFU size is 3).
    val valueRef2a = cache.get(KEYS[2])
    valueRef2a?.close()
    assertMFUExclusivelyOwned(KEYS[2], 20)

    // insert item4 to MFU, item2 will be evicted from MFU and inserted in the MFU ghost list
    val originalRef4 = newReference(40)
    val valueRef4 = cache.cache(KEYS[4], originalRef4)
    val valueRef4a = cache.get(KEYS[4])
    originalRef4.close()
    valueRef4?.close()
    valueRef4a?.close()
    assertThat(cache.mLFUFractionPromil)
        .isEqualTo(initialLFUCacheFractionPromil + cache.mAdaptiveRatePromil)
  }

  /**
   * This test checks edge cases, when trying to increase/decrease the LFU cache fraction to more
   * than 1/less than 0 (of the cache size). The expected behaviour is not to update the LFU
   * fraction.
   */
  @Test
  fun testLFUFractionOverflow() {
    val adaptiveRate = 700 // in order to cause an overflow
    cache =
        createDummyAdaptiveCountingMemoryCache(
            paramsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            adaptiveRate,
            1,
            2,
            initialLFUCacheFractionPromil,
        )

    // add item1 to LFU
    val originalRef1 = newReference(10)
    val valueRef1 = cache.cache(KEYS[1], originalRef1)
    originalRef1.close()
    valueRef1?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)

    // add item2 to LFU
    val originalRef2 = newReference(20)
    val valueRef2 = cache.cache(KEYS[2], originalRef2)
    originalRef2.close()
    valueRef2?.close()
    assertLFUExclusivelyOwned(KEYS[1], 10)
    assertLFUExclusivelyOwned(KEYS[2], 20)

    // add item3 to LFU, item1 will be evicted from LFU and will be inserted to the LFU ghost list
    val originalRef3 = newReference(30)
    val valueRef3 = cache.cache(KEYS[3], originalRef3)
    originalRef3.close()
    valueRef3?.close()
    assertLFUExclusivelyOwned(KEYS[2], 20)
    assertLFUExclusivelyOwned(KEYS[3], 30)
    assertNotCached(KEYS[1], 10)

    // item1 in the LFU ghost list
    assertKeyIsInLFUGhostList(KEYS[1], 1)

    // try to access item1, which was already evicted, which will result in updating the LFU
    // fraction
    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUCacheFractionPromil)
    val valueRef1a = cache.get(KEYS[1])
    assertThat(valueRef1a).isNull()
    // make sure the LFU cache fraction was not updated, because if we do it will cause in overflow
    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUCacheFractionPromil)

    // make item2 and item3 "frequently used" by accessing them more than mFrequentlyUsedThreshiold
    val valueRef2a = cache.get(KEYS[2])
    val valueRef3a = cache.get(KEYS[3])
    valueRef2a?.close()
    valueRef3a?.close()
    assertMFUExclusivelyOwned(KEYS[2], 20)
    assertMFUExclusivelyOwned(KEYS[3], 30)

    // insert item4 to MFU, item2 will be evicted from MFU and inserted in the MFU ghost list
    val originalRef4 = newReference(40)
    val valueRef4 = cache.cache(KEYS[4], originalRef4)
    val valueRef4a = cache.get(KEYS[4])
    originalRef4.close()
    valueRef4?.close()
    valueRef4a?.close()

    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUCacheFractionPromil)
    val valueRef2b = cache.get(KEYS[2])
    assertThat(valueRef2b).isNull()
    // make sure the LFU cache fraction was not updated, because if we do it will cause in overflow
    assertThat(cache.mLFUFractionPromil).isEqualTo(initialLFUCacheFractionPromil)
  }

  private fun assertKeyIsInLFUGhostList(key: String, accessCount: Int) {
    assertThat(cache.mLeastFrequentlyUsedKeysGhostList.contains(key))
        .describedAs("Key not found in LFU Ghost List")
        .isTrue()
    assertThat(cache.mCachedEntries.contains(key)).describedAs("Key found in cache").isFalse()
    assertThat(
        cache.mLeastFrequentlyUsedExclusiveEntries.contains(key) ||
            cache.mMostFrequentlyUsedExclusiveEntries.contains(key),
    )
        .describedAs("Key found in exclusive")
        .isFalse()
    assertThat(cache.mLeastFrequentlyUsedKeysGhostList.contains(key))
        .describedAs("Key not found in LFU ghost list")
        .isTrue()
    val keyAccessCount = cache.mLeastFrequentlyUsedKeysGhostList.getValue(key)
    assertThat(keyAccessCount).isNotNull()
    assertThat(keyAccessCount)
        .describedAs("Mismatch in access count in ghost list")
        .isEqualTo(accessCount)
  }

  private fun assertKeyIsNotInLFUGhostList(key: String) {
    assertThat(cache.mLeastFrequentlyUsedKeysGhostList.contains(key))
        .describedAs("Key found in LFU Ghost List")
        .isFalse()
  }

  private fun assertGhostListsValidSize() {
    assertThat(cache.mLeastFrequentlyUsedKeysGhostList.size() <= cache.mGhostListMaxSize)
        .describedAs("LFU keys ghost list overflows")
        .isTrue()
    assertThat(cache.mMostFrequentlyUsedKeysGhostList.size <= cache.mGhostListMaxSize)
        .describedAs("MFU keys ghost list overflows")
        .isTrue()
  }

  private fun assertKeyIsInMFUGhostList(key: String) {
    assertThat(cache.mLeastFrequentlyUsedKeysGhostList.contains(key))
        .describedAs("Key found in LFU Ghost List")
        .isFalse()
    assertThat(cache.mCachedEntries.contains(key)).describedAs("Key found in cache").isFalse()
    assertThat(
        cache.mLeastFrequentlyUsedExclusiveEntries.contains(key) ||
            cache.mMostFrequentlyUsedExclusiveEntries.contains(key),
    )
        .describedAs("Key found in exclusive")
        .isFalse()
    assertThat(cache.mMostFrequentlyUsedKeysGhostList.contains(key))
        .describedAs("Key not found in MFU ghost list")
        .isTrue()
  }

  private fun newReference(size: Int): CloseableReference<Int> {
    return CloseableReference.of(size, releaser)
  }

  private fun assertSharedWithCount(key: String, value: Int, count: Int) {
    assertThat(cache.mCachedEntries.contains(key))
        .describedAs("key not found in the cache")
        .isTrue()
    assertThat(cache.mLeastFrequentlyUsedExclusiveEntries.contains(key))
        .describedAs("key found in the LFU exclusives")
        .isFalse()
    assertThat(cache.mMostFrequentlyUsedExclusiveEntries.contains(key))
        .describedAs("key found in the MFU exclusives")
        .isFalse()
    val entry = cache.mCachedEntries.get(key)
    assertThat(entry).describedAs("entry not found in the cache").isNotNull()
    entry?.let { memoryEntry ->
      assertThat(memoryEntry.key).describedAs("key mismatch").isEqualTo(key)
      assertThat(memoryEntry.valueRef.get()).describedAs("value mismatch").isEqualTo(value)
      assertThat(memoryEntry.clientCount).describedAs("client count mismatch").isEqualTo(count)
      assertThat(memoryEntry.isOrphan).describedAs("entry is an orphan").isFalse()
    }
  }

  private fun assertSharedWithAccessCount(key: String, value: Int, count: Int) {
    assertThat(cache.mCachedEntries.contains(key))
        .describedAs("key not found in the cache")
        .isTrue()
    val entry = cache.mCachedEntries.get(key)
    assertThat(entry).describedAs("entry not found in the cache").isNotNull()
    entry?.let { accessEntry ->
      assertThat(accessEntry.key).describedAs("key mismatch").isEqualTo(key)
      assertThat(accessEntry.valueRef.get()).describedAs("value mismatch").isEqualTo(value)
      assertThat(accessEntry.accessCount).describedAs("access count mismatch").isEqualTo(count)
      assertThat(accessEntry.isOrphan).describedAs("entry is an orphan").isFalse()
    }
  }

  private fun assertExclusivelyOwned(key: String, value: Int) {
    assertThat(cache.mCachedEntries.contains(key))
        .describedAs("key not found in the cache")
        .isTrue()
    assertThat(
        cache.mLeastFrequentlyUsedExclusiveEntries.contains(key) ||
            cache.mMostFrequentlyUsedExclusiveEntries.contains(key),
    )
        .describedAs("key not found in the exclusives")
        .isTrue()
    val entry = cache.mCachedEntries.get(key)
    assertThat(entry).describedAs("entry not found in the cache").isNotNull()
    entry?.let { exclusiveEntry ->
      assertThat(exclusiveEntry.key).describedAs("key mismatch").isEqualTo(key)
      assertThat(exclusiveEntry.valueRef.get()).describedAs("value mismatch").isEqualTo(value)
      assertThat(exclusiveEntry.clientCount)
          .describedAs("client count greater than zero")
          .isEqualTo(0)
      assertThat(exclusiveEntry.isOrphan).describedAs("entry is an orphan").isFalse()
    }
  }

  private fun assertMFUExclusivelyOwned(key: String, value: Int) {
    assertThat(cache.mCachedEntries.contains(key))
        .describedAs("key not found in the cache")
        .isTrue()
    assertThat(cache.mMostFrequentlyUsedExclusiveEntries.contains(key))
        .describedAs("key not found in the exclusives")
        .isTrue()
    val entry = cache.mCachedEntries.get(key)
    assertThat(entry).describedAs("entry not found in the cache").isNotNull()
    entry?.let { mfuEntry ->
      assertThat(mfuEntry.key).describedAs("key mismatch").isEqualTo(key)
      assertThat(mfuEntry.valueRef.get()).describedAs("value mismatch").isEqualTo(value)
      assertThat(mfuEntry.clientCount).describedAs("client count greater than zero").isEqualTo(0)
      assertThat(mfuEntry.isOrphan).describedAs("entry is an orphan").isFalse()
    }
  }

  private fun assertLFUExclusivelyOwned(key: String, value: Int) {
    assertThat(cache.mCachedEntries.contains(key))
        .describedAs("key not found in the cache")
        .isTrue()
    assertThat(cache.mLeastFrequentlyUsedExclusiveEntries.contains(key))
        .describedAs("key not found in the exclusives")
        .isTrue()
    val entry = cache.mCachedEntries.get(key)
    assertThat(entry).describedAs("entry not found in the cache").isNotNull()
    entry?.let { cacheEntry ->
      assertThat(cacheEntry.key).describedAs("key mismatch").isEqualTo(key)
      assertThat(cacheEntry.valueRef.get()).describedAs("value mismatch").isEqualTo(value)
      assertThat(cacheEntry.clientCount).describedAs("client count greater than zero").isEqualTo(0)
      assertThat(cacheEntry.isOrphan).describedAs("entry is an orphan").isFalse()
    }
  }

  private fun assertNotCached(key: String, value: Int) {
    assertThat(cache.mCachedEntries.contains(key)).describedAs("key found in the cache").isFalse()
    assertThat(
        cache.mLeastFrequentlyUsedExclusiveEntries.contains(key) ||
            cache.mMostFrequentlyUsedExclusiveEntries.contains(key),
    )
        .describedAs("key found in the exclusives")
        .isFalse()
  }

  private fun assertOrphanWithCount(entry: CountingMemoryCache.Entry<String, Int>, count: Int) {
    assertThat(entry)
        .describedAs("entry found in the cahce")
        .isNotSameAs(cache.mCachedEntries.get(entry.key))
    assertThat(entry)
        .describedAs("entry found in the LFU execlusive")
        .isNotSameAs(cache.mLeastFrequentlyUsedExclusiveEntries.get(entry.key))
    assertThat(entry)
        .describedAs("entry found in the MFU execlusive")
        .isNotSameAs(cache.mMostFrequentlyUsedExclusiveEntries.get(entry.key))
    assertThat(entry.isOrphan).describedAs("entry is not an orphan").isTrue()
    assertThat(entry.clientCount).describedAs("client count mismatch").isEqualTo(count)
  }

  private fun assertTotalSize(count: Int, bytes: Int) {
    assertThat(cache.count).describedAs("total cache count mismatch").isEqualTo(count)
    assertThat(cache.sizeInBytes).describedAs("total cache size mismatch").isEqualTo(bytes)
  }

  private fun assertExclusivelyOwnedSize(count: Int, bytes: Int) {
    assertThat(cache.evictionQueueCount)
        .describedAs("total exclusives count mismatch")
        .isEqualTo(count)
    assertThat(cache.evictionQueueSizeInBytes)
        .describedAs("total exclusives size mismatch")
        .isEqualTo(bytes)
  }

  private fun createDummyAdaptiveCountingMemoryCache(
      memoryCacheParamsSupplier: Supplier<MemoryCacheParams>,
      cacheTrimStrategy: MemoryCache.CacheTrimStrategy,
      valueDescriptor: ValueDescriptor<Int>,
      adaptiveRatePromil: Int,
      frequentlyUsedThreshold: Int,
      ghostListMaxSize: Int,
      lfuFractionPromil: Int,
  ): AbstractAdaptiveCountingMemoryCache<String, Int> {
    return object :
        AbstractAdaptiveCountingMemoryCache<String, Int>(
            memoryCacheParamsSupplier,
            cacheTrimStrategy,
            valueDescriptor,
            adaptiveRatePromil,
            frequentlyUsedThreshold,
            ghostListMaxSize,
            lfuFractionPromil,
        ) {
      override fun logIllegalLfuFraction() {}

      override fun logIllegalAdaptiveRate() {}

      override val debugData: String?
        get() = null
    }
  }
}
