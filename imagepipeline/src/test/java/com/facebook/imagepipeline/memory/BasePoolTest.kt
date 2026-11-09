/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.memory

import android.util.SparseIntArray
import com.facebook.common.internal.ImmutableMap
import com.facebook.common.internal.Preconditions
import com.facebook.common.memory.MemoryTrimmableRegistry
import com.facebook.imagepipeline.memory.BasePool.InvalidSizeException
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

/** Tests for BasePool */
@RunWith(RobolectricTestRunner::class)
class BasePoolTest {
  private var mPool: TestPool? = null
  private var mStats: PoolStats<ByteArray>? = null

  @Before
  fun setup() {
    mPool = TestPool()
    mStats = PoolStats<ByteArray>(mPool!!)
  }

  // Test out the alloc method
  @Test
  @Throws(Exception::class)
  fun testAlloc() {
    Assertions.assertThat(mPool!!.alloc(1).size).isEqualTo(1)
    Assertions.assertThat(mPool!!.alloc(3).size).isEqualTo(3)
    Assertions.assertThat(mPool!!.alloc(2).size).isEqualTo(2)
  }

  @Test @Throws(Exception::class) fun testFree() = Unit

  // tests out the getBucketedSize method
  @Test
  @Throws(Exception::class)
  fun testGetBucketedSize() {
    Assertions.assertThat(mPool!!.getBucketedSize(1)).isEqualTo(2)
    Assertions.assertThat(mPool!!.getBucketedSize(2)).isEqualTo(2)
    Assertions.assertThat(mPool!!.getBucketedSize(3)).isEqualTo(4)
    Assertions.assertThat(mPool!!.getBucketedSize(6)).isEqualTo(6)
    Assertions.assertThat(mPool!!.getBucketedSize(7)).isEqualTo(8)
  }

  // tests out the getBucketedSize method for invalid inputs
  @Test
  @Throws(Exception::class)
  fun testGetBucketedSize_Invalid() {
    val sizes = intArrayOf(-1, 0)
    for (s in sizes) {
      val size = s
      Assertions.assertThatThrownBy(
              ThrowableAssert.ThrowingCallable { mPool!!.getBucketedSize(size) },
          )
          .isInstanceOf(InvalidSizeException::class.java)
    }
  }

  // tests out the getBucketedSizeForValue method
  @Test
  @Throws(Exception::class)
  fun testGetBucketedSizeForValue() {
    Assertions.assertThat(mPool!!.getBucketedSizeForValue(ByteArray(2))).isEqualTo(2)
    Assertions.assertThat(mPool!!.getBucketedSizeForValue(ByteArray(3))).isEqualTo(3)
    Assertions.assertThat(mPool!!.getBucketedSizeForValue(ByteArray(6))).isEqualTo(6)
  }

  @Test
  @Throws(Exception::class)
  fun testGetSizeInBytes() {
    Assertions.assertThat(mPool!!.getSizeInBytes(1)).isEqualTo(1)
    Assertions.assertThat(mPool!!.getSizeInBytes(2)).isEqualTo(2)
    Assertions.assertThat(mPool!!.getSizeInBytes(3)).isEqualTo(3)
    Assertions.assertThat(mPool!!.getSizeInBytes(5)).isEqualTo(5)
    Assertions.assertThat(mPool!!.getSizeInBytes(4)).isEqualTo(4)
  }

  // Get via alloc
  @Test
  @Throws(Exception::class)
  fun testGet_Alloc() {
    // get a buffer - causes an alloc
    val b1 = mPool!!.get(1)
    Assertions.assertThat(b1).isNotNull()
    Assertions.assertThat(b1.size).isEqualTo(2)
    Assertions.assertThat(mPool!!.inUseValues.contains(b1)).isTrue()
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(1, 0)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)

    // release this buffer
    mPool!!.release(b1)
    Assertions.assertThat(mPool!!.inUseValues.contains(b1)).isFalse()

    // get another buffer, but of a different size. No reuse possible
    val b2 = mPool!!.get(3)
    Assertions.assertThat(b2).isNotNull()
    Assertions.assertThat(b2.size).isEqualTo(4)
    Assertions.assertThat(mPool!!.inUseValues.contains(b2)).isTrue()
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(0, 1), 4, IntPair(1, 0)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(4)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(1)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)
  }

  // Tests that we can reuse a free buffer in the pool
  @Test
  @Throws(Exception::class)
  fun testGet_Reuse() {
    // get a buffer, and immediately release it
    val b1 = mPool!!.get(1)
    mPool!!.release(b1)
    Assertions.assertThat(b1).isNotNull()
    Assertions.assertThat(b1.size).isEqualTo(2)
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(0, 1)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(1)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(0)

    // get another buffer of the same size as above. We should be able to reuse it
    val b2 = mPool!!.get(1)
    Assertions.assertThat(b2).isNotNull()
    Assertions.assertThat(b2.size).isEqualTo(2)
    Assertions.assertThat(mPool!!.inUseValues.contains(b2)).isTrue()
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(1, 0)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)
  }

  // test a simple release
  @Test
  @Throws(Exception::class)
  fun testRelease() {
    // get a buffer - causes an alloc
    val b1 = mPool!!.get(1)
    // release this buffer
    mPool!!.release(b1)

    // verify stats
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(0, 1)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(1)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(0)
  }

  // Test release with bucket length constraints
  @Test
  @Throws(Exception::class)
  fun testRelease_BucketLengths() {
    mPool = TestPool(makeBucketSizeArray(2, 2))
    mStats!!.setPool(mPool!!)

    val b0 = mPool!!.get(2)
    mPool!!.get(2)
    mPool!!.get(2)
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(3, 0)))
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(6)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(3)
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)

    // now release one of the buffers
    mPool!!.release(b0)

    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(2, 0)))
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(4)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(2)
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
  }

  // Test releasing an 'unknown' value
  @Test
  @Throws(Exception::class)
  fun testRelease_UnknownValue() {
    // get a buffer from the pool
    mPool!!.get(1)

    // allocate a buffer outside the pool
    val b2 = ByteArray(2)
    // try to release this buffer to the pool
    mPool!!.release(b2)

    // verify stats
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(1, 0)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)
  }

  // test out release with non reusable values
  @Test
  @Throws(Exception::class)
  fun testRelease_NonReusable() {
    val pool = TestPool(makeBucketSizeArray(2, 3))
    mPool!!.mIsReusable = false
    mStats!!.setPool(pool)

    // get a buffer, and then release it
    val b1 = mPool!!.get(2)
    mPool!!.release(b1)

    // verify stats
    mStats!!.refresh()
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(ImmutableMap.of<Int, IntPair>(2, IntPair(0, 0)))
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(0)
  }

  // test buffers outside the 'normal' bucket sizes
  @Test
  @Throws(Exception::class)
  fun testGetRelease_NonBucketSizes() {
    mPool = TestPool(makeBucketSizeArray(2, 1, 4, 1, 6, 1))
    mStats!!.setPool(mPool!!)

    mPool!!.get(2)
    val b1 = mPool!!.get(7)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(10)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(2)
    mPool!!.release(b1)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)

    val b2 = ByteArray(3)
    mPool!!.release(b2)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedCount).isEqualTo(1)
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.freeCount).isEqualTo(0)
  }

  // test illegal arguments to get
  @Test
  @Throws(Exception::class)
  fun testGetWithErrors() {
    val sizes = intArrayOf(-1, 0)
    for (s in sizes) {
      val size = s
      Assertions.assertThatThrownBy(ThrowableAssert.ThrowingCallable { mPool!!.get(size) })
          .isInstanceOf(InvalidSizeException::class.java)
    }
  }

  // test out trimToNothing functionality
  @Test
  @Throws(Exception::class)
  fun testTrimToNothing() {
    // alloc a buffer and then release it
    val b1 = mPool!!.get(1)
    mPool!!.release(b1)
    mPool!!.get(3)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(2)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(4)

    // trim the pool and check
    mPool!!.trimToNothing()
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(4)
  }

  // test out trimToSize functionality
  @Test
  @Throws(Exception::class)
  fun testTrimToSize() {
    mPool = TestPool(makeBucketSizeArray(2, 2, 4, 2, 6, 2))
    mStats!!.setPool(mPool!!)

    // allocate and release multiple buffers
    var b1: ByteArray?
    mPool!!.get(2)
    b1 = mPool!!.get(2)
    mPool!!.release(b1)
    b1 = mPool!!.get(6)
    mPool!!.release(b1)
    b1 = mPool!!.get(4)
    mPool!!.release(b1)

    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(12)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)

    // perform a dummy trim - nothing should happen
    mPool!!.trimToSize(100)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(12)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)

    // now perform the real trim
    mPool!!.trimToSize(8)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(6)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(
            ImmutableMap.of<Int, IntPair>(2, IntPair(1, 0), 4, IntPair(0, 0), 6, IntPair(0, 1)),
        )

    // perform another trim
    mPool!!.trimToSize(1)
    mStats!!.refresh()
    Assertions.assertThat(mStats!!.freeBytes).isEqualTo(0)
    Assertions.assertThat(mStats!!.usedBytes).isEqualTo(2)
    Assertions.assertThat<Int, IntPair>(mStats!!.bucketStats)
        .isEqualTo(
            ImmutableMap.of<Int, IntPair>(2, IntPair(1, 0), 4, IntPair(0, 0), 6, IntPair(0, 0)),
        )
  }

  /**
   * A simple test pool that allocates byte arrays, and always allocates buffers of double the size
   * requested
   */
  class TestPool @JvmOverloads constructor(bucketSizes: SparseIntArray? = null) :
      BasePool<ByteArray>(
          Mockito.mock<MemoryTrimmableRegistry>(MemoryTrimmableRegistry::class.java),
          PoolParams(bucketSizes),
          Mockito.mock<PoolStatsTracker>(PoolStatsTracker::class.java),
      ) {
    var mIsReusable: Boolean = true

    init {
      initialize()
    }

    public override fun alloc(bucketedSize: Int): ByteArray {
      return ByteArray(bucketedSize)
    }

    override fun free(value: ByteArray) = Unit

    override fun isReusable(value: ByteArray): Boolean {
      return mIsReusable
    }

    /**
     * Allocate the smallest even number than is greater than or equal to the requested size
     *
     * @param requestSize the logical request size
     * @return the slightly higher size
     */
    public override fun getBucketedSize(requestSize: Int): Int {
      if (requestSize <= 0) {
        throw InvalidSizeException(requestSize)
      }

      return if (requestSize % 2 == 0) requestSize else requestSize + 1
    }

    public override fun getBucketedSizeForValue(value: ByteArray): Int {
      return value.size
    }

    public override fun getSizeInBytes(bucketedSize: Int): Int {
      return bucketedSize
    }
  }

  companion object {
    private fun makeBucketSizeArray(vararg params: Int): SparseIntArray {
      Preconditions.checkArgument(params.size % 2 == 0)
      val bucketSizes = SparseIntArray()
      var i = 0
      while (i < params.size) {
        bucketSizes.append(params[i], params[i + 1])
        i += 2
      }
      return bucketSizes
    }
  }
}
