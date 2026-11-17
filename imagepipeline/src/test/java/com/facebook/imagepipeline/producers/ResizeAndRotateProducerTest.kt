/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import com.facebook.common.executors.UiThreadImmediateExecutorService
import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.memory.PooledByteBufferFactory
import com.facebook.common.memory.PooledByteBufferOutputStream
import com.facebook.common.references.CloseableReference
import com.facebook.imageformat.DefaultImageFormats
import com.facebook.imageformat.ImageFormat
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.common.RotationOptions
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.nativecode.NativeJpegTranscoder
import com.facebook.imagepipeline.nativecode.NativeJpegTranscoderFactory
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.imagepipeline.testing.FakeClock
import com.facebook.imagepipeline.testing.TestExecutorService
import com.facebook.imagepipeline.testing.TestNativeLoader
import com.facebook.imagepipeline.testing.TestScheduledExecutorService
import com.facebook.imagepipeline.testing.TrivialPooledByteBuffer
import com.facebook.imagepipeline.transcoder.JpegTranscoderUtils
import com.facebook.imagepipeline.transcoder.JpegTranscoderUtils.DEFAULT_JPEG_QUALITY
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Helper function for Mockito any() with generic types in Kotlin
@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
private inline fun <T> anyObject(): T = any<T>() as T

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ResizeAndRotateProducerTest {

  @Mock lateinit var mInputProducer: Producer<EncodedImage>

  @Mock lateinit var mImageRequest: ImageRequest

  @Mock lateinit var mProducerListener: ProducerListener2

  @Mock lateinit var mConsumer: Consumer<EncodedImage>

  @Mock lateinit var mProducerContext: ProducerContext

  @Mock lateinit var mPooledByteBufferFactory: PooledByteBufferFactory

  @Mock lateinit var mPooledByteBufferOutputStream: PooledByteBufferOutputStream

  private lateinit var testExecutorService: TestExecutorService
  private lateinit var resizeAndRotateProducer: ResizeAndRotateProducer
  private var resizeAndRotateProducerConsumer: Consumer<EncodedImage>? = null
  private lateinit var intermediateResult: CloseableReference<PooledByteBuffer>
  private lateinit var finalResult: CloseableReference<PooledByteBuffer>
  private lateinit var pooledByteBuffer: PooledByteBuffer

  private lateinit var fakeClockForWorker: FakeClock
  private lateinit var fakeClockForScheduled: FakeClock
  private lateinit var testScheduledExecutorService: TestScheduledExecutorService
  private lateinit var uiThreadImmediateExecutorService: UiThreadImmediateExecutorService
  private lateinit var intermediateEncodedImage: EncodedImage
  private lateinit var finalEncodedImage: EncodedImage
  private lateinit var mockedSystemClock: MockedStatic<SystemClock>
  private lateinit var mockedNativeJpegTranscoder: MockedStatic<NativeJpegTranscoder>
  private lateinit var mockedUiThreadImmediateExecutorService:
      MockedStatic<UiThreadImmediateExecutorService>

  @Before
  fun setUp() {
    mockedUiThreadImmediateExecutorService =
        mockStatic(UiThreadImmediateExecutorService::class.java)
    mockedNativeJpegTranscoder = mockStatic(NativeJpegTranscoder::class.java)
    mockedSystemClock = mockStatic(SystemClock::class.java)
    MockitoAnnotations.openMocks(this)
    fakeClockForWorker = FakeClock()
    fakeClockForScheduled = FakeClock()
    fakeClockForWorker.incrementBy(1000)
    fakeClockForScheduled.incrementBy(1000)
    mockedSystemClock
        .`when`<Long> { SystemClock.uptimeMillis() }
        .thenAnswer { fakeClockForWorker.now() }

    `when`(mImageRequest.getSourceUri()).thenReturn(Uri.parse("http://testuri"))
    testExecutorService = TestExecutorService(fakeClockForWorker)
    testScheduledExecutorService = TestScheduledExecutorService(fakeClockForScheduled)
    uiThreadImmediateExecutorService = mock(UiThreadImmediateExecutorService::class.java)
    `when`(
        uiThreadImmediateExecutorService.schedule(
            any(Runnable::class.java),
            anyLong(),
            any(TimeUnit::class.java),
        ),
    )
        .thenAnswer { invocation ->
          testScheduledExecutorService.schedule(
              invocation.arguments[0] as Runnable,
              invocation.arguments[1] as Long,
              invocation.arguments[2] as TimeUnit,
          )
        }

    mockedUiThreadImmediateExecutorService
        .`when`<UiThreadImmediateExecutorService> { UiThreadImmediateExecutorService.getInstance() }
        .thenAnswer { uiThreadImmediateExecutorService }

    testExecutorService = TestExecutorService(fakeClockForWorker)

    doReturn(mImageRequest).`when`(mProducerContext).imageRequest
    doReturn(mProducerListener).`when`(mProducerContext).producerListener
    doReturn(true).`when`(mProducerListener).requiresExtraMap(eq(mProducerContext), anyString())
    intermediateResult = CloseableReference.of(mock(PooledByteBuffer::class.java))
    finalResult = CloseableReference.of(mock(PooledByteBuffer::class.java))

    resizeAndRotateProducerConsumer = null
    doAnswer { invocation ->
          resizeAndRotateProducerConsumer = invocation.arguments[0] as Consumer<EncodedImage>
          null
        }
        .`when`(mInputProducer)
        .produceResults(anyObject<Consumer<EncodedImage>>(), anyObject<ProducerContext>())
    doReturn(mPooledByteBufferOutputStream).`when`(mPooledByteBufferFactory).newOutputStream()
    pooledByteBuffer = TrivialPooledByteBuffer(byteArrayOf(1), 0)
    doReturn(pooledByteBuffer).`when`(mPooledByteBufferOutputStream).toByteBuffer()
  }

  @After
  fun tearDownStaticMocks() {
    mockedSystemClock.close()
    mockedNativeJpegTranscoder.close()
    mockedUiThreadImmediateExecutorService.close()
  }

  @Test
  fun testDoesNotTransformIfImageRotationAngleUnkown() {
    whenResizingEnabled()
    whenRequestSpecificRotation(RotationOptions.NO_ROTATION)

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        800,
        800,
        EncodedImage.UNKNOWN_ROTATION_ANGLE,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        800,
        800,
        EncodedImage.UNKNOWN_ROTATION_ANGLE,
        ExifInterface.ORIENTATION_UNDEFINED,
    )
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesNotTransformIfRotationDisabled() {
    whenResizingEnabled()
    whenDisableRotation()

    provideIntermediateResult(DefaultImageFormats.JPEG)
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(DefaultImageFormats.JPEG)
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesNotTransformIfMetadataAngleAndRequestedRotationHaveOppositeValues() {
    whenResizingEnabled()
    whenRequestSpecificRotation(RotationOptions.ROTATE_270)

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 90, ExifInterface.ORIENTATION_ROTATE_90)
    verifyAFinalResultPassedThroughNotResized()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesNotTransformIfNotRequested() {
    whenResizingDisabled()
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideIntermediateResult(DefaultImageFormats.JPEG)
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(DefaultImageFormats.JPEG)
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesNotTransformIfNotJpeg() {
    whenResizingEnabled()
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideIntermediateResult(DefaultImageFormats.WEBP_SIMPLE)
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(DefaultImageFormats.WEBP_SIMPLE)
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesRotateIfJpegAndCannotDeferRotationAndResizingDisabled() {
    whenResizingDisabled()
    testDoesRotateIfJpegAndCannotDeferRotation()
  }

  @Test
  fun testDoesRotateIfJpegAndCannotDeferRotationAndResizingEnabled() {
    whenResizingEnabled()
    testDoesRotateIfJpegAndCannotDeferRotation()
  }

  private fun testDoesRotateIfJpegAndCannotDeferRotation() {
    val rotationAngle = 180
    val exifOrientation = ExifInterface.ORIENTATION_ROTATE_180
    val sourceWidth = 10
    val sourceHeight = 10
    whenRequestWidthAndHeight(sourceWidth, sourceHeight)
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        sourceWidth,
        sourceHeight,
        rotationAngle,
        exifOrientation,
    )
    verifyNoIntermediateResultPassedThrough()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        sourceWidth,
        sourceHeight,
        rotationAngle,
        exifOrientation,
    )
    verifyAFinalResultPassedThroughNotResized()

    assertThat(finalResult.underlyingReferenceTestOnly.refCountTestOnly).isEqualTo(2)
    assertThat(pooledByteBuffer.isClosed).isTrue

    verifyJpegTranscoderInteractions(8, rotationAngle)
  }

  @Test
  fun testRotateUsingRotationAngleOnlyForJPEG() {
    whenResizingEnabled()
    val rotationAngle = 90
    whenRequestSpecificRotation(rotationAngle)
    provideFinalResult(DefaultImageFormats.JPEG, 10, 10, 0, ExifInterface.ORIENTATION_UNDEFINED)

    verifyJpegTranscoderInteractions(8, rotationAngle)
    verifyZeroJpegTranscoderExifOrientationInteractions()
  }

  @Test
  fun testRotationAngleIsSetForPNG() {
    whenResizingEnabled()
    testNewResultContainsRotationAngleForImageFormat(DefaultImageFormats.PNG)
  }

  @Test
  fun testRotationAngleIsSetForWebp() {
    whenResizingEnabled()
    testNewResultContainsRotationAngleForImageFormat(DefaultImageFormats.WEBP_SIMPLE)
  }

  private fun testNewResultContainsRotationAngleForImageFormat(imageFormat: ImageFormat) {
    val rotationAngle = 90
    whenRequestSpecificRotation(rotationAngle)
    provideFinalResult(imageFormat, 10, 10, 0, ExifInterface.ORIENTATION_UNDEFINED)

    assertAngleOnNewResult(rotationAngle)
    verifyZeroJpegTranscoderInteractions()
    verifyZeroJpegTranscoderExifOrientationInteractions()
  }

  private fun assertAngleOnNewResult(expectedRotationAngle: Int) {
    val captor = ArgumentCaptor.forClass(EncodedImage::class.java)
    verify(mConsumer).onNewResult(captor.capture(), eq(Consumer.IS_LAST))
    assertThat(captor.value.rotationAngle).isEqualTo(expectedRotationAngle)
  }

  @Test
  fun testRotateUsingExifOrientationOnly() {
    whenResizingEnabled()
    val exifOrientation = ExifInterface.ORIENTATION_FLIP_HORIZONTAL
    whenRequestsRotationFromMetadataWithoutDeferring()
    provideFinalResult(DefaultImageFormats.JPEG, 10, 10, 0, exifOrientation)

    verifyZeroJpegTranscoderInteractions()
    verifyJpegTranscoderExifOrientationInteractions(8, exifOrientation)
  }

  @Test
  fun testDoesNotRotateIfCanDeferRotationAndResizeNotNeeded() {
    whenResizingEnabled()

    val rotationAngle = 180
    val exifOrientation = ExifInterface.ORIENTATION_ROTATE_180
    val sourceWidth = 10
    val sourceHeight = 10
    whenRequestWidthAndHeight(sourceWidth, sourceHeight)
    whenRequestsRotationFromMetadataWithDeferringAllowed()

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        sourceWidth,
        sourceHeight,
        rotationAngle,
        exifOrientation,
    )
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        sourceWidth,
        sourceHeight,
        rotationAngle,
        exifOrientation,
    )
    verifyFinalResultPassedThroughUnchanged()

    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesResizeAndRotateIfCanDeferRotationButResizeIsNeeded() {
    whenResizingEnabled()

    val rotationAngle = 90
    val exifOrientation = ExifInterface.ORIENTATION_ROTATE_90
    val sourceWidth = 10
    val sourceHeight = 10
    whenRequestWidthAndHeight(sourceWidth, sourceHeight)
    whenRequestsRotationFromMetadataWithDeferringAllowed()

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        sourceWidth * 2,
        sourceHeight * 2,
        rotationAngle,
        exifOrientation,
    )
    verifyNoIntermediateResultPassedThrough()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        sourceWidth * 2,
        sourceHeight * 2,
        rotationAngle,
        exifOrientation,
    )
    verifyAFinalResultPassedThroughResized()

    verifyJpegTranscoderInteractions(4, rotationAngle)
  }

  @Test
  fun testDoesResizeIfJpegAndResizingEnabled() {
    whenResizingEnabled()
    val preferredWidth = 300
    val preferredHeight = 600
    whenRequestWidthAndHeight(preferredWidth, preferredHeight)
    whenRequestSpecificRotation(RotationOptions.NO_ROTATION)

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        preferredWidth * 2,
        preferredHeight * 2,
        0,
        ExifInterface.ORIENTATION_NORMAL,
    )
    verifyNoIntermediateResultPassedThrough()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        preferredWidth * 2,
        preferredHeight * 2,
        0,
        ExifInterface.ORIENTATION_NORMAL,
    )
    verifyAFinalResultPassedThroughResized()

    assertThat(finalResult.underlyingReferenceTestOnly.refCountTestOnly).isEqualTo(2)
    assertThat(pooledByteBuffer.isClosed).isTrue

    verifyJpegTranscoderInteractions(4, 0)
  }

  @Test
  fun testDoesNotResizeIfJpegButResizingDisabled() {
    whenResizingDisabled()
    val preferredWidth = 300
    val preferredHeight = 600
    whenRequestWidthAndHeight(preferredWidth, preferredHeight)
    whenRequestSpecificRotation(RotationOptions.NO_ROTATION)

    provideIntermediateResult(
        DefaultImageFormats.JPEG,
        preferredWidth * 2,
        preferredHeight * 2,
        0,
        ExifInterface.ORIENTATION_NORMAL,
    )
    verifyIntermediateResultPassedThroughUnchanged()

    provideFinalResult(
        DefaultImageFormats.JPEG,
        preferredWidth * 2,
        preferredHeight * 2,
        0,
        ExifInterface.ORIENTATION_NORMAL,
    )
    verifyFinalResultPassedThroughUnchanged()

    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDisablesResizingIfResizeTranscoderIsNone() {
    verifyResizingEnabledForTranscoder(ResizeOptions.Transcoder.NONE, 600, false)
  }

  @Test
  fun testDisablesResizingForDecodeScaleIfResizeTranscoderIsNoneForDecodeScales() {
    // 300 / 600 is a 4/8 transcode, and the decode samples by 2.
    verifyResizingEnabledForTranscoder(ResizeOptions.Transcoder.NONE_FOR_DECODE_SCALES, 600, false)
  }

  @Test
  fun testKeepsResizingForOtherScalesIfResizeTranscoderIsNoneForDecodeScales() {
    // 300 / 800 is a 3/8 transcode, and the decode samples by 2.
    verifyResizingEnabledForTranscoder(ResizeOptions.Transcoder.NONE_FOR_DECODE_SCALES, 800, true)
  }

  private fun verifyResizingEnabledForTranscoder(
      transcoder: ResizeOptions.Transcoder,
      imageWidth: Int,
      expectedResizingEnabled: Boolean,
  ) {
    `when`(mImageRequest.getResizeOptions())
        .thenReturn(ResizeOptions(300, 600, transcoder = transcoder))
    `when`(mImageRequest.getResizingAllowedOverride()).thenReturn(null)
    whenRequestSpecificRotation(RotationOptions.NO_ROTATION)
    val transcoderFactory = mock(NativeJpegTranscoderFactory::class.java)
    `when`(transcoderFactory.createImageTranscoder(anyObject<ImageFormat>(), anyBoolean()))
        .thenReturn(NativeJpegTranscoder(false, MAX_BITMAP_SIZE, false, false))

    ResizeAndRotateProducer(
        testExecutorService,
        mPooledByteBufferFactory,
        mInputProducer,
        true,
        transcoderFactory,
    )
        .produceResults(mConsumer, mProducerContext)
    provideFinalResult(
        DefaultImageFormats.JPEG,
        imageWidth,
        imageWidth * 2,
        0,
        ExifInterface.ORIENTATION_NORMAL,
    )

    verify(transcoderFactory, atLeastOnce())
        .createImageTranscoder(anyObject<ImageFormat>(), eq(expectedResizingEnabled))
    verify(transcoderFactory, never())
        .createImageTranscoder(anyObject<ImageFormat>(), eq(!expectedResizingEnabled))
  }

  @Test
  fun testDoesNotUpscale() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(150, 150)
    whenRequestSpecificRotation(RotationOptions.NO_ROTATION)

    provideFinalResult(DefaultImageFormats.JPEG, 100, 100, 0, ExifInterface.ORIENTATION_NORMAL)
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesNotUpscaleWhenRotating() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(150, 150)
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideFinalResult(DefaultImageFormats.JPEG, 100, 100, 90, ExifInterface.ORIENTATION_ROTATE_90)
    verifyAFinalResultPassedThroughNotResized()
    verifyJpegTranscoderInteractions(8, 90)
  }

  @Test
  fun testDoesComputeRightNumeratorWhenRotating_0() {
    testDoesComputeRightNumerator(0, ExifInterface.ORIENTATION_NORMAL, 4)
  }

  @Test
  fun testDoesComputeRightNumeratorWhenRotating_90() {
    testDoesComputeRightNumerator(90, ExifInterface.ORIENTATION_ROTATE_90, 2)
  }

  @Test
  fun testDoesComputeRightNumeratorWhenRotating_180() {
    testDoesComputeRightNumerator(180, ExifInterface.ORIENTATION_ROTATE_180, 4)
  }

  @Test
  fun testDoesComputeRightNumeratorWhenRotating_270() {
    testDoesComputeRightNumerator(270, ExifInterface.ORIENTATION_ROTATE_270, 2)
  }

  private fun testDoesComputeRightNumerator(
      rotationAngle: Int,
      exifOrientation: Int,
      numerator: Int,
  ) {
    whenResizingEnabled()
    whenRequestWidthAndHeight(50, 100)
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, rotationAngle, exifOrientation)
    verifyAFinalResultPassedThroughResized()
    verifyJpegTranscoderInteractions(numerator, rotationAngle)
  }

  @Test
  fun testDoesComputeRightNumeratorInvertedOrientation_flipHorizontal() {
    testDoesComputeRightNumeratorInvertedOrientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, 4)
  }

  @Test
  fun testDoesComputeRightNumeratorInvertedOrientation_flipVertical() {
    testDoesComputeRightNumeratorInvertedOrientation(ExifInterface.ORIENTATION_FLIP_VERTICAL, 4)
  }

  @Test
  fun testDoesComputeRightNumeratorInvertedOrientation_transpose() {
    testDoesComputeRightNumeratorInvertedOrientation(ExifInterface.ORIENTATION_TRANSPOSE, 2)
  }

  @Test
  fun testDoesComputeRightNumeratorInvertedOrientation_transverse() {
    testDoesComputeRightNumeratorInvertedOrientation(ExifInterface.ORIENTATION_TRANSVERSE, 2)
  }

  private fun testDoesComputeRightNumeratorInvertedOrientation(
      exifOrientation: Int,
      numerator: Int,
  ) {
    whenResizingEnabled()
    whenRequestWidthAndHeight(50, 100)
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 0, exifOrientation)
    verifyAFinalResultPassedThroughResized()
    verifyJpegTranscoderExifOrientationInteractions(numerator, exifOrientation)
  }

  @Test
  fun testDoesRotateWhenNoResizeOptionsIfCannotBeDeferred() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(0, 0)
    whenRequestsRotationFromMetadataWithoutDeferring()

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 90, ExifInterface.ORIENTATION_ROTATE_90)
    verifyAFinalResultPassedThroughNotResized()
    verifyJpegTranscoderInteractions(8, 90)
  }

  @Test
  fun testDoesNotRotateWhenNoResizeOptionsAndCanBeDeferred() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(0, 0)
    whenRequestsRotationFromMetadataWithDeferringAllowed()

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 90, ExifInterface.ORIENTATION_ROTATE_90)
    verifyFinalResultPassedThroughUnchanged()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testDoesRotateWhenSpecificRotationRequested() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(200, 400)
    whenRequestSpecificRotation(RotationOptions.ROTATE_270)

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 0, ExifInterface.ORIENTATION_UNDEFINED)
    verifyAFinalResultPassedThroughNotResized()
    verifyJpegTranscoderInteractions(8, 270)
  }

  @Test
  fun testDoesNothingWhenNotAskedToDoAnything() {
    whenResizingEnabled()
    whenRequestWidthAndHeight(0, 0)
    whenDisableRotation()

    provideFinalResult(DefaultImageFormats.JPEG, 400, 200, 90, ExifInterface.ORIENTATION_ROTATE_90)
    verifyAFinalResultPassedThroughNotResized()
    verifyZeroJpegTranscoderInteractions()
  }

  @Test
  fun testRoundNumerator() {
    assertThat(JpegTranscoderUtils.roundNumerator(1.0f / 8, ResizeOptions.DEFAULT_ROUNDUP_FRACTION))
        .isEqualTo(1)
    assertThat(
        JpegTranscoderUtils.roundNumerator(5.0f / 32, ResizeOptions.DEFAULT_ROUNDUP_FRACTION),
    )
        .isEqualTo(1)
    assertThat(
        JpegTranscoderUtils.roundNumerator(
            1.0f / 6 - 0.01f,
            ResizeOptions.DEFAULT_ROUNDUP_FRACTION,
        ),
    )
        .isEqualTo(1)
    assertThat(JpegTranscoderUtils.roundNumerator(1.0f / 6, ResizeOptions.DEFAULT_ROUNDUP_FRACTION))
        .isEqualTo(2)
    assertThat(
        JpegTranscoderUtils.roundNumerator(3.0f / 16, ResizeOptions.DEFAULT_ROUNDUP_FRACTION),
    )
        .isEqualTo(2)
    assertThat(JpegTranscoderUtils.roundNumerator(2.0f / 8, ResizeOptions.DEFAULT_ROUNDUP_FRACTION))
        .isEqualTo(2)
  }

  @Test
  fun testDownsamplingRatioUsage() {
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(1)).isEqualTo(8)
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(2)).isEqualTo(4)
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(4)).isEqualTo(2)
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(8)).isEqualTo(1)
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(16)).isEqualTo(1)
    assertThat(JpegTranscoderUtils.calculateDownsampleNumerator(32)).isEqualTo(1)
  }

  @Test
  fun testResizeRatio() {
    val resizeOptions = ResizeOptions(512, 512)
    assertThat(JpegTranscoderUtils.determineResizeRatio(resizeOptions, 1024, 1024))
        .isCloseTo(0.5f, offset(0.01f))
    assertThat(JpegTranscoderUtils.determineResizeRatio(resizeOptions, 2048, 4096))
        .isCloseTo(0.25f, offset(0.01f))
    assertThat(JpegTranscoderUtils.determineResizeRatio(resizeOptions, 4096, 512))
        .isCloseTo(0.5f, offset(0.01f))
  }

  private fun verifyIntermediateResultPassedThroughUnchanged() {
    verify(mConsumer).onNewResult(intermediateEncodedImage, Consumer.NO_FLAGS)
  }

  private fun verifyNoIntermediateResultPassedThrough() {
    verify(mConsumer, never()).onNewResult(any(EncodedImage::class.java), eq(Consumer.NO_FLAGS))
  }

  private fun verifyFinalResultPassedThroughUnchanged() {
    verify(mConsumer).onNewResult(finalEncodedImage, Consumer.IS_LAST)
  }

  private fun verifyAFinalResultPassedThroughResized() {
    verify(mConsumer)
        .onNewResult(
            any(EncodedImage::class.java),
            eq(Consumer.IS_LAST or Consumer.IS_RESIZING_DONE),
        )
  }

  private fun verifyAFinalResultPassedThroughNotResized() {
    verify(mConsumer).onNewResult(any(EncodedImage::class.java), eq(Consumer.IS_LAST))
  }

  private fun verifyJpegTranscoderInteractions(numerator: Int, rotationAngle: Int) {
    mockedNativeJpegTranscoder.verify(
        {
          NativeJpegTranscoder.transcodeJpeg(
              any(InputStream::class.java),
              any(OutputStream::class.java),
              eq(rotationAngle),
              eq(numerator),
              eq(DEFAULT_JPEG_QUALITY),
          )
        },
    )
  }

  private fun verifyJpegTranscoderExifOrientationInteractions(
      numerator: Int,
      exifOrientation: Int,
  ) {
    mockedNativeJpegTranscoder.verify(
        {
          NativeJpegTranscoder.transcodeJpegWithExifOrientation(
              any(InputStream::class.java),
              any(OutputStream::class.java),
              eq(exifOrientation),
              eq(numerator),
              eq(DEFAULT_JPEG_QUALITY),
          )
        },
    )
  }

  private fun verifyZeroJpegTranscoderInteractions() {
    mockedNativeJpegTranscoder.verify(
        {
          NativeJpegTranscoder.transcodeJpeg(
              any(InputStream::class.java),
              any(OutputStream::class.java),
              anyInt(),
              anyInt(),
              anyInt(),
          )
        },
        never(),
    )
  }

  private fun verifyZeroJpegTranscoderExifOrientationInteractions() {
    mockedNativeJpegTranscoder.verify(
        {
          NativeJpegTranscoder.transcodeJpegWithExifOrientation(
              any(InputStream::class.java),
              any(OutputStream::class.java),
              anyInt(),
              anyInt(),
              anyInt(),
          )
        },
        never(),
    )
  }

  private fun provideIntermediateResult(imageFormat: ImageFormat) {
    provideIntermediateResult(imageFormat, 800, 800, 0, ExifInterface.ORIENTATION_UNDEFINED)
  }

  private fun provideIntermediateResult(
      imageFormat: ImageFormat,
      width: Int,
      height: Int,
      rotationAngle: Int,
      exifOrientation: Int,
  ) {
    intermediateEncodedImage =
        buildEncodedImage(
            intermediateResult,
            imageFormat,
            width,
            height,
            rotationAngle,
            exifOrientation,
        )
    resizeAndRotateProducerConsumer!!.onNewResult(intermediateEncodedImage, Consumer.NO_FLAGS)
  }

  private fun provideFinalResult(imageFormat: ImageFormat) {
    provideFinalResult(imageFormat, 800, 800, 0, ExifInterface.ORIENTATION_UNDEFINED)
  }

  private fun provideFinalResult(
      imageFormat: ImageFormat,
      width: Int,
      height: Int,
      rotationAngle: Int,
      exifOrientation: Int,
  ) {
    finalEncodedImage =
        buildEncodedImage(finalResult, imageFormat, width, height, rotationAngle, exifOrientation)
    resizeAndRotateProducerConsumer!!.onNewResult(finalEncodedImage, Consumer.IS_LAST)
    fakeClockForScheduled.incrementBy(MIN_TRANSFORM_INTERVAL_MS.toLong())
    fakeClockForWorker.incrementBy(MIN_TRANSFORM_INTERVAL_MS.toLong())
  }

  private fun whenResizingEnabled() {
    whenResizingEnabledIs(true)
  }

  private fun whenResizingDisabled() {
    whenResizingEnabledIs(false)
  }

  private fun whenResizingEnabledIs(resizingEnabled: Boolean) {
    val nativeJpegTranscoder = NativeJpegTranscoder(resizingEnabled, MAX_BITMAP_SIZE, false, false)
    val jpegTranscoderFactory = mock(NativeJpegTranscoderFactory::class.java)
    `when`(jpegTranscoderFactory.createImageTranscoder(anyObject<ImageFormat>(), anyBoolean()))
        .thenReturn(nativeJpegTranscoder)

    resizeAndRotateProducer =
        ResizeAndRotateProducer(
            testExecutorService,
            mPooledByteBufferFactory,
            mInputProducer,
            resizingEnabled,
            jpegTranscoderFactory,
        )

    resizeAndRotateProducer.produceResults(mConsumer, mProducerContext)
  }

  private fun whenRequestWidthAndHeight(preferredWidth: Int, preferredHeight: Int) {
    `when`(mImageRequest.getPreferredWidth()).thenReturn(preferredWidth)
    `when`(mImageRequest.getPreferredHeight()).thenReturn(preferredHeight)
    var resizeOptions: ResizeOptions? = null
    if (preferredWidth > 0 || preferredHeight > 0) {
      resizeOptions = ResizeOptions(preferredWidth, preferredHeight)
    }
    `when`(mImageRequest.getResizeOptions()).thenReturn(resizeOptions)
  }

  private fun whenRequestSpecificRotation(@RotationOptions.RotationAngle rotationAngle: Int) {
    `when`(mImageRequest.getRotationOptions())
        .thenReturn(RotationOptions.forceRotation(rotationAngle))
  }

  private fun whenDisableRotation() {
    `when`(mImageRequest.getRotationOptions()).thenReturn(RotationOptions.disableRotation())
  }

  private fun whenRequestsRotationFromMetadataWithDeferringAllowed() {
    `when`(mImageRequest.getRotationOptions()).thenReturn(RotationOptions.autoRotateAtRenderTime())
  }

  private fun whenRequestsRotationFromMetadataWithoutDeferring() {
    `when`(mImageRequest.getRotationOptions()).thenReturn(RotationOptions.autoRotate())
  }

  companion object {
    init {
      TestNativeLoader.init()
    }

    private const val MIN_TRANSFORM_INTERVAL_MS = ResizeAndRotateProducer.MIN_TRANSFORM_INTERVAL_MS
    private const val MAX_BITMAP_SIZE = 2024

    private fun buildEncodedImage(
        pooledByteBufferRef: CloseableReference<PooledByteBuffer>,
        imageFormat: ImageFormat,
        width: Int,
        height: Int,
        rotationAngle: Int,
        exifOrientation: Int,
    ): EncodedImage {
      val encodedImage = EncodedImage(pooledByteBufferRef)
      encodedImage.imageFormat = imageFormat
      encodedImage.rotationAngle = rotationAngle
      encodedImage.exifOrientation = exifOrientation
      encodedImage.width = width
      encodedImage.height = height
      return encodedImage
    }
  }
}
