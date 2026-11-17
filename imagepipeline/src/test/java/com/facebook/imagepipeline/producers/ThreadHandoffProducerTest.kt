/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.core.ImagePipelineExperiments
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.testing.FakeClock
import com.facebook.imagepipeline.testing.TestExecutorService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ThreadHandoffProducerTest {
  @Mock private lateinit var inputProducer: Producer<Any?>

  @Mock private lateinit var consumer: Consumer<Any?>

  @Mock private lateinit var imageRequest: ImageRequest

  @Mock private lateinit var producerListener: ProducerListener2

  @Mock private lateinit var config: ImagePipelineConfig

  private val requestId = "requestId"
  private lateinit var producerContext: SettableProducerContext
  private lateinit var threadHandoffProducer: ThreadHandoffProducer<Any?>
  private lateinit var testExecutorService: TestExecutorService
  private lateinit var imagePipelineExperiments: ImagePipelineExperiments

  @Before
  fun setUp() {
    MockitoAnnotations.openMocks(this).close()
    producerContext =
        SettableProducerContext(
            imageRequest,
            requestId,
            producerListener,
            Mockito.mock(Any::class.java),
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            true,
            Priority.MEDIUM,
            config,
        )
    testExecutorService = TestExecutorService(FakeClock())
    threadHandoffProducer =
        ThreadHandoffProducer(inputProducer, ThreadHandoffProducerQueueImpl(testExecutorService))

    imagePipelineExperiments = Mockito.mock(ImagePipelineExperiments::class.java)

    Mockito.doReturn(imagePipelineExperiments).`when`(config).experiments
    Mockito.doReturn(false).`when`(imagePipelineExperiments).handOffOnUiThreadOnly
    Mockito.doReturn({ true }).`when`(imagePipelineExperiments).isCriticalThread
  }

  @Test
  fun testSuccess() {
    threadHandoffProducer.produceResults(consumer, producerContext)
    testExecutorService.runUntilIdle()
    Mockito.verify(inputProducer).produceResults(consumer, producerContext)
    Mockito.verify(producerListener)
        .onProducerStart(producerContext, ThreadHandoffProducer.PRODUCER_NAME)
    Mockito.verify(producerListener)
        .onProducerFinishWithSuccess(producerContext, ThreadHandoffProducer.PRODUCER_NAME, null)
    Mockito.verifyNoMoreInteractions(producerListener)
  }

  @Test
  fun contextWithoutConfigUsesHandoff() {
    val context = SettableProducerContext(
        imageRequest,
        requestId,
        producerListener,
        null,
        ImageRequest.RequestLevel.FULL_FETCH,
        false,
        false,
        Priority.MEDIUM,
        null,
    )

    for (requestContext in listOf(context, object : ProducerContext by context {})) {
      threadHandoffProducer.produceResults(consumer, requestContext)

      Mockito.verify(inputProducer, Mockito.never()).produceResults(consumer, requestContext)
      testExecutorService.runUntilIdle()
      Mockito.verify(inputProducer).produceResults(consumer, requestContext)
    }
  }

  @Test
  fun contextWithoutConfigCanCancelBeforeHandoff() {
    val context = SettableProducerContext(
        imageRequest,
        requestId,
        producerListener,
        null,
        ImageRequest.RequestLevel.FULL_FETCH,
        false,
        false,
        Priority.MEDIUM,
        null,
    )
    threadHandoffProducer.produceResults(consumer, context)
    context.cancel()
    testExecutorService.runUntilIdle()

    Mockito.verify(inputProducer, Mockito.never()).produceResults(consumer, context)
    Mockito.verify(consumer).onCancellation()
  }

  @Test
  fun testCancellation() {
    threadHandoffProducer.produceResults(consumer, producerContext)
    producerContext.cancel()
    testExecutorService.runUntilIdle()
    Mockito.verify(inputProducer, Mockito.never()).produceResults(consumer, producerContext)
    Mockito.verify(consumer).onCancellation()
    Mockito.verify(producerListener)
        .onProducerStart(producerContext, ThreadHandoffProducer.PRODUCER_NAME)
    Mockito.verify(producerListener)
        .requiresExtraMap(producerContext, ThreadHandoffProducer.PRODUCER_NAME)
    Mockito.verify(producerListener)
        .onProducerFinishWithCancellation(
            producerContext,
            ThreadHandoffProducer.PRODUCER_NAME,
            null,
        )
    Mockito.verifyNoMoreInteractions(producerListener)
  }
}
