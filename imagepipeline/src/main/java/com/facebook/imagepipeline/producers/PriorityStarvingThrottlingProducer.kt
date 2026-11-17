/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.common.internal.Preconditions
import com.facebook.infer.annotation.Nullsafe
import java.util.PriorityQueue
import java.util.Queue
import java.util.concurrent.Executor
import javax.annotation.concurrent.GuardedBy

/**
 * Time based, priority starving throttling producer.
 *
 * This means for any # of elements, all of the higher priority items will be run before any of the
 * lower priority items. Within the groups, send order is the same as order they were given to the
 * class. (based on nano time).
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
class PriorityStarvingThrottlingProducer<T>(
    private val mMaxSimultaneousRequests: Int,
    executor: Executor,
    inputProducer: Producer<T>,
) : Producer<T> {
  private val inputProducer = Preconditions.checkNotNull(inputProducer)

  @GuardedBy("this") private val pendingRequests: Queue<Item<T>>

  private val mExecutor = Preconditions.checkNotNull(executor)

  @GuardedBy("this") private var numCurrentRequests = 0

  init {
    pendingRequests = PriorityQueue(11, PriorityComparator())
  }

  override fun produceResults(
      consumer: Consumer<T>,
      context: ProducerContext,
  ) {
    val time = System.nanoTime()
    val producerListener = context.producerListener
    producerListener.onProducerStart(context, PRODUCER_NAME)
    val delayRequest: Boolean
    synchronized(this) {
      if (numCurrentRequests >= mMaxSimultaneousRequests) {
        pendingRequests.add(Item(consumer, context, time))
        delayRequest = true
      } else {
        numCurrentRequests++
        delayRequest = false
      }
    }

    if (!delayRequest) {
      produceResultsInternal(Item(consumer, context, time))
    }
  }

  private fun produceResultsInternal(item: Item<T>) {
    val producerListener = item.producerContext.producerListener
    producerListener.onProducerFinishWithSuccess(item.producerContext, PRODUCER_NAME, null)
    inputProducer.produceResults(ThrottlerConsumer(item.consumer), item.producerContext)
  }

  internal class Item<T>(
      @field:JvmField val consumer: Consumer<T>,
      @field:JvmField val producerContext: ProducerContext,
      @field:JvmField val time: Long,
  )

  internal class PriorityComparator<T> : Comparator<Item<T>> {
    override fun compare(o1: Item<T>, o2: Item<T>): Int {
      val p1 = o1.producerContext.priority
      val p2 = o2.producerContext.priority

      return if (p1 == p2) {
        // lower time wins in this case, so minimum value
        java.lang.Double.compare(o1.time.toDouble(), o2.time.toDouble())
      } else {
        // priority ordering is via ordinals
        if (p1.ordinal > p2.ordinal) {
          // higher priority gets minimum value (pq is min-heap based)
          -1
        } else {
          1
        }
      }
    }
  }

  private inner class ThrottlerConsumer(consumer: Consumer<T>) :
      DelegatingConsumer<T, T>(consumer) {
    override fun onNewResultImpl(newResult: T?, @Consumer.Status status: Int) {
      consumer.onNewResult(newResult, status)
      if (BaseConsumer.isLast(status)) {
        onRequestFinished()
      }
    }

    override fun onFailureImpl(t: Throwable) {
      consumer.onFailure(t)
      onRequestFinished()
    }

    override fun onCancellationImpl() {
      consumer.onCancellation()
      onRequestFinished()
    }

    fun onRequestFinished() {
      val nextRequest: Item<T>?
      synchronized(this@PriorityStarvingThrottlingProducer) {
        nextRequest = pendingRequests.poll()
        if (nextRequest == null) {
          numCurrentRequests--
        }
      }

      if (nextRequest != null) {
        mExecutor.execute { produceResultsInternal(nextRequest) }
      }
    }
  }

  companion object {
    const val PRODUCER_NAME: String = "PriorityStarvingThrottlingProducer"
  }
}
