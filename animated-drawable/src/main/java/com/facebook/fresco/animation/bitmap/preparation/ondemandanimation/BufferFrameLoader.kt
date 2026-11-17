/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.animation.bitmap.preparation.ondemandanimation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import com.facebook.common.references.CloseableReference
import com.facebook.fresco.animation.backend.AnimationInformation
import com.facebook.fresco.animation.bitmap.BitmapFrameRenderer
import com.facebook.fresco.animation.bitmap.preparation.loadframe.AnimationLoaderExecutor
import com.facebook.fresco.animation.bitmap.preparation.loadframe.FpsCompressorInfo
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.set

/**
 * This frame loader uses a fixed number of bitmap. The buffer loads the next bunch of frames when
 * the animation render an specific threshold frame
 */
class BufferFrameLoader(
    private val platformBitmapFactory: PlatformBitmapFactory,
    private val bitmapFrameRenderer: BitmapFrameRenderer,
    private val fpsCompressor: FpsCompressorInfo,
    override val animationInformation: AnimationInformation,
    private val bufferLengthMilliseconds: Int,
    private val enableBufferFrameLoaderFix: Boolean = false,
    private val frameLoaderListener: FrameLoaderListener? = null,
    private val enableSingleFrameRendering: Boolean = false,
) : FrameLoader {

  private val bufferSize =
      ((animationInformation.fps() * bufferLengthMilliseconds) / 1000).coerceAtLeast(1)
  private val isSingleFrame = enableSingleFrameRendering && animationInformation.frameCount == 1
  @Volatile private var singleFrameRef: CloseableReference<Bitmap>? = null

  private val bufferFramesHash = ConcurrentHashMap<Int, BufferFrame>()
  @Volatile private var thresholdFrame: Int
  @Volatile private var isFetching = false
  @Volatile private var loadGeneration = 0

  private val frameSequence = CircularList(animationInformation.frameCount)
  private var lastRenderedFrameNumber: Int = -1
  @Volatile private var compressionFrameMap: Map<Int, Int> = emptyMap()
  @Volatile private var renderableFrameIndexes: Set<Int> = emptySet()

  init {
    compressToFps(animationInformation.fps())
    thresholdFrame = bufferSize.times(THRESHOLD_PERCENTAGE).toInt()
  }

  @UiThread
  override fun getFrame(frameNumber: Int, width: Int, height: Int): FrameResult {
    if (isSingleFrame) {
      return getSingleFrame(width, height)
    }

    val cachedFrameIndex = compressionFrameMap[frameNumber]

    // Return the nearest frame if the frame is not in the buffer OR width or height is 0
    if (enableBufferFrameLoaderFix && (width <= 0 || height <= 0)) {
      frameLoaderListener?.onZeroFrameDimensions(
          origin = "BufferFrameLoader.getFrame",
          frameNumber,
          width,
          height,
      )

      return findNearestToRender(frameNumber)
    }

    if (cachedFrameIndex == null) {
      return findNearestToRender(frameNumber)
    }

    lastRenderedFrameNumber = cachedFrameIndex

    val cachedFrame = bufferFramesHash[cachedFrameIndex]?.takeIf { it.isFrameAvailable }

    if (cachedFrame != null) {
      val isTargetAhead = frameSequence.isTargetAhead(thresholdFrame, cachedFrameIndex, bufferSize)
      if (isTargetAhead) {
        loadNextFrames(width, height)
      }
      return FrameResult(cachedFrame.bitmapRef.clone(), FrameResult.FrameType.SUCCESS)
    }

    loadNextFrames(width, height)
    return findNearestToRender(cachedFrameIndex)
  }

  @UiThread
  private fun getSingleFrame(width: Int, height: Int): FrameResult {
    frameLoaderListener?.onSingleFrameRender(
        origin = "BufferFrameLoader.getSingleFrame",
        width,
        height,
    )

    singleFrameRef?.let { ref ->
      val clone = ref.cloneOrNull()
      if (clone != null) {
        return FrameResult(clone, FrameResult.FrameType.SUCCESS)
      }
    }

    if (width <= 0 || height <= 0) {
      return FrameResult(null, FrameResult.FrameType.MISSING)
    }

    val bitmapRef = platformBitmapFactory.createBitmap(width, height)
    try {
      bitmapFrameRenderer.renderFrame(0, bitmapRef.get())
    } catch (renderFailure: Throwable) {
      CloseableReference.closeSafely(bitmapRef)
      throw renderFailure
    }
    singleFrameRef = bitmapRef
    return FrameResult(bitmapRef.clone(), FrameResult.FrameType.SUCCESS)
  }

  @UiThread
  private fun findNearestToRender(targetFrame: Int): FrameResult {
    val nearestFrame = findNearestFrame(targetFrame)

    return if (nearestFrame != null) {
      val bitmapRef = nearestFrame.bitmap.clone()
      lastRenderedFrameNumber = nearestFrame.frameNumber
      FrameResult(bitmapRef, FrameResult.FrameType.NEAREST)
    } else {
      FrameResult(null, FrameResult.FrameType.MISSING)
    }
  }

  @UiThread
  override fun prepareFrames(width: Int, height: Int, onAnimationLoaded: () -> Unit) {
    if (isSingleFrame) {
      onAnimationLoaded()
      return
    }
    loadNextFrames(width, height)
    onAnimationLoaded()
  }

  override fun compressToFps(fps: Int) {
    val durationMs =
        animationInformation.loopDurationMs.times(animationInformation.loopCount.coerceAtLeast(1))
    compressionFrameMap =
        fpsCompressor.calculateReducedIndexes(
            durationMs = durationMs,
            frameCount = animationInformation.frameCount,
            targetFps = fps.coerceAtMost(animationInformation.fps()),
        )

    renderableFrameIndexes = compressionFrameMap.values.toSet()
  }

  /** Release all bitmaps */
  override fun clear() {
    loadGeneration++
    isFetching = false
    CloseableReference.closeSafely(singleFrameRef)
    singleFrameRef = null
    bufferFramesHash.values.forEach { it.release() }
    bufferFramesHash.clear()
    lastRenderedFrameNumber = -1
  }

  private fun loadNextFrames(width: Int, height: Int) {
    if (enableBufferFrameLoaderFix && (width <= 0 || height <= 0)) {
      frameLoaderListener?.onZeroFrameDimensions(
          origin = "BufferFrameLoader.loadNextFrames",
          frameNumber = lastRenderedFrameNumber.coerceAtLeast(0),
          width,
          height,
      )

      return
    }
    if (isFetching) {
      return
    }

    isFetching = true
    val generation = loadGeneration
    AnimationLoaderExecutor.execute {
      try {
        do {
          if (generation != loadGeneration) {
            return@execute
          }
          val targetFrame = lastRenderedFrameNumber.coerceAtLeast(0)
          val success = extractDemandedFrame(targetFrame, width, height, generation)
        } while (!success)
      } finally {
        if (generation == loadGeneration) {
          isFetching = false
        }
      }
    }
  }

  @WorkerThread
  private fun extractDemandedFrame(
      targetFrame: Int,
      width: Int,
      height: Int,
      generation: Int,
  ): Boolean {
    val nextWindow =
        frameSequence.sublist(targetFrame, bufferSize).filter {
          renderableFrameIndexes.contains(it)
        }
    val nextWindowIndexes = nextWindow.toSet()
    val oldFramesNumbers = ArrayDeque(bufferFramesHash.keys.minus(nextWindowIndexes))

    // Load new frames
    nextWindow.forEach { newFrameNumber ->
      if (generation != loadGeneration) {
        return true
      }
      if (bufferFramesHash[newFrameNumber] != null) {
        return@forEach
      }

      if (lastRenderedFrameNumber != -1 && !nextWindowIndexes.contains(lastRenderedFrameNumber)) {
        return false
      }

      val deprecatedFrameNumber = oldFramesNumbers.pollFirst() ?: -1
      val cachedFrame = bufferFramesHash[deprecatedFrameNumber]
      val bufferFrame: BufferFrame
      val bitmapRef: CloseableReference<Bitmap>
      val ref = cachedFrame?.bitmapRef?.cloneOrNull()

      if (ref != null) {
        bufferFrame = cachedFrame
        bitmapRef = ref
      } else {
        bufferFrame = BufferFrame(platformBitmapFactory.createBitmap(width, height))
        bitmapRef = bufferFrame.bitmapRef.clone()
      }
      bufferFrame.isUpdatingFrame = true
      bitmapRef.use { obtainFrame(it, newFrameNumber, width, height) }
      if (generation != loadGeneration) {
        bufferFrame.isUpdatingFrame = false
        if (bufferFrame !== cachedFrame) {
          bufferFrame.release()
        }
        return true
      }
      bufferFramesHash.remove(deprecatedFrameNumber)
      bufferFrame.isUpdatingFrame = false

      bufferFramesHash[newFrameNumber] = bufferFrame
    }

    thresholdFrame =
        if (nextWindow.isEmpty()) bufferSize.times(THRESHOLD_PERCENTAGE).toInt()
        else {
          val windowSize = nextWindow.size
          val middlePoint =
              windowSize.times(THRESHOLD_PERCENTAGE).toInt().coerceIn(0, windowSize - 1)
          nextWindow[middlePoint]
        }
    return true
  }

  private fun obtainFrame(
      targetBitmap: CloseableReference<Bitmap>,
      targetFrame: Int,
      width: Int,
      height: Int,
  ) {
    val nearestFrame = findNearestFrame(targetFrame)

    nearestFrame?.bitmap?.cloneOrNull()?.use { nearestBitmap ->
      val from = nearestFrame.frameNumber

      if (from < targetFrame) {
        targetBitmap.set(nearestBitmap.get())
        (from + 1..targetFrame).forEach { bitmapFrameRenderer.renderFrame(it, targetBitmap.get()) }
        return
      }
    }

    targetBitmap.clear()
    (0..targetFrame).forEach { bitmapFrameRenderer.renderFrame(it, targetBitmap.get()) }
  }

  private fun findNearestFrame(targetFrame: Int): AnimationBitmapFrame? =
      (0..frameSequence.size).firstNotNullOfOrNull { delta ->
        val closestFrame = frameSequence.getPosition(targetFrame - delta)
        bufferFramesHash[closestFrame]
            ?.takeIf { it.isFrameAvailable }
            ?.let { AnimationBitmapFrame(closestFrame, it.bitmapRef) }
      }

  private fun CloseableReference<Bitmap>.clear() {
    if (isValid) {
      Canvas(get()).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }
  }

  private fun CloseableReference<Bitmap>.set(src: Bitmap): CloseableReference<Bitmap> {
    if (isValid && get() != src) {
      val canvas = Canvas(get())
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      canvas.drawBitmap(src, 0f, 0f, null)
    }
    return this
  }

  private fun AnimationInformation.fps(): Int =
      TimeUnit.SECONDS.toMillis(1).div(loopDurationMs.div(frameCount)).coerceAtLeast(1).toInt()

  private class BufferFrame(val bitmapRef: CloseableReference<Bitmap>) {
    var isUpdatingFrame: Boolean = false
    val isFrameAvailable: Boolean
      get(): Boolean = !isUpdatingFrame && bitmapRef.isValid

    fun release() {
      CloseableReference.closeSafely(bitmapRef)
    }
  }

  companion object {

    /**
     * Used to calculate the threshold frame for triggering the next buffer load from the last
     * render frame
     */
    private const val THRESHOLD_PERCENTAGE = 0.5f
  }
}
