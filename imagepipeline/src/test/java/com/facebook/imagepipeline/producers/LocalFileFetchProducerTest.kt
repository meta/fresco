/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.memory.PooledByteBufferFactory
import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.testing.FakeClock
import com.facebook.imagepipeline.testing.TestExecutorService
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Basic tests for LocalFileFetchProducer */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalFileFetchProducerTest {
  @Mock lateinit var pooledByteBufferFactory: PooledByteBufferFactory
  @Mock lateinit var consumer: Consumer<EncodedImage?>
  @Mock lateinit var imageRequest: ImageRequest
  @Mock lateinit var producerListener: ProducerListener2
  @Mock lateinit var exception: Exception
  @Mock lateinit var config: ImagePipelineConfig
  private lateinit var executor: TestExecutorService
  private lateinit var producerContext: SettableProducerContext
  private val requestId = "requestId"
  private lateinit var file: File
  private lateinit var localFileFetchProducer: LocalFileFetchProducer
  private var capturedEncodedImage: EncodedImage? = null

  @Before
  @Throws(Exception::class)
  fun setUp() {
    MockitoAnnotations.openMocks(this).close()
    executor = TestExecutorService(FakeClock())
    localFileFetchProducer = LocalFileFetchProducer(executor, pooledByteBufferFactory)
    file = File(RuntimeEnvironment.application.getExternalFilesDir(null), TEST_FILENAME)
    val bos = BufferedOutputStream(FileOutputStream(file))
    bos.write(ByteArray(INPUT_STREAM_LENGTH), 0, INPUT_STREAM_LENGTH)
    bos.close()

    producerContext =
        SettableProducerContext(
            imageRequest,
            requestId,
            producerListener,
            Mockito.mock<Any?>(Any::class.java),
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            true,
            Priority.MEDIUM,
            config,
        )
    Mockito.`when`<File?>(imageRequest.getSourceFile()).thenReturn(file)
    Mockito.doAnswer(
            object : Answer<Any?> {
              @Throws(Throwable::class)
              override fun answer(invocation: InvocationOnMock): Any? {
                capturedEncodedImage =
                    EncodedImage.cloneOrNull(invocation.getArguments()[0] as EncodedImage?)
                return null
              }
            },
        )
        .`when`<Consumer<EncodedImage?>>(consumer)
        .onNewResult(
            ArgumentMatchers.notNull<EncodedImage?>(EncodedImage::class.java),
            ArgumentMatchers.anyInt(),
        )
  }

  @Test
  fun testLocalFileFetchCancelled() {
    localFileFetchProducer.produceResults(consumer, producerContext)
    producerContext.cancel()
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerStart(producerContext, PRODUCER_NAME)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerFinishWithCancellation(producerContext, PRODUCER_NAME, null)
    Mockito.verify<Consumer<EncodedImage?>>(consumer).onCancellation()
    executor.runUntilIdle()
    verifyNoMoreInteractions(pooledByteBufferFactory)
  }

  @Test
  @Throws(Exception::class)
  fun testFetchLocalFile() {
    val pooledByteBuffer = Mockito.mock<PooledByteBuffer?>(PooledByteBuffer::class.java)
    Mockito.`when`<PooledByteBuffer?>(
            pooledByteBufferFactory.newByteBuffer(
                ArgumentMatchers.any<InputStream?>(InputStream::class.java),
                ArgumentMatchers.eq(INPUT_STREAM_LENGTH),
            ),
        )
        .thenReturn(pooledByteBuffer)
    localFileFetchProducer.produceResults(consumer, producerContext)
    executor.runUntilIdle()
    assertThat(
        capturedEncodedImage
            ?.getByteBufferRef()
            ?.getUnderlyingReferenceTestOnly()
            ?.getRefCountTestOnly()
            ?.toLong(),
    )
        .isEqualTo(2)
    assertThat(capturedEncodedImage?.getByteBufferRef()?.get()).isSameAs(pooledByteBuffer)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerStart(producerContext, PRODUCER_NAME)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerFinishWithSuccess(producerContext, PRODUCER_NAME, null)
    Mockito.verify<ProducerListener2>(producerListener)
        .onUltimateProducerReached(producerContext, PRODUCER_NAME, true)
  }

  @Test(expected = RuntimeException::class)
  @Throws(Exception::class)
  fun testFetchLocalFileFailsByThrowing() {
    Mockito.`when`<PooledByteBuffer?>(
            pooledByteBufferFactory.newByteBuffer(
                ArgumentMatchers.any<InputStream?>(InputStream::class.java),
                ArgumentMatchers.eq(INPUT_STREAM_LENGTH),
            ),
        )
        .thenThrow(exception)
    Mockito.verify<Consumer<EncodedImage?>>(consumer).onFailure(exception)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerStart(producerContext, PRODUCER_NAME)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerFinishWithFailure(producerContext, PRODUCER_NAME, exception, null)
    Mockito.verify<ProducerListener2>(producerListener)
        .onUltimateProducerReached(producerContext, PRODUCER_NAME, false)
  }

  @After
  @Throws(Exception::class)
  fun tearDown() {
    file.delete()
  }

  companion object {
    private val PRODUCER_NAME = LocalFileFetchProducer.PRODUCER_NAME
    private const val INPUT_STREAM_LENGTH = 100
    private const val TEST_FILENAME = "dummy.jpg"
  }
}
