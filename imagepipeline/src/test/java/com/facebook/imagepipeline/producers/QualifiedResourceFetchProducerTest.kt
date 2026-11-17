/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import android.content.ContentResolver
import android.net.Uri
import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.memory.PooledByteBufferFactory
import com.facebook.common.util.UriUtil
import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.testing.FakeClock
import com.facebook.imagepipeline.testing.TestExecutorService
import java.io.InputStream
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Basic tests for QualifiedResourceFetchProducer */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QualifiedResourceFetchProducerTest {
  @Mock lateinit var pooledByteBufferFactory: PooledByteBufferFactory
  @Mock lateinit var contentResolver: ContentResolver
  @Mock lateinit var consumer: Consumer<EncodedImage?>
  @Mock lateinit var imageRequest: ImageRequest
  @Mock lateinit var producerListener: ProducerListener2
  @Mock lateinit var exception: Exception
  @Mock lateinit var config: ImagePipelineConfig

  private lateinit var executor: TestExecutorService
  private lateinit var producerContext: SettableProducerContext
  private lateinit var contentUri: Uri
  private lateinit var qualifiedResourceFetchProducer: QualifiedResourceFetchProducer

  @Before
  @Throws(Exception::class)
  fun setUp() {
    MockitoAnnotations.openMocks(this).close()
    executor = TestExecutorService(FakeClock())
    qualifiedResourceFetchProducer =
        QualifiedResourceFetchProducer(executor, pooledByteBufferFactory, contentResolver)
    contentUri = UriUtil.getUriForQualifiedResource(PACKAGE_NAME, RESOURCE_ID)

    producerContext =
        SettableProducerContext(
            imageRequest,
            REQUEST_ID,
            producerListener,
            CALLER_CONTEXT,
            ImageRequest.RequestLevel.FULL_FETCH,
            false,
            true,
            Priority.MEDIUM,
            config,
        )
    Mockito.`when`<Uri?>(imageRequest.getSourceUri()).thenReturn(contentUri)
  }

  @Test
  @Throws(Exception::class)
  fun testQualifiedResourceUri() {
    val pooledByteBuffer = Mockito.mock<PooledByteBuffer?>(PooledByteBuffer::class.java)
    Mockito.`when`<PooledByteBuffer?>(
            pooledByteBufferFactory.newByteBuffer(
                ArgumentMatchers.any<InputStream?>(InputStream::class.java),
            ),
        )
        .thenReturn(pooledByteBuffer)

    Mockito.`when`<InputStream?>(contentResolver.openInputStream(contentUri))
        .thenReturn(Mockito.mock<InputStream?>(InputStream::class.java))

    qualifiedResourceFetchProducer.produceResults(consumer, producerContext)
    executor.runUntilIdle()

    Mockito.verify<PooledByteBufferFactory>(pooledByteBufferFactory, Mockito.times(1))
        .newByteBuffer(ArgumentMatchers.any<InputStream?>(InputStream::class.java))
    Mockito.verify<ContentResolver>(contentResolver, Mockito.times(1)).openInputStream(contentUri)

    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerStart(producerContext, PRODUCER_NAME)
    Mockito.verify<ProducerListener2>(producerListener)
        .onProducerFinishWithSuccess(producerContext, PRODUCER_NAME, null)
  }

  companion object {
    private val PRODUCER_NAME = QualifiedResourceFetchProducer.PRODUCER_NAME

    private const val PACKAGE_NAME = "com.myapp.myplugin"
    private const val RESOURCE_ID = 42

    private const val REQUEST_ID = "requestId"
    private const val CALLER_CONTEXT = "callerContext"
  }
}
