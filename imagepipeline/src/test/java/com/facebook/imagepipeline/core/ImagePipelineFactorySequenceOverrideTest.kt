/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.core

import android.content.Context
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.cache.CacheKeyFactory
import com.facebook.imagepipeline.cache.DefaultCacheKeyFactory
import com.facebook.imagepipeline.decoder.ImageDecoder
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.producers.Consumer
import com.facebook.imagepipeline.producers.NetworkFetcher
import com.facebook.imagepipeline.producers.Producer
import com.facebook.imagepipeline.producers.ProducerContext
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueueImpl
import com.facebook.imagepipeline.request.ImageRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.mock as typedMock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class ImagePipelineFactorySequenceOverrideTest {
  @Before
  fun setUp() {
    ImagePipelineFactory.forceSingleInstance(false)
    ImagePipelineFactory.shutDown()
  }

  @After
  fun tearDown() {
    ImagePipelineFactory.forceSingleInstance(false)
    ImagePipelineFactory.shutDown()
  }

  @Test
  fun suppliedPipelineReleasesThePreviousNativeFactory() {
    val factory = factoryFor(typedMock())
    ImagePipelineFactory.setInstance(factory)
    assertThat(ImagePipelineFactory.hasBeenInitialized()).isTrue()
    assertThat(ImagePipelineFactory.getInstance()).isSameAs(factory)
    val pipeline: ImagePipeline = typedMock()

    assertThat(ImagePipelineFactory.initialize(pipeline)).isSameAs(pipeline)

    assertThat(ImagePipelineFactory.getInstanceOrNull()).isNull()
    assertThat(ImagePipelineFactory.hasBeenInitialized()).isFalse()
    assertThat(ImagePipelineFactory.hasImagePipeline()).isTrue()
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
  }

  @Test
  fun explicitFactoryReplacementClearsTheSuppliedPipeline() {
    val previousPipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(previousPipeline)
    val pipeline: ImagePipeline = typedMock()
    val factory = factoryFor(pipeline)

    ImagePipelineFactory.setInstance(factory)

    assertThat(ImagePipelineFactory.getInstanceOrNull()).isSameAs(factory)
    assertThat(ImagePipelineFactory.hasBeenInitialized()).isTrue()
    assertThat(ImagePipelineFactory.hasImagePipeline()).isTrue()
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
    verify(previousPipeline).clearMemoryCaches()
  }

  @Test
  fun replacingASuppliedPipelineClearsItsMemoryCaches() {
    val previousPipeline: ImagePipeline = typedMock()
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(previousPipeline)

    ImagePipelineFactory.initialize(pipeline)

    verify(previousPipeline).clearMemoryCaches()
    verifyNoInteractions(pipeline)
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
  }

  @Test
  fun registeringTheSamePipelinePreservesItsMemoryCaches() {
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(pipeline)

    ImagePipelineFactory.initialize(pipeline)

    verifyNoInteractions(pipeline)
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
  }

  @Test
  fun shuttingDownASuppliedPipelineClearsItsMemoryCachesOnce() {
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(pipeline)

    ImagePipelineFactory.shutDown()
    ImagePipelineFactory.shutDown()

    verify(pipeline).clearMemoryCaches()
    assertThat(ImagePipelineFactory.hasImagePipeline()).isFalse()
  }

  @Test
  fun forceSingleInstanceKeepsTheSuppliedPipelineWhenAnotherPipelineIsSupplied() {
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(pipeline)
    ImagePipelineFactory.forceSingleInstance(true)

    assertThat(ImagePipelineFactory.initialize(typedMock<ImagePipeline>())).isSameAs(pipeline)
    assertThat(ImagePipelineFactory.getInstanceOrNull()).isNull()
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
    verifyNoInteractions(pipeline)
  }

  @Test
  fun forceSingleInstanceAllowsExplicitNativeConfigInitializationAfterSuppliedPipeline() {
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(pipeline)
    ImagePipelineFactory.forceSingleInstance(true)
    val config = ImagePipelineConfig.newBuilder(RuntimeEnvironment.getApplication()).build()

    ImagePipelineFactory.initialize(config)

    assertThat(ImagePipelineFactory.hasBeenInitialized()).isTrue()
    verify(pipeline).clearMemoryCaches()
    val factory = ImagePipelineFactory.getInstance()
    val unusedConfig: ImagePipelineConfigInterface = typedMock()
    ImagePipelineFactory.initialize(unusedConfig)
    assertThat(ImagePipelineFactory.getInstance()).isSameAs(factory)
    verifyNoInteractions(unusedConfig)
  }

  @Test
  fun forceSingleInstanceAllowsExplicitNativeContextInitializationAfterSuppliedPipeline() {
    val pipeline: ImagePipeline = typedMock()
    ImagePipelineFactory.initialize(pipeline)
    ImagePipelineFactory.forceSingleInstance(true)

    ImagePipelineFactory.initialize(RuntimeEnvironment.getApplication() as Context)

    assertThat(ImagePipelineFactory.hasBeenInitialized()).isTrue()
    verify(pipeline).clearMemoryCaches()
    val factory = ImagePipelineFactory.getInstance()
    val unusedContext: Context = typedMock()
    ImagePipelineFactory.initialize(unusedContext)
    assertThat(ImagePipelineFactory.getInstance()).isSameAs(factory)
    verifyNoInteractions(unusedContext)
  }

  @Test
  fun forceSingleInstanceKeepsTheNativeFactoryWhenAPipelineIsSupplied() {
    val pipeline: ImagePipeline = typedMock()
    val factory = factoryFor(pipeline)
    ImagePipelineFactory.setInstance(factory)
    ImagePipelineFactory.forceSingleInstance(true)

    assertThat(ImagePipelineFactory.initialize(typedMock<ImagePipeline>())).isSameAs(pipeline)

    assertThat(ImagePipelineFactory.getInstanceOrNull()).isSameAs(factory)
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
  }

  private fun factoryFor(pipeline: ImagePipeline): ImagePipelineFactory =
      typedMock<ImagePipelineFactory>().also { factory ->
        whenever(factory.imagePipeline).thenReturn(pipeline)
        whenever(factory.bitmapMemoryCache).thenReturn(typedMock())
        whenever(factory.encodedMemoryCache).thenReturn(typedMock())
      }

  @Test
  fun directPipelineWithoutHandoffQueueDoesNotPause() {
    val pipeline = ImagePipeline(
        typedMock(),
        typedMock(),
        typedMock(),
        DefaultCacheKeyFactory.getInstance(),
    )

    pipeline.pause()
    assertThat(pipeline.isPaused).isFalse()
    pipeline.resume()
    assertThat(pipeline.isPaused).isFalse()
  }

  @Test
  fun directPipelineSubmitsRequestsWithoutConfigOrFactory() {
    val replacement = mock(ProducerSequenceFactoryInterface::class.java)
    val request = requireNotNull(ImageRequest.fromUri("https://example.com/image"))
    val decodedProducer =
        object : Producer<CloseableReference<CloseableImage>> {
          override fun produceResults(
              consumer: Consumer<CloseableReference<CloseableImage>>,
              context: ProducerContext,
          ) {
            assertThat(context.imageRequest).isSameAs(request)
            assertThatThrownBy { context.imagePipelineConfig }
                .isInstanceOf(UnsupportedOperationException::class.java)
            consumer.onNewResult(null, Consumer.IS_LAST)
          }
        }
    val prefetchProducer =
        object : Producer<Void?> {
          override fun produceResults(consumer: Consumer<Void?>, context: ProducerContext) {
            assertThat(context.isPrefetch).isTrue()
            consumer.onNewResult(null, Consumer.IS_LAST)
          }
        }
    whenever(replacement.getDecodedImageProducerSequence(request)).thenReturn(decodedProducer)
    whenever(replacement.getDecodedImagePrefetchProducerSequence(request))
        .thenReturn(prefetchProducer)
    whenever(replacement.getEncodedImagePrefetchProducerSequence(request))
        .thenReturn(prefetchProducer)
    val pipeline = directPipeline(replacement)
    for (dataSource in
        listOf(
            pipeline.fetchDecodedImage(request, null),
            pipeline.prefetchToBitmapCache(request, null),
            pipeline.prefetchToDiskCache(request, null),
        )) {
      try {
        assertThat(dataSource.hasFailed()).isFalse()
        assertThat(dataSource.isFinished).isTrue()
      } finally {
        dataSource.close()
      }
    }
    assertThatThrownBy { pipeline.config }.isInstanceOf(UnsupportedOperationException::class.java)

    try {
      ImagePipelineFactory.initialize(pipeline)
      assertThat(ImagePipelineFactory.getInstanceOrNull()).isNull()
      assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isSameAs(pipeline)
    } finally {
      ImagePipelineFactory.shutDown()
    }
    assertThat(ImagePipelineFactory.getImagePipelineOrNull()).isNull()
    assertThat(ImagePipelineFactory.hasImagePipeline()).isFalse()
  }

  @Test
  fun directPipelineHasNoDiskCacheAndSupportsPauseResume() {
    val cacheKeyFactory: CacheKeyFactory = typedMock()
    val pipeline =
        directPipeline(mock(ProducerSequenceFactoryInterface::class.java), cacheKeyFactory)
    val request = requireNotNull(ImageRequest.fromUri("https://example.com/image"))
    pipeline.evictFromDiskCache(request)
    pipeline.clearDiskCaches()
    for (choice in ImageRequest.CacheChoice.values()) {
      pipeline.clearSpecificDiskCache(choice, null)
    }
    assertThat(pipeline.usedDiskCacheSize).isZero()
    assertThat(pipeline.isInDiskCacheSync(request)).isFalse()
    assertThat(pipeline.isInDiskCache(request).result).isFalse()
    assertThat(pipeline.isPaused).isFalse()
    pipeline.pause()
    assertThat(pipeline.isPaused).isTrue()
    pipeline.resume()
    assertThat(pipeline.isPaused).isFalse()
    verifyNoInteractions(cacheKeyFactory)
  }

  private fun directPipeline(
      replacement: ProducerSequenceFactoryInterface,
      cacheKeyFactory: CacheKeyFactory = DefaultCacheKeyFactory.getInstance(),
  ): ImagePipeline = ImagePipeline(
      replacement,
      typedMock(),
      typedMock(),
      cacheKeyFactory,
      ThreadHandoffProducerQueueImpl { it.run() },
  )

  @Test
  fun replacementBypassesDefaultProducerDependencies() {
    val replacement = mock(ProducerSequenceFactoryInterface::class.java)
    val baseConfig =
        ImagePipelineConfig.newBuilder(RuntimeEnvironment.getApplication())
            .setProducerSequenceFactory(replacement)
            .build()
    val config =
        object : ImagePipelineConfigInterface by baseConfig {
          override val imageDecoder: ImageDecoder?
            get() = error("Default decoder must not be initialized")

          override val networkFetcher: NetworkFetcher<*>
            get() = error("Default network fetcher must not be used")
        }
    val factory = ImagePipelineFactory(config)
    val pipeline = checkNotNull(
        ReflectionHelpers.callInstanceMethod<ImagePipeline>(factory, "createImagePipeline"),
    )

    assertThat(pipeline.producerSequenceFactory).isSameAs(replacement)
  }

  @Test
  fun nullReplacementKeepsDefaultFactory() {
    val config =
        ImagePipelineConfig.newBuilder(RuntimeEnvironment.getApplication())
            .setProducerSequenceFactory(mock(ProducerSequenceFactoryInterface::class.java))
            .setProducerSequenceFactory(null)
            .setMemoryChunkType(MemoryChunkType.BUFFER_MEMORY)
            .apply { experiment().setNativeCodeDisabled(true) }
            .build()
    val factory = ImagePipelineFactory(config)
    val pipeline = checkNotNull(
        ReflectionHelpers.callInstanceMethod<ImagePipeline>(factory, "createImagePipeline"),
    )

    assertThat(pipeline.producerSequenceFactory).isInstanceOf(ProducerSequenceFactory::class.java)
  }
}
