/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.animation.bitmap.preparation.ondemandanimation

import android.os.Handler
import android.os.HandlerThread
import com.facebook.fresco.animation.bitmap.preparation.ondemandanimation.FrameResult.FrameType
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object AnimationCoordinator {

  /** Frequency in ms to adjust the rendering fps based con device performance */
  private const val FREQUENCY_PERFORMANCE_MS = 2000L
  private const val FREQUENCY_LOADERS_MS = 10000L

  /** This is the % of fps that animation will increase or decrease based on device performance */
  private const val FPS_STEP_PERCENTAGE = 0.20f

  /** Minimum rendering fps percentage */
  private const val MIN_RENDERING_FPS_PERCENTAGE = 0.50f

  private val successCounter = AtomicInteger(0)
  private val failuresCounter = AtomicInteger(0)
  private val criticalCounter = AtomicInteger(0)
  private val runningAnimations = ConcurrentHashMap<DynamicRenderingFps, Int>()
  private val handler: Handler by lazy {
    val handlerThread = HandlerThread("FrescoAnimationWorker")
    handlerThread.start()
    Handler(handlerThread.looper)
  }

  private val calculatePerformance: Runnable = Runnable {
    val success = successCounter.getAndSet(0).toFloat()
    val failures = failuresCounter.getAndSet(0).toFloat()
    val critical = criticalCounter.getAndSet(0).toFloat()
    val totalFrames = success + failures + critical

    if (totalFrames > 0) {
      val successRatio = success.div(totalFrames)
      val failuresRatio = failures.div(totalFrames)
      val criticalRatio = critical.div(totalFrames)

      // Verify that device performance can render all needed frames
      if (failuresRatio > 0.25f || criticalRatio > 0.1f) {
        // If animation performance is not enough, then decrease the rendering fps
        runningAnimations.forEach { (animation, fpsStep) ->
          updateRenderingFps(animation, -fpsStep)
        }
      } else if (successRatio > 0.98f) {
        // Animation performance is good, then we can increase the rendering fps
        runningAnimations.forEach { (animation, fpsStep) -> updateRenderingFps(animation, fpsStep) }
      } else {
        // Performance is good enough, but to increase rendering fps could be risky
      }
      runningAnimations.clear()
    }

    schedulePerformance()
  }

  private val clearUnusedFrameLoaders: Runnable = Runnable {
    val maxUnusedTime = System.currentTimeMillis() - FREQUENCY_LOADERS_MS
    FrameLoaderFactory.clearUnusedUntil(Date(maxUnusedTime))
    scheduleLoaders()
  }

  init {
    handler.post(calculatePerformance)
    handler.post(clearUnusedFrameLoaders)
  }

  private fun schedulePerformance() =
      handler.postDelayed(calculatePerformance, FREQUENCY_PERFORMANCE_MS)

  private fun scheduleLoaders() = handler.postDelayed(clearUnusedFrameLoaders, FREQUENCY_LOADERS_MS)

  private fun updateRenderingFps(animation: DynamicRenderingFps, delta: Int) {
    val minRenderingFps =
        animation.animationFps.times(MIN_RENDERING_FPS_PERCENTAGE).coerceAtLeast(1f).toInt()
    val renderingFps =
        (animation.renderingFps + delta).coerceIn(minRenderingFps, animation.animationFps)

    if (renderingFps != animation.renderingFps) {
      animation.setRenderingFps(renderingFps)
    }
  }

  /**
   * This method is executed everytime that a frame is render in one animation. This allow to
   * collect what is the running animation performance per [FREQUENCY_PERFORMANCE_MS] We will adjust
   * the animation performance based on this data.
   */
  fun onRenderFrame(animation: DynamicRenderingFps, frameResult: FrameResult) {
    if (!runningAnimations.containsKey(animation)) {
      val fps = animation.animationFps
      val fpsStep = fps.times(FPS_STEP_PERCENTAGE).toInt()

      runningAnimations[animation] = fpsStep
    }

    when (frameResult.type) {
      FrameType.SUCCESS -> successCounter.incrementAndGet()
      FrameType.NEAREST -> failuresCounter.incrementAndGet()
      FrameType.MISSING -> criticalCounter.incrementAndGet()
    }
  }
}

/** This interface allow animations to adjust their fps according with the device performance. */
interface DynamicRenderingFps {
  /** Animation FPS provided by the original asset */
  val animationFps: Int

  /** Render animation FPS. These are time-varying based on the performance of the device. */
  val renderingFps: Int

  /**
   * Update the render fps to [renderingFps]. This number is calculated based on the range from
   * [AnimationCoordinator.MIN_RENDERING_FPS_PERCENTAGE] to [animationFps]
   */
  fun setRenderingFps(renderingFps: Int)
}
