/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.AbstractDataSource
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.NopDrawable
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.imagepipeline.image.BaseCloseableImage
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.image.ImmutableQualityInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FrescoDrawable2ImplTest {

  private lateinit var frescoDrawable: FrescoDrawable2Impl
  private lateinit var latch: CountDownLatch
  private lateinit var closeableImage: CloseableImage
  private lateinit var closeableReference: CloseableReference<CloseableImage>

  @Before
  fun setup() {
    frescoDrawable = FrescoDrawable2Impl(false, null, BaseVitoImagePerfListener())
    latch = CountDownLatch(1)
    closeableImage = DummyCloseableImage()
    closeableReference =
        CloseableReference.of(closeableImage) { value ->
          value.close()
          latch.countDown()
        }
  }

  @Test
  fun testFrescoDrawable_whenDrawableClosed_thenReleaseActualImageReference() {
    frescoDrawable.setImage(NopDrawable, closeableReference)
    closeableReference.close()
    assertThat(closeableImage.isClosed).isFalse()
    frescoDrawable.close()
    assertThat(frescoDrawable.imageReference).isNull()
    assertThat(closeableImage.isClosed).isTrue()
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun testFrescoDrawable_whenNewImageDrawableSet_thenReleaseOldImageReference() {
    frescoDrawable.setImage(NopDrawable, closeableReference)
    closeableReference.close()
    val dummyCloseableImage = DummyCloseableImage()
    frescoDrawable.setImage(NopDrawable, CloseableReference.of(dummyCloseableImage))
    assertThat(closeableImage.isClosed).isTrue()
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun testFrescoDrawable_whenNewNullImageDrawableSet_thenReleaseOldImageReference() {
    frescoDrawable.setImage(NopDrawable, closeableReference)
    closeableReference.close()
    frescoDrawable.setImage(null, null)
    assertThat(closeableImage.isClosed).isTrue()
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun testFrescoDrawable_whenImageDrawableSet_thenReleaseImageReference() {
    frescoDrawable.setImage(NopDrawable, closeableReference)
    closeableReference.close()
    val drawable = mock<Drawable>()
    frescoDrawable.setImageDrawable(drawable)
    assertThat(frescoDrawable.imageReference).isNull()
    assertThat(closeableImage.isClosed).isTrue()
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun testFrescoDrawable_whenImageDrawableReset_thenReleaseImageReference() {
    frescoDrawable.setImage(NopDrawable, closeableReference)
    closeableReference.close()
    frescoDrawable.setImageDrawable(null)
    assertThat(frescoDrawable.imageReference).isNull()
    assertThat(closeableImage.isClosed).isTrue()
    assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
  }

  @Test
  fun testRetriggerListenersField_doesNotClobberForceReload() {
    frescoDrawable.forceReloadIfImageAlreadySet = true
    frescoDrawable.retriggerListenersIfImageAlreadySet = true

    assertThat(frescoDrawable.forceReloadIfImageAlreadySet).isTrue()
    assertThat(frescoDrawable.retriggerListenersIfImageAlreadySet).isTrue()
  }

  @Test
  fun testRetriggerListenersField_independentFromForceReload() {
    frescoDrawable.forceReloadIfImageAlreadySet = true
    frescoDrawable.retriggerListenersIfImageAlreadySet = false

    assertThat(frescoDrawable.forceReloadIfImageAlreadySet).isTrue()
    assertThat(frescoDrawable.retriggerListenersIfImageAlreadySet).isFalse()

    frescoDrawable.retriggerListenersIfImageAlreadySet = true

    assertThat(frescoDrawable.forceReloadIfImageAlreadySet).isTrue()
    assertThat(frescoDrawable.retriggerListenersIfImageAlreadySet).isTrue()
  }

  @Test
  fun testReset_clearsRetriggerListenersAndForceReload() {
    frescoDrawable.forceReloadIfImageAlreadySet = true
    frescoDrawable.retriggerListenersIfImageAlreadySet = true
    frescoDrawable.reset()

    assertThat(frescoDrawable.forceReloadIfImageAlreadySet).isFalse()
    assertThat(frescoDrawable.retriggerListenersIfImageAlreadySet).isFalse()
  }

  @Test
  fun testDeferredRelease_queuedResultDoesNotKeepUnmountedImageAlive() =
      withImageRequest { controller, request, dataSource ->
        assertThat(dataSource.complete(closeableReference)).isTrue()
        closeableReference.close()
        controller.releaseDelayed(frescoDrawable)
        controller.releaseNextFrame(frescoDrawable)

        shadowOf(Looper.getMainLooper()).runOneTask()
        assertThat(frescoDrawable.imageReference?.get()).isSameAs(closeableImage)
        assertThat(frescoDrawable.imageRequest).isSameAs(request)
        assertThat(dataSource.isClosed).isFalse()

        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(frescoDrawable.imageReference).isNull()
        assertThat(dataSource.isClosed).isTrue()
        assertThat(closeableImage.isClosed).isTrue()
      }

  @Test
  fun testDeferredRelease_genuineRebindKeepsImageAlive() =
      withImageRequest { controller, request, dataSource ->
        assertThat(dataSource.complete(closeableReference)).isTrue()
        closeableReference.close()
        controller.releaseDelayed(frescoDrawable)
        controller.releaseNextFrame(frescoDrawable)

        assertThat(fetch(controller, request)).isTrue()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(frescoDrawable.imageReference?.get()).isSameAs(closeableImage)
        assertThat(dataSource.isClosed).isFalse()
        assertThat(closeableImage.isClosed).isFalse()
      }

  @Test
  fun testDeferredRelease_releaseBeforeQueuedResultClosesImage() =
      withImageRequest { controller, _, dataSource ->
        controller.releaseDelayed(frescoDrawable)
        controller.releaseNextFrame(frescoDrawable)
        assertThat(dataSource.complete(closeableReference)).isTrue()
        closeableReference.close()

        shadowOf(Looper.getMainLooper()).runOneTask()
        assertThat(dataSource.isClosed).isTrue()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(frescoDrawable.imageReference).isNull()
        assertThat(frescoDrawable.imageRequest).isNull()
        assertThat(closeableImage.isClosed).isTrue()
      }

  @Test
  fun testDeferredRelease_fixDisabledPreservesLegacyCancellation() =
      withImageRequest(preservePendingRelease = false) { controller, _, dataSource ->
        assertThat(dataSource.complete(closeableReference)).isTrue()
        closeableReference.close()
        controller.releaseDelayed(frescoDrawable)
        controller.releaseNextFrame(frescoDrawable)

        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(frescoDrawable.imageReference?.get()).isSameAs(closeableImage)
        assertThat(dataSource.isClosed).isFalse()
        assertThat(closeableImage.isClosed).isFalse()
      }

  @Test
  fun testDeferredRelease_queuedResultPreservesDelayedOnlyRelease() =
      withImageRequest { controller, _, dataSource ->
        assertThat(dataSource.complete(closeableReference)).isTrue()
        closeableReference.close()
        controller.releaseDelayed(frescoDrawable)

        shadowOf(Looper.getMainLooper()).runOneTask()
        assertThat(frescoDrawable.imageReference?.get()).isSameAs(closeableImage)
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        assertThat(frescoDrawable.imageReference).isNull()
        assertThat(dataSource.isClosed).isTrue()
        assertThat(closeableImage.isClosed).isTrue()
      }

  @Test
  fun testPrepareToDrawOnFetch_asynchronousBitmapFollowsConfig() {
    for (isEnabled in listOf(false, true)) {
      val bitmap = spy(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
      CloseableReference.of<CloseableImage>(
              CloseableStaticBitmap.of(
                  bitmap,
                  { it.recycle() },
                  ImmutableQualityInfo.FULL_QUALITY,
                  0,
              ),
          )
          .use { imageReference ->
            withImageRequest(enablePrepareToDrawOnFetch = isEnabled) { _, _, dataSource ->
              verify(bitmap, never()).prepareToDraw()
              assertThat(dataSource.complete(imageReference)).isTrue()
              shadowOf(Looper.getMainLooper()).runToEndOfTasks()
              assertThat(frescoDrawable.imageReference?.get()).isSameAs(imageReference.get())
              verify(bitmap, times(if (isEnabled) 1 else 0)).prepareToDraw()
            }
          }
    }
  }

  @Test
  fun testPrepareToDrawOnFetch_cachedBitmapFollowsConfig() {
    for (isEnabled in listOf(false, true)) {
      val bitmap = spy(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
      CloseableReference.of<CloseableImage>(
              CloseableStaticBitmap.of(
                  bitmap,
                  { it.recycle() },
                  ImmutableQualityInfo.FULL_QUALITY,
                  0,
              ),
          )
          .use { imageReference ->
            withImageRequest(
                enablePrepareToDrawOnFetch = isEnabled,
                cachedImage = imageReference,
            ) { _, _, _ ->
              assertThat(frescoDrawable.imageReference?.get()).isSameAs(imageReference.get())
              verify(bitmap, times(if (isEnabled) 1 else 0)).prepareToDraw()
            }
          }
    }
  }

  private fun withImageRequest(
      preservePendingRelease: Boolean = true,
      enablePrepareToDrawOnFetch: Boolean = false,
      cachedImage: CloseableReference<CloseableImage>? = null,
      test: (FrescoController2Impl, VitoImageRequest, TestImageDataSource) -> Unit,
  ) {
    val mainLooper = shadowOf(Looper.getMainLooper())
    mainLooper.pause()
    val dataSource = TestImageDataSource()
    val pipeline = mock<VitoImagePipeline>()
    whenever(pipeline.getCachedImage(any())).thenAnswer {
      CloseableReference.cloneOrNull(cachedImage)
    }
    whenever(pipeline.fetchDecodedImage(any(), anyOrNull(), anyOrNull(), any(), anyOrNull()))
        .thenReturn(dataSource)
    val controller = FrescoController2Impl(
        config =
            object : DefaultFrescoVitoConfig() {
              override fun preservePendingReleaseOnImageUpdate(): Boolean = preservePendingRelease

              override fun enablePrepareToDrawOnFetch(): Boolean = enablePrepareToDrawOnFetch
            },
        hierarcher = mock(),
        lightweightBackgroundThreadExecutor = Executor { it.run() },
        uiThreadExecutor = Executor { Handler(Looper.getMainLooper()).post(it) },
        imagePipeline = pipeline,
        globalImageListener = null,
        debugOverlayFactory = mock(),
        imagePerfListenerSupplier = null,
        vitoImagePerfListener = BaseVitoImagePerfListener(),
    )
    frescoDrawable = controller.createDrawable("unit-test")
    val request = VitoImageRequest(
        resources = Resources.getSystem(),
        imageSource = ImageSourceProvider.forUri(Uri.parse("https://example.com/image.jpg")),
        imageOptions = ImageOptions.defaults(),
        finalImageRequest = null,
        finalImageCacheKey = null,
    )
    try {
      assertThat(fetch(controller, request)).isEqualTo(cachedImage != null)
      if (cachedImage == null) {
        assertThat(frescoDrawable.imageReference).isNull()
      }
      assertThat(frescoDrawable.isFetchSubmitted).isTrue()
      test(controller, request, dataSource)
      verify(pipeline, times(if (cachedImage == null) 1 else 0))
          .fetchDecodedImage(any(), anyOrNull(), anyOrNull(), any(), anyOrNull())
    } finally {
      frescoDrawable.close()
      dataSource.close()
      closeableReference.close()
      mainLooper.runToEndOfTasks()
    }
  }

  private fun fetch(controller: FrescoController2Impl, request: VitoImageRequest): Boolean =
      controller.fetch(
          drawable = frescoDrawable,
          imageRequest = request,
          callerContext = "FrescoDrawable2ImplTest",
          contextChain = null,
          listener = null,
          onFadeListener = null,
          viewportDimensions = null,
      )

  private class TestImageDataSource : AbstractDataSource<CloseableReference<CloseableImage>>() {
    fun complete(ref: CloseableReference<CloseableImage>): Boolean =
        setResult(ref.clone(), true, null)

    override fun getResult(): CloseableReference<CloseableImage>? =
        CloseableReference.cloneOrNull(super.getResult())

    override fun closeResult(result: CloseableReference<CloseableImage>?) {
      CloseableReference.closeSafely(result)
    }
  }

  internal class DummyCloseableImage : BaseCloseableImage() {
    private var closed = false

    override fun getSizeInBytes(): Int = 0

    override fun close() {
      closed = true
    }

    override fun isClosed(): Boolean = closed

    override fun getWidth(): Int = 0

    override fun getHeight(): Int = 0
  }
}
