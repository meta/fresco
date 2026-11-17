/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import android.util.Pair
import com.facebook.common.internal.Preconditions
import com.facebook.infer.annotation.Nullsafe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import javax.annotation.concurrent.GuardedBy

/**
 * Only permits a configurable number of requests to be kicked off simultaneously. If that number is
 * exceeded, then requests are queued up and kicked off once other requests complete.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
class ThrottlingProducer<T>(
    private val mMaxSimultaneousRequests: Long,
    executor: Executor,
    inputProducer: Producer<T>,
) : Producer<T> {
  private val inputProducer = Preconditions.checkNotNull(inputProducer)

  @GuardedBy("this") private var numCurrentRequests = 0L

  @GuardedBy("this")
  private val pendingRequests = ConcurrentLinkedQueue<Pair<Consumer<T>, ProducerContext>>()

  private val mExecutor = Preconditions.checkNotNull(executor)

  override fun produceResults(
      consumer: Consumer<T>,
      context: ProducerContext,
  ) {
    val producerListener = context.producerListener
    producerListener.onProducerStart(context, PRODUCER_NAME)

    val delayRequest: Boolean
    synchronized(this) {
      if (numCurrentRequests >= mMaxSimultaneousRequests) {
        pendingRequests.add(Pair.create(consumer, context))
        delayRequest = true
      } else {
        numCurrentRequests++
        delayRequest = false
      }
    }

    if (!delayRequest) {
      produceResultsInternal(consumer, context)
    }
  }

  fun produceResultsInternal(consumer: Consumer<T>, producerContext: ProducerContext) {
    val producerListener = producerContext.producerListener
    producerListener.onProducerFinishWithSuccess(producerContext, PRODUCER_NAME, null)
    inputProducer.produceResults(ThrottlerConsumer(consumer), producerContext)
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
      val nextRequestPair: Pair<Consumer<T>, ProducerContext>?
      synchronized(this@ThrottlingProducer) {
        nextRequestPair = pendingRequests.poll()
        if (nextRequestPair == null) {
          numCurrentRequests--
        }
      }

      if (nextRequestPair != null) {
        mExecutor.execute {
          produceResultsInternal(
              nextRequestPair.first,
              nextRequestPair.second,
          )
        }
      }
    }
  }

  companion object {
    const val PRODUCER_NAME: String = "ThrottlingProducer"
  }
}
