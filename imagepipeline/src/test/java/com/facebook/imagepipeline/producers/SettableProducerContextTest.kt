/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.request.ImageRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks basic properties of SettableProducerContext. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SettableProducerContextTest {
  @Mock private lateinit var imageRequest: ImageRequest

  @Mock private lateinit var config: ImagePipelineConfig

  private val requestId = "requestId"
  private lateinit var callbacks1: ProducerContextCallbacks
  private lateinit var callbacks2: ProducerContextCallbacks
  private lateinit var settableProducerContext: SettableProducerContext

  @Before
  fun setUp() {
    MockitoAnnotations.openMocks(this).close()
    settableProducerContext =
        SettableProducerContext(
            imageRequest,
            requestId,
            Mockito.mock(ProducerListener2::class.java),
            Mockito.mock(Any::class.java),
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            true,
            Priority.MEDIUM,
            config,
        )
    callbacks1 = Mockito.mock(ProducerContextCallbacks::class.java)
    callbacks2 = Mockito.mock(ProducerContextCallbacks::class.java)
  }

  @Test
  fun testGetters() {
    assertThat(settableProducerContext.imageRequest).isEqualTo(imageRequest)
    assertThat(settableProducerContext.id).isEqualTo(requestId)
  }

  @Test
  fun contextCopiesPreserveOptionalConfigAndRequestMetadata() {
    val overrideRequest = Mockito.mock(ImageRequest::class.java)
    for (pipelineConfig in listOf(config, null)) {
      val source = SettableProducerContext(
          imageRequest,
          requestId,
          "screen",
          settableProducerContext.producerListener,
          settableProducerContext.callerContext,
          ImageRequest.RequestLevel.FULL_FETCH,
          true,
          true,
          Priority.HIGH,
          pipelineConfig,
      )
      source.putExtra("copied", "value")
      for ((copy, request) in
          listOf(source, object : ProducerContext by source {}).flatMap { context ->
            listOf(
                SettableProducerContext(context) to imageRequest,
                SettableProducerContext(overrideRequest, context) to overrideRequest,
            )
          }) {
        assertThat(copy.imageRequest).isSameAs(request)
        assertThat(copy.id).isEqualTo(requestId)
        assertThat(copy.uiComponentId).isEqualTo("screen")
        assertThat(copy.getExtra<String>("copied")).isEqualTo("value")
        assertThat(copy.isPrefetch).isTrue()
        assertThat(copy.priority).isEqualTo(Priority.HIGH)
        assertThat(BaseProducerContext.getImagePipelineConfigOrNull(copy)).isSameAs(pipelineConfig)
      }
    }
  }

  @Test
  fun optionalConfigAccessorSupportsOtherProducerContexts() {
    val context = Mockito.mock(ProducerContext::class.java)
    Mockito.`when`(context.imagePipelineConfig).thenReturn(config)

    assertThat(BaseProducerContext.getImagePipelineConfigOrNull(context)).isSameAs(config)
  }

  @Test
  fun testIsPrefetch() {
    assertThat(settableProducerContext.isPrefetch).isFalse()
  }

  @Test
  fun testCancellation() {
    settableProducerContext.addCallbacks(callbacks1)
    Mockito.verify(callbacks1, Mockito.never()).onCancellationRequested()
    settableProducerContext.cancel()
    Mockito.verify(callbacks1).onCancellationRequested()
    Mockito.verify(callbacks1, Mockito.never()).onIsPrefetchChanged()

    settableProducerContext.addCallbacks(callbacks2)
    Mockito.verify(callbacks2).onCancellationRequested()
    Mockito.verify(callbacks2, Mockito.never()).onIsPrefetchChanged()
  }

  @Test
  fun emptyCallbackSnapshotsRemainMutableAndIndependent() {
    val snapshots = listOf(
        settableProducerContext.setIsPrefetchNoCallbacks(true),
        settableProducerContext.setPriorityNoCallbacks(Priority.HIGH),
        settableProducerContext.setIsIntermediateResultExpectedNoCallbacks(false),
        settableProducerContext.cancelNoCallbacks(),
    )
    for (snapshot in snapshots) {
      assertThat(snapshot).isNotNull().isEmpty()
      snapshot?.add(callbacks1)
    }
    for (snapshot in snapshots) {
      assertThat(snapshot).containsExactly(callbacks1)
    }
    assertThat(settableProducerContext.cancelNoCallbacks()).isNull()
    assertThat(settableProducerContext.setIsPrefetchNoCallbacks(false)).isEmpty()
    assertThat(settableProducerContext.setPriorityNoCallbacks(Priority.LOW)).isEmpty()
    assertThat(settableProducerContext.setIsIntermediateResultExpectedNoCallbacks(true)).isEmpty()
  }

  @Test
  fun cancellationPreservesOrderAndAllowsReentrantRegistrationOutsideMonitor() {
    val events = mutableListOf<String>()
    val lateCallback =
        object : BaseProducerContextCallbacks() {
          override fun onCancellationRequested() {
            assertThat(Thread.holdsLock(settableProducerContext)).isFalse()
            events.add("late")
          }
        }
    settableProducerContext.addCallbacks(
        object : BaseProducerContextCallbacks() {
          override fun onCancellationRequested() {
            assertThat(Thread.holdsLock(settableProducerContext)).isFalse()
            events.add("first")
            settableProducerContext.addCallbacks(lateCallback)
            settableProducerContext.cancel()
          }
        },
    )
    settableProducerContext.addCallbacks(
        object : BaseProducerContextCallbacks() {
          override fun onCancellationRequested() {
            assertThat(Thread.holdsLock(settableProducerContext)).isFalse()
            events.add("second")
          }
        },
    )

    settableProducerContext.cancel()
    settableProducerContext.cancel()

    assertThat(events).containsExactly("first", "late", "second")
  }

  @Test
  fun callbacksAddedAfterEmptyCancellationStillReceivePropertyChanges() {
    settableProducerContext.cancel()
    settableProducerContext.addCallbacks(callbacks1)
    settableProducerContext.cancel()
    settableProducerContext.setIsPrefetch(true)
    settableProducerContext.setPriority(Priority.HIGH)
    settableProducerContext.setIsIntermediateResultExpected(false)

    Mockito.verify(callbacks1).onCancellationRequested()
    Mockito.verify(callbacks1).onIsPrefetchChanged()
    Mockito.verify(callbacks1).onPriorityChanged()
    Mockito.verify(callbacks1).onIsIntermediateResultExpectedChanged()
    Mockito.verifyNoMoreInteractions(callbacks1)
  }

  @Test
  fun snapshotsDoNotIncludeLaterRegistrationsOrMutateCallbackStorage() {
    settableProducerContext.addCallbacks(callbacks1)
    val prefetchSnapshot = settableProducerContext.setIsPrefetchNoCallbacks(true)
    val prioritySnapshot = settableProducerContext.setPriorityNoCallbacks(Priority.HIGH)
    val intermediateSnapshot =
        settableProducerContext.setIsIntermediateResultExpectedNoCallbacks(false)
    val cancellationSnapshot = settableProducerContext.cancelNoCallbacks()
    settableProducerContext.addCallbacks(callbacks2)

    for (snapshot in
        listOf(
            prefetchSnapshot,
            prioritySnapshot,
            intermediateSnapshot,
            cancellationSnapshot,
        )) {
      assertThat(snapshot).containsExactly(callbacks1)
      snapshot?.clear()
    }
    settableProducerContext.setIsPrefetch(false)
    settableProducerContext.setPriority(Priority.LOW)
    settableProducerContext.setIsIntermediateResultExpected(true)

    Mockito.verify(callbacks1).onIsPrefetchChanged()
    Mockito.verify(callbacks2).onIsPrefetchChanged()
    Mockito.verify(callbacks1).onPriorityChanged()
    Mockito.verify(callbacks2).onPriorityChanged()
    Mockito.verify(callbacks1).onIsIntermediateResultExpectedChanged()
    Mockito.verify(callbacks2).onIsIntermediateResultExpectedChanged()
    Mockito.verify(callbacks2).onCancellationRequested()
    Mockito.verify(callbacks1, Mockito.never()).onCancellationRequested()
  }

  @Test
  fun cancellingOneContextDoesNotCancelItsCopy() {
    val copy = SettableProducerContext(settableProducerContext)
    settableProducerContext.addCallbacks(callbacks1)
    copy.addCallbacks(callbacks2)

    settableProducerContext.cancel()

    assertThat(copy.isCancelled).isFalse()
    Mockito.verify(callbacks1).onCancellationRequested()
    Mockito.verifyNoInteractions(callbacks2)
    copy.cancel()
    Mockito.verify(callbacks2).onCancellationRequested()
  }

  @Test
  fun cancellationExceptionDoesNotChangeSnapshotOrLateRegistrationSemantics() {
    val failure = IllegalStateException("callback failure")
    settableProducerContext.addCallbacks(
        object : BaseProducerContextCallbacks() {
          override fun onCancellationRequested() {
            throw failure
          }
        },
    )
    settableProducerContext.addCallbacks(callbacks1)
    var caught: Throwable? = null
    try {
      settableProducerContext.cancel()
    } catch (throwable: IllegalStateException) {
      caught = throwable
    }

    assertThat(caught).isSameAs(failure)
    assertThat(settableProducerContext.isCancelled).isTrue()
    settableProducerContext.cancel()
    settableProducerContext.addCallbacks(callbacks2)
    Mockito.verify(callbacks1, Mockito.never()).onCancellationRequested()
    Mockito.verify(callbacks2).onCancellationRequested()
  }

  @Test(timeout = 30000)
  fun concurrentRegistrationAndCancellationNotifyEachCallbackExactlyOnce() {
    val executor = Executors.newFixedThreadPool(3)
    try {
      repeat(200) {
        val context = SettableProducerContext(settableProducerContext)
        val start = CountDownLatch(1)
        val counts = List(16) { AtomicInteger() }
        val callbacks = counts.map { count ->
          object : BaseProducerContextCallbacks() {
            override fun onCancellationRequested() {
              assertThat(Thread.holdsLock(context)).isFalse()
              count.incrementAndGet()
            }
          }
        }
        val registrations = executor.submit {
          assertThat(start.await(5, TimeUnit.SECONDS)).isTrue()
          callbacks.forEach { context.addCallbacks(it) }
        }
        val cancellations =
            List(2) {
              executor.submit {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue()
                context.cancel()
              }
            }
        start.countDown()
        registrations.get(5, TimeUnit.SECONDS)
        cancellations.forEach { it.get(5, TimeUnit.SECONDS) }
        assertThat(context.isCancelled).isTrue()
        assertThat(counts.map { it.get() }).containsOnly(1)
      }
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
    }
  }

  @Test
  fun testSetPrefetch() {
    settableProducerContext.addCallbacks(callbacks1)
    assertThat(settableProducerContext.isPrefetch).isFalse()
    settableProducerContext.setIsPrefetch(true)
    assertThat(settableProducerContext.isPrefetch).isTrue()
    Mockito.verify(callbacks1).onIsPrefetchChanged()
    settableProducerContext.setIsPrefetch(true)
    // only one callback is expected
    Mockito.verify(callbacks1).onIsPrefetchChanged()
  }

  @Test
  fun testSetIsIntermediateResultExpected() {
    settableProducerContext.addCallbacks(callbacks1)
    assertThat(settableProducerContext.isIntermediateResultExpected).isTrue()
    settableProducerContext.setIsIntermediateResultExpected(false)
    assertThat(settableProducerContext.isIntermediateResultExpected).isFalse()
    Mockito.verify(callbacks1).onIsIntermediateResultExpectedChanged()
    settableProducerContext.setIsIntermediateResultExpected(false)
    // only one callback is expected
    Mockito.verify(callbacks1).onIsIntermediateResultExpectedChanged()
  }

  @Test
  fun testNoCallbackCalledWhenIsPrefetchDoesNotChange() {
    assertThat(settableProducerContext.isPrefetch).isFalse()
    settableProducerContext.addCallbacks(callbacks1)
    settableProducerContext.setIsPrefetch(false)
    Mockito.verify(callbacks1, Mockito.never()).onIsPrefetchChanged()
  }

  @Test
  fun testCallbackCalledWhenIsPrefetchChanges() {
    assertThat(settableProducerContext.isPrefetch).isFalse()
    settableProducerContext.addCallbacks(callbacks1)
    settableProducerContext.addCallbacks(callbacks2)
    settableProducerContext.setIsPrefetch(true)
    assertThat(settableProducerContext.isPrefetch).isTrue()
    Mockito.verify(callbacks1).onIsPrefetchChanged()
    Mockito.verify(callbacks1, Mockito.never()).onCancellationRequested()
    Mockito.verify(callbacks2).onIsPrefetchChanged()
    Mockito.verify(callbacks2, Mockito.never()).onCancellationRequested()
  }
}
