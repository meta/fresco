/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.facebook.common.internal.Supplier
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.AbstractDataSource
import com.facebook.fresco.ui.common.BaseControllerListener2
import com.facebook.fresco.ui.common.ControllerListener2.Extras
import com.facebook.fresco.ui.common.ImageLoadStatus
import com.facebook.fresco.ui.common.ImagePerfData
import com.facebook.fresco.ui.common.ImagePerfDataListener
import com.facebook.fresco.ui.common.ImagePerfNotifier
import com.facebook.fresco.ui.common.ImagePerfNotifierHolder
import com.facebook.fresco.ui.common.ImagePerfState
import com.facebook.fresco.ui.common.ImageRenderingInfra
import com.facebook.fresco.ui.common.VisibilityState
import com.facebook.fresco.vito.core.BaseVitoImageRequestListener
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.ImagePerfLoggingListener
import com.facebook.fresco.vito.core.VitoImagePerfListener
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.core.VitoImageRequestListener
import com.facebook.fresco.vito.listener.BaseImageListener
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ImageSourceProvider
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.ImageInfo
import java.util.concurrent.Executor
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * A bound image is unbound (delayed release) and fetched again before its request set an image (it
 * still loads, or it failed without an error drawable): with a different request, as when a Litho
 * rebind gets another URL for the same image, or with the same request and
 * [FrescoDrawableInterface.forceReloadIfImageAlreadySet].
 */
@RunWith(RobolectricTestRunner::class)
class FrescoController2ImplReplacedRequestTest {

  private val events = mutableListOf<String>()
  private val tagsById = mutableMapOf<Long, String>()
  private val dataSources = mutableListOf<TestImageDataSource>()
  private val images = mutableListOf<CloseableReference<CloseableImage>>()
  private lateinit var mainLooper: ShadowLooper
  private lateinit var drawable: FrescoDrawable2Impl
  private lateinit var requestA: VitoImageRequest
  private lateinit var requestB: VitoImageRequest

  @Before
  fun setup() {
    mainLooper = shadowOf(Looper.getMainLooper())
    mainLooper.pause()
    requestA = request(URI_A)
    requestB = request(URI_B)
  }

  @After
  fun tearDown() {
    if (::drawable.isInitialized) {
      drawable.close()
    }
    dataSources.forEach { it.close() }
    images.forEach { it.close() }
    mainLooper.runToEndOfTasks()
  }

  @Test
  fun testReplacedLoadingRequest_whenFixOff_thenReplacedRequestGetsNoEndEvent() {
    val controller = createController(fixEnabled = false)
    fetch(controller, requestA, "A")
    controller.releaseDelayed(drawable)
    fetch(controller, requestB, "B")
    mainLooper.runToEndOfTasks()

    assertThat(events).containsExactly("A:submit", "perf:A:submit", "B:submit", "perf:B:submit")
    assertThat(dataSources[0].isClosed).isTrue()
  }

  @Test
  fun testReplacedLoadingRequest_whenFixOn_thenReplacedRequestIsReleasedOnce() {
    val controller = createController(fixEnabled = true)
    fetch(controller, requestA, "A")
    controller.releaseDelayed(drawable)
    fetch(controller, requestB, "B")

    assertThat(events)
        .containsExactly(
            "A:submit",
            "perf:A:submit",
            "A:release",
            "perf:A:release",
            "B:submit",
            "perf:B:submit",
        )
    assertThat(dataSources[0].isClosed).isTrue()

    events.clear()
    mainLooper.runToEndOfTasks()
    assertThat(events).isEmpty()

    assertThat(dataSources[1].complete(newImage())).isTrue()
    mainLooper.runToEndOfTasks()
    assertThat(events).containsExactly("B:final", "perf:B:final")

    events.clear()
    controller.releaseDelayed(drawable)
    mainLooper.runToEndOfTasks()
    assertThat(events).containsExactly("B:release", "perf:B:release")
  }

  @Test
  fun testReplacedLoadingRequest_whenFixOnAndReplacementFails_thenEachRequestEndsOnce() {
    val controller = createController(fixEnabled = true)
    fetch(controller, requestA, "A")
    controller.releaseDelayed(drawable)
    fetch(controller, requestB, "B")
    assertThat(dataSources[1].fail()).isTrue()
    mainLooper.runToEndOfTasks()

    assertThat(events.filter { !it.startsWith("perf:") })
        .containsExactly("A:submit", "A:release", "B:submit", "B:failure")
  }

  @Test
  fun testFailedRequestReplacedDuringDelayedRelease_whenFixOff_thenFailedRequestGetsNoRelease() {
    val controller = createController(fixEnabled = false)
    fetchAndFailWithoutErrorDrawable(controller)
    controller.releaseDelayed(drawable)
    fetch(controller, requestB, "B", RecordingRequestListener("localB"))
    mainLooper.runToEndOfTasks()

    assertThat(events)
        .containsExactly(
            "A:submit",
            "perf:A:submit",
            "A:failure",
            "perf:A:failure",
            "B:submit",
            "perf:B:submit",
        )
  }

  @Test
  fun testFailedRequestReplacedDuringDelayedRelease_whenFixOn_thenFailedRequestIsReleasedOnce() {
    val controller = createController(fixEnabled = true)
    val failedImageId = fetchAndFailWithoutErrorDrawable(controller)
    controller.releaseDelayed(drawable)
    fetch(controller, requestB, "B", RecordingRequestListener("localB"))

    assertThat(events)
        .containsExactly(
            "A:submit",
            "perf:A:submit",
            "A:failure",
            "perf:A:failure",
            "localA:A:release",
            "A:release",
            "perf:A:release",
            "B:submit",
            "perf:B:submit",
        )
    assertThat(drawable.imageId).isNotEqualTo(failedImageId)

    events.clear()
    mainLooper.runToEndOfTasks()
    assertThat(events).isEmpty()

    assertThat(dataSources[1].complete(newImage())).isTrue()
    mainLooper.runToEndOfTasks()
    controller.releaseDelayed(drawable)
    mainLooper.runToEndOfTasks()
    assertThat(events)
        .containsExactly(
            "B:final",
            "perf:B:final",
            "localB:B:release",
            "B:release",
            "perf:B:release",
        )
  }

  @Test
  fun testFailedRequestWithErrorDrawableReplaced_inBothArms_thenNoRelease() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled, errorDrawable = ColorDrawable(Color.RED))
      fetch(controller, requestA, "A")
      assertThat(dataSources.last().fail()).isTrue()
      mainLooper.runToEndOfTasks()
      assertThat(drawable.hasImage()).describedAs("fixEnabled=$fixEnabled").isTrue()
      controller.releaseDelayed(drawable)
      fetch(controller, requestB, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A:submit", "A:failure", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testReplacedCompletedRequest_inBothArms_thenNoRelease() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled)
      fetch(controller, requestA, "A")
      assertThat(dataSources.last().complete(newImage())).isTrue()
      mainLooper.runToEndOfTasks()
      controller.releaseDelayed(drawable)
      fetch(controller, requestB, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A:submit", "A:final", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testReplacedLoadingRequestWithoutPendingRelease_inBothArms_thenNoRelease() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled)
      fetch(controller, requestA, "A")
      fetch(controller, requestB, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A:submit", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testLoadingRequestReleasedAtOnceThenReplaced_inBothArms_thenReleasedOnce() {
    for (fixEnabled in listOf(false, true)) {
      for (releaseNextFrame in listOf(false, true)) {
        resetEvents()
        val controller = createController(fixEnabled)
        fetch(controller, requestA, "A")
        if (releaseNextFrame) {
          controller.releaseNextFrame(drawable)
        } else {
          controller.releaseImmediately(drawable)
        }
        fetch(controller, requestB, "B")
        mainLooper.runToEndOfTasks()

        assertThat(events.filter { !it.startsWith("perf:") })
            .describedAs("fixEnabled=$fixEnabled, releaseNextFrame=$releaseNextFrame")
            .containsExactly("A:submit", "A:release", "B:submit")
        drawable.close()
      }
    }
  }

  @Test
  fun testReplacedRequestWhoseReleaseWasReported_inBothArms_thenNoSecondRelease() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled)
      fetch(controller, requestA, "A")
      controller.releaseDelayed(drawable)
      // The delay elapses: the release is reported and the close waits for the next frame.
      mainLooper.runOneTask()
      fetch(controller, requestB, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A:submit", "A:release", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testSameRequestRebound_inBothArms_thenRequestKeepsLoadingWithoutRelease() {
    for (fixEnabled in listOf(false, true)) {
      for (useSmartPropertyDiffing in listOf(false, true)) {
        // The same instance, and an equal request as a new render builds it.
        for (sameRequest in listOf(requestA, request(URI_A))) {
          val arm =
              "fixEnabled=$fixEnabled, useSmartPropertyDiffing=$useSmartPropertyDiffing, " +
                  "sameInstance=${sameRequest === requestA}"
          resetEvents()
          val controller =
              createController(fixEnabled, useSmartPropertyDiffing = useSmartPropertyDiffing)
          fetch(controller, requestA, "A")
          controller.releaseDelayed(drawable)
          fetch(controller, sameRequest, "A")
          mainLooper.runToEndOfTasks()

          assertThat(events.filter { !it.startsWith("perf:") })
              .describedAs(arm)
              .containsExactly("A:submit")
          assertThat(dataSources.last().isClosed).describedAs(arm).isFalse()
          assertThat(drawable.imageRequest).describedAs(arm).isSameAs(requestA)
          drawable.close()
        }
      }
    }
  }

  @Test
  fun testReplacedLoadingRequest_whenNewReleaseCallbacks_thenReleasedOnceInBothArms() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled, useNewReleaseCallback = true)
      fetch(controller, requestA, "A")
      controller.releaseDelayed(drawable)
      fetch(controller, requestB, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A:submit", "A:release", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testSameRequestReboundWithForceReload_whenFixOff_thenFirstRequestGetsNoEndEvent() {
    val controller = createController(fixEnabled = false)
    fetch(controller, requestA, "A1")
    val firstImageId = drawable.imageId
    controller.releaseDelayed(drawable)
    // As FrescoVitoImage2Spec.onBind sets it before its fetch.
    drawable.forceReloadIfImageAlreadySet = true
    fetch(controller, requestA, "A2")
    mainLooper.runToEndOfTasks()

    assertThat(events).containsExactly("A1:submit", "perf:A1:submit", "A2:submit", "perf:A2:submit")
    assertThat(drawable.imageId).isNotEqualTo(firstImageId)
    assertThat(dataSources[0].isClosed).isTrue()
  }

  @Test
  fun testSameRequestReboundWithForceReload_whenFixOn_thenFirstRequestIsReleasedOnceBeforeRefetch() {
    val controller = createController(fixEnabled = true)
    fetch(controller, requestA, "A1")
    val firstImageId = drawable.imageId
    controller.releaseDelayed(drawable)
    // As FrescoVitoImage2Spec.onBind sets it before its fetch.
    drawable.forceReloadIfImageAlreadySet = true
    fetch(controller, requestA, "A2")

    assertThat(events)
        .containsExactly(
            "A1:submit",
            "perf:A1:submit",
            "A1:release",
            "perf:A1:release",
            "A2:submit",
            "perf:A2:submit",
        )
    assertThat(drawable.imageId).isNotEqualTo(firstImageId)
    assertThat(drawable.forceReloadIfImageAlreadySet).isTrue()
    assertThat(dataSources[0].isClosed).isTrue()

    events.clear()
    mainLooper.runToEndOfTasks()
    assertThat(events).isEmpty()
  }

  @Test
  fun testSameRequestRemountedWithForceReloadAfterNextFrameRelease_inBothArms_thenReleasedOnce() {
    for (fixEnabled in listOf(false, true)) {
      resetEvents()
      val controller = createController(fixEnabled)
      fetch(controller, requestA, "A1")
      // Litho unbind (DELAYED), then unmount (NEXT_FRAME, which reports the release at once).
      controller.releaseDelayed(drawable)
      controller.releaseNextFrame(drawable)
      drawable.forceReloadIfImageAlreadySet = true
      fetch(controller, requestA, "A2")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("fixEnabled=$fixEnabled")
          .containsExactly("A1:submit", "A1:release", "A2:submit")
      drawable.close()
    }
  }

  @Test
  fun testLoadingRequestRebound_whenFixOff_thenOptionsOnlyChangeGetsNoEndEvent() {
    val requestWithOtherPlaceholder =
        request(URI_A, ImageOptions.create().placeholderColor(Color.RED).build())
    for (useSmartPropertyDiffing in listOf(false, true)) {
      resetEvents()
      val controller =
          createController(fixEnabled = false, useSmartPropertyDiffing = useSmartPropertyDiffing)
      fetch(controller, requestA, "A")
      controller.releaseDelayed(drawable)
      fetch(controller, requestWithOtherPlaceholder, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("useSmartPropertyDiffing=$useSmartPropertyDiffing")
          .containsExactly("A:submit", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testLoadingRequestRebound_whenFixOn_thenSmartPropertyDiffingKeepsTheSameRelease() {
    val requestWithOtherPlaceholder =
        request(URI_A, ImageOptions.create().placeholderColor(Color.RED).build())
    for (useSmartPropertyDiffing in listOf(false, true)) {
      resetEvents()
      val controller =
          createController(fixEnabled = true, useSmartPropertyDiffing = useSmartPropertyDiffing)
      fetch(controller, requestA, "A")
      controller.releaseDelayed(drawable)
      fetch(controller, request(URI_A), "A")

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("useSmartPropertyDiffing=$useSmartPropertyDiffing, equal request")
          .containsExactly("A:submit")

      controller.releaseDelayed(drawable)
      fetch(controller, requestWithOtherPlaceholder, "B")
      mainLooper.runToEndOfTasks()

      assertThat(events.filter { !it.startsWith("perf:") })
          .describedAs("useSmartPropertyDiffing=$useSmartPropertyDiffing, other placeholder")
          .containsExactly("A:submit", "A:release", "B:submit")
      drawable.close()
    }
  }

  @Test
  fun testReplacedLoadingRequest_whenFixOn_thenEachListenerGetsTheReleaseOfItsOwnRequest() {
    val controller = createController(
        fixEnabled = true,
        globalImageListener = RecordingRequestListener("global"),
        vitoImagePerfListener = RecordingVitoImagePerfListener(),
    )
    fetch(
        controller,
        requestA,
        "A",
        RecordingRequestListener("localA"),
        RecordingPerfDataListener("perfDataA"),
    )
    controller.releaseDelayed(drawable)
    fetch(
        controller,
        requestB,
        "B",
        RecordingRequestListener("localB"),
        RecordingPerfDataListener("perfDataB"),
    )

    assertThat(events)
        .containsExactly(
            "A:submit",
            "perf:A:submit",
            "global:A:release",
            "localA:A:release",
            "A:release",
            "perf:A:release",
            "perfDataA:RELEASED",
            "lifecycle:A:release",
            "B:submit",
            "perf:B:submit",
        )

    assertThat(dataSources[1].complete(newImage())).isTrue()
    mainLooper.runToEndOfTasks()
    events.clear()
    controller.releaseDelayed(drawable)
    mainLooper.runToEndOfTasks()

    assertThat(events)
        .containsExactly(
            "global:B:release",
            "localB:B:release",
            "B:release",
            "perf:B:release",
            "perfDataB:RELEASED",
            "lifecycle:B:release",
        )
  }

  private fun resetEvents() {
    mainLooper.runToEndOfTasks()
    events.clear()
    tagsById.clear()
  }

  private fun createController(
      fixEnabled: Boolean,
      useNewReleaseCallback: Boolean = false,
      useSmartPropertyDiffing: Boolean = false,
      errorDrawable: Drawable? = null,
      globalImageListener: VitoImageRequestListener? = null,
      vitoImagePerfListener: VitoImagePerfListener = BaseVitoImagePerfListener(),
  ): FrescoController2Impl {
    val pipeline = mock<VitoImagePipeline>()
    whenever(pipeline.getCachedImage(any())).thenReturn(null)
    whenever(pipeline.fetchDecodedImage(any(), anyOrNull(), anyOrNull(), any(), anyOrNull()))
        .thenAnswer { TestImageDataSource().also { dataSources.add(it) } }
    val hierarcher = mock<Hierarcher>()
    whenever(hierarcher.buildErrorDrawable(any(), any())).thenReturn(errorDrawable)
    val controller = FrescoController2Impl(
        config =
            object : DefaultFrescoVitoConfig() {
              override fun fixReleaseOfReplacedLoadingRequest(): Boolean = fixEnabled

              override fun useNewReleaseCallback(): Boolean = useNewReleaseCallback

              override fun useSmartPropertyDiffing(): Boolean = useSmartPropertyDiffing
            },
        hierarcher = hierarcher,
        lightweightBackgroundThreadExecutor = Executor { it.run() },
        uiThreadExecutor = Executor { Handler(Looper.getMainLooper()).post(it) },
        imagePipeline = pipeline,
        globalImageListener = globalImageListener,
        debugOverlayFactory = mock(),
        imagePerfListenerSupplier = Supplier { RecordingPerfLoggingListener() },
        vitoImagePerfListener = vitoImagePerfListener,
    )
    drawable = controller.createDrawable("unit-test")
    return controller
  }

  private fun fetch(
      controller: FrescoController2Impl,
      request: VitoImageRequest,
      tag: String,
      vitoImageRequestListener: VitoImageRequestListener? = null,
      perfDataListener: ImagePerfDataListener? = null,
  ) {
    controller.fetch(
        drawable = drawable,
        imageRequest = request,
        callerContext = "FrescoController2ImplReplacedRequestTest",
        contextChain = null,
        listener = RecordingImageListener(tag),
        perfDataListener = perfDataListener,
        onFadeListener = null,
        viewportDimensions = null,
        vitoImageRequestListener = vitoImageRequestListener,
    )
  }

  /** Fetches A and fails it with no error drawable, so A still has no image. */
  private fun fetchAndFailWithoutErrorDrawable(controller: FrescoController2Impl): Long {
    fetch(controller, requestA, "A", RecordingRequestListener("localA"))
    val imageId = drawable.imageId
    assertThat(dataSources.last().fail()).isTrue()
    mainLooper.runToEndOfTasks()
    assertThat(drawable.hasImage()).isFalse()
    assertThat(drawable.isFetchSubmitted).isTrue()
    return imageId
  }

  private fun newImage(): CloseableReference<CloseableImage> =
      CloseableReference.of<CloseableImage>(FrescoDrawable2ImplTest.DummyCloseableImage()).also {
        images.add(it)
      }

  private fun tagOf(id: Long): String = tagsById[id] ?: "unknown($id)"

  private fun tagOf(stringId: String): String =
      stringId.removePrefix("v").toLongOrNull()?.let { tagOf(it) } ?: "unknown($stringId)"

  private inner class RecordingImageListener(private val tag: String) : BaseImageListener() {
    override fun onSubmit(id: Long, callerContext: Any?) {
      tagsById[id] = tag
      events.add("$tag:submit")
    }

    override fun onFinalImageSet(
        id: Long,
        imageOrigin: Int,
        imageInfo: ImageInfo?,
        drawable: Drawable?,
    ) {
      events.add("${tagOf(id)}:final")
    }

    override fun onFailure(id: Long, error: Drawable?, throwable: Throwable?) {
      events.add("${tagOf(id)}:failure")
    }

    override fun onRelease(id: Long) {
      events.add("${tagOf(id)}:release")
    }
  }

  /** Holds the request's [ImagePerfNotifier] and reports the release to it. */
  private inner class RecordingPerfLoggingListener :
      BaseControllerListener2<ImageInfo>(), ImagePerfLoggingListener, ImagePerfNotifierHolder {
    private val state = ImagePerfState(ImageRenderingInfra.VITO_V2)
    private var imagePerfNotifier: ImagePerfNotifier? = null

    override fun setImagePerfNotifier(imagePerfNotifier: ImagePerfNotifier?) {
      this.imagePerfNotifier = imagePerfNotifier
    }

    override fun onSubmit(id: String, callerContext: Any?, extraData: Extras?) {
      events.add("perf:${tagOf(id)}:submit")
    }

    override fun onFinalImageSet(id: String, imageInfo: ImageInfo?, extraData: Extras?) {
      events.add("perf:${tagOf(id)}:final")
    }

    override fun onFailure(id: String, throwable: Throwable?, extraData: Extras?) {
      events.add("perf:${tagOf(id)}:failure")
    }

    override fun onRelease(id: String, extraData: Extras?) {
      events.add("perf:${tagOf(id)}:release")
      imagePerfNotifier?.notifyStatusUpdated(state, ImageLoadStatus.RELEASED)
    }

    override fun reportVisible(visible: Boolean) = Unit
  }

  private inner class RecordingRequestListener(private val owner: String) :
      BaseVitoImageRequestListener() {
    override fun onRelease(id: Long, imageRequest: VitoImageRequest, extras: Extras?) {
      events.add("$owner:${tagOf(id)}:release")
    }
  }

  private inner class RecordingPerfDataListener(private val owner: String) : ImagePerfDataListener {
    override fun onImageLoadStatusUpdated(
        imagePerfData: ImagePerfData,
        imageLoadStatus: ImageLoadStatus,
    ) {
      events.add("$owner:${imageLoadStatus.name}")
    }

    override fun onImageVisibilityUpdated(
        imagePerfData: ImagePerfData,
        visibilityState: VisibilityState,
    ) = Unit
  }

  private inner class RecordingVitoImagePerfListener : BaseVitoImagePerfListener() {
    override fun onImageRelease(drawable: FrescoDrawableInterface) {
      events.add("lifecycle:${tagOf(drawable.imageId)}:release")
    }
  }

  private class TestImageDataSource : AbstractDataSource<CloseableReference<CloseableImage>>() {
    fun complete(ref: CloseableReference<CloseableImage>): Boolean =
        setResult(ref.clone(), true, null)

    fun fail(): Boolean = setFailure(RuntimeException("test failure"))

    override fun getResult(): CloseableReference<CloseableImage>? =
        CloseableReference.cloneOrNull(super.getResult())

    override fun closeResult(result: CloseableReference<CloseableImage>?) {
      CloseableReference.closeSafely(result)
    }
  }

  private fun request(
      uri: String,
      imageOptions: ImageOptions = ImageOptions.defaults(),
  ): VitoImageRequest = VitoImageRequest(
      resources = Resources.getSystem(),
      imageSource = ImageSourceProvider.forUri(Uri.parse(uri)),
      imageOptions = imageOptions,
      finalImageRequest = null,
      finalImageCacheKey = null,
  )

  private companion object {
    const val URI_A = "https://example.com/a.jpg"
    const val URI_B = "https://example.com/b.jpg"
  }
}
