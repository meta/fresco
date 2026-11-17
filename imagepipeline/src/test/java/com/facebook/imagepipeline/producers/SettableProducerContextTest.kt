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
