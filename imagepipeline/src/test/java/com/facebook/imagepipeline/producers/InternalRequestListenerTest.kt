/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.imagepipeline.listener.RequestListener
import com.facebook.imagepipeline.listener.RequestListener2
import com.facebook.imagepipeline.request.ImageRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.Mockito

class InternalRequestListenerTest {
  private val legacy = Mockito.mock(RequestListener::class.java)
  private val modern = Mockito.mock(RequestListener2::class.java)
  private val listener = InternalRequestListener(legacy, modern)

  private fun context(id: String, prefetch: Boolean): ProducerContext {
    val context = Mockito.mock(ProducerContext::class.java)
    Mockito.`when`(context.id).thenReturn(id)
    Mockito.`when`(context.imageRequest).thenReturn(Mockito.mock(ImageRequest::class.java))
    Mockito.`when`(context.callerContext).thenReturn(Any())
    Mockito.`when`(context.isPrefetch).thenReturn(prefetch)
    return context
  }

  @Test
  fun interleavedRequestsForwardTheirOwnContextInLegacyThenModernOrder() {
    val first = context("first", false)
    val second = context("second", true)
    val failure = IllegalStateException("failure")
    val extras = mapOf<String?, String?>("key" to "value")

    listener.onRequestStart(first)
    listener.onRequestStart(second)
    listener.onProducerStart(second, "producer")
    listener.onProducerEvent(first, "producer", "event")
    listener.onProducerFinishWithSuccess(second, "producer", extras)
    listener.onProducerFinishWithFailure(first, "producer", failure, extras)
    listener.onProducerFinishWithCancellation(second, "producer", extras)
    listener.onUltimateProducerReached(first, "producer", true)
    listener.onRequestSuccess(second)
    listener.onRequestFailure(first, failure)
    listener.onRequestCancellation(second)

    val order = Mockito.inOrder(legacy, modern)
    order.verify(legacy).onRequestStart(first.imageRequest, first.callerContext, "first", false)
    order.verify(modern).onRequestStart(first)
    order.verify(legacy).onRequestStart(second.imageRequest, second.callerContext, "second", true)
    order.verify(modern).onRequestStart(second)
    order.verify(legacy).onProducerStart("second", "producer")
    order.verify(modern).onProducerStart(second, "producer")
    order.verify(legacy).onProducerEvent("first", "producer", "event")
    order.verify(modern).onProducerEvent(first, "producer", "event")
    order.verify(legacy).onProducerFinishWithSuccess("second", "producer", extras)
    order.verify(modern).onProducerFinishWithSuccess(second, "producer", extras)
    order.verify(legacy).onProducerFinishWithFailure("first", "producer", failure, extras)
    order.verify(modern).onProducerFinishWithFailure(first, "producer", failure, extras)
    order.verify(legacy).onProducerFinishWithCancellation("second", "producer", extras)
    order.verify(modern).onProducerFinishWithCancellation(second, "producer", extras)
    order.verify(legacy).onUltimateProducerReached("first", "producer", true)
    order.verify(modern).onUltimateProducerReached(first, "producer", true)
    order.verify(legacy).onRequestSuccess(second.imageRequest, "second", true)
    order.verify(modern).onRequestSuccess(second)
    order.verify(legacy).onRequestFailure(first.imageRequest, "first", failure, false)
    order.verify(modern).onRequestFailure(first, failure)
    order.verify(legacy).onRequestCancellation("second")
    order.verify(modern).onRequestCancellation(second)
    order.verifyNoMoreInteractions()
  }

  @Test
  fun extraMapRequirementIsEvaluatedPerContextAndShortCircuitsLegacyTrue() {
    val first = context("first", false)
    val second = context("second", true)
    Mockito.`when`(legacy.requiresExtraMap("first")).thenReturn(true)
    Mockito.`when`(modern.requiresExtraMap(second, "producer")).thenReturn(true)

    assertThat(listener.requiresExtraMap(first, "producer")).isTrue()
    assertThat(listener.requiresExtraMap(second, "producer")).isTrue()
    Mockito.verify(modern, Mockito.never()).requiresExtraMap(first, "producer")
    Mockito.verify(modern).requiresExtraMap(second, "producer")
  }

  @Test
  fun absentLegacyListenerOnlyForwardsToModernListener() {
    val context = context("request", false)
    val modernOnly = InternalRequestListener(null, modern)
    modernOnly.onRequestStart(context)
    modernOnly.onProducerStart(context, "producer")
    modernOnly.onRequestCancellation(context)

    Mockito.verify(modern).onRequestStart(context)
    Mockito.verify(modern).onProducerStart(context, "producer")
    Mockito.verify(modern).onRequestCancellation(context)
    Mockito.verifyNoInteractions(legacy)
    assertThat(InternalRequestListener(null, null).requiresExtraMap(context, "producer")).isFalse()
  }

  @Test(timeout = 30000)
  fun sharedAdapterDoesNotMixConcurrentRequestContexts() {
    val contexts = listOf(context("first", false), context("second", true))
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val tasks = contexts.map { context ->
        executor.submit {
          assertThat(start.await(5, TimeUnit.SECONDS)).isTrue()
          repeat(100) {
            listener.onRequestStart(context)
            listener.onProducerStart(context, "producer")
            listener.onRequestSuccess(context)
            listener.onRequestCancellation(context)
          }
        }
      }
      start.countDown()
      tasks.forEach { it.get(10, TimeUnit.SECONDS) }
      for (context in contexts) {
        Mockito.verify(legacy, Mockito.times(100))
            .onRequestStart(
                context.imageRequest,
                context.callerContext,
                context.id,
                context.isPrefetch,
            )
        Mockito.verify(legacy, Mockito.times(100)).onProducerStart(context.id, "producer")
        Mockito.verify(legacy, Mockito.times(100))
            .onRequestSuccess(context.imageRequest, context.id, context.isPrefetch)
        Mockito.verify(legacy, Mockito.times(100)).onRequestCancellation(context.id)
        Mockito.verify(modern, Mockito.times(100)).onRequestStart(context)
        Mockito.verify(modern, Mockito.times(100)).onProducerStart(context, "producer")
        Mockito.verify(modern, Mockito.times(100)).onRequestSuccess(context)
        Mockito.verify(modern, Mockito.times(100)).onRequestCancellation(context)
      }
      Mockito.verifyNoMoreInteractions(legacy, modern)
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }
  }
}
