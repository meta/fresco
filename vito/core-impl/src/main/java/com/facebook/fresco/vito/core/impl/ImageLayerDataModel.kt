/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.facebook.fresco.vito.options.BorderOptions
import com.facebook.fresco.vito.options.RoundingOptions
import com.facebook.fresco.vito.renderer.BitmapImageDataModel
import com.facebook.fresco.vito.renderer.CanvasTransformation
import com.facebook.fresco.vito.renderer.CanvasTransformationHandler
import com.facebook.fresco.vito.renderer.ImageDataModel

class ImageLayerDataModel(
    var drawableCallbackProvider: (() -> Drawable.Callback?)? = null,
    var invalidateLayerCallback: (() -> Unit)? = null,
    val optimizeAlphaHandling: Boolean = false,
    val resetOnZeroDurationFadeOut: Boolean = false,
    private val skipRedundantImageClip: Boolean = false,
) {
  private var dataModel: ImageDataModel? = null
  private var roundingOptions: RoundingOptions? = null
  private var borderOptions: BorderOptions? = null
  private var currentBounds: Rect? = null
  val canvasTransformationHandler: CanvasTransformationHandler = CanvasTransformationHandler(null)
  private val paint: Paint = Paint()

  private var renderCommand: ((Canvas) -> Unit)? = null
  private var colorFilter: ColorFilter? = null

  private var fadeAnimator: ValueAnimator? = null

  private val animatorUpdateListener = ValueAnimator.AnimatorUpdateListener {
    setAlpha(it.animatedValue as Int)
  }

  fun getDataModel(): ImageDataModel? {
    return dataModel
  }

  fun configure(
      dataModel: ImageDataModel? = this.dataModel,
      roundingOptions: RoundingOptions? = this.roundingOptions,
      borderOptions: BorderOptions? = this.borderOptions,
      canvasTransformation: CanvasTransformation? =
          canvasTransformationHandler.canvasTransformation,
      bounds: Rect? = this.currentBounds,
      colorFilter: ColorFilter? = this.colorFilter,
  ) {
    if (dataModel != this.dataModel) {
      this.dataModel?.apply {
        onDetach()
        setCallback(null)
      }
      this.dataModel = dataModel?.apply {
        setCallback(drawableCallbackProvider?.invoke())
        onAttach()
      }
    }
    this.roundingOptions = roundingOptions
    this.borderOptions = borderOptions
    this.currentBounds = bounds
    this.colorFilter = colorFilter
    canvasTransformationHandler.canvasTransformation = canvasTransformation
    // TODO(T105148151): only invalidate if changed
    invalidateRenderCommand()
    if (bounds != null) {
      computeRenderCommand(bounds)
    }
  }

  fun invalidateRenderCommand() {
    renderCommand = null
  }

  private fun computeRenderCommand(bounds: Rect, alpha: Int = 255) {
    val model = dataModel
    if (model == null) {
      renderCommand = null
      return
    }
    if (renderCommand != null && currentBounds == bounds) {
      return
    }

    currentBounds = bounds
    canvasTransformationHandler.configure(bounds, model.width, model.height)
    paint.colorFilter = colorFilter
    paint.flags = model.defaultPaintFlags
    renderCommand =
        ImageWithTransformationAndBorderRenderer.createRenderCommand(
            model,
            roundingOptions,
            borderOptions,
            canvasTransformationHandler.getMatrix(),
            bounds,
            paint,
            alpha,
            skipRedundantImageClip = skipRedundantImageClip,
        )
  }

  fun draw(canvas: Canvas) {
    renderCommand?.let { it(canvas) }
  }

  fun reset(endAnimator: Boolean = true) {
    canvasTransformationHandler.canvasTransformation = null
    dataModel?.apply {
      onDetach()
      setCallback(null)
    }
    dataModel = null
    roundingOptions = null
    borderOptions = null
    renderCommand = null
    currentBounds = null
    paint.reset()
    colorFilter = null

    if (endAnimator) {
      // Placed inside if block to prevent recursion (T134570663)
      fadeAnimator?.end()
    }
    fadeAnimator = null
  }

  fun fadeIn(durationMs: Int, onAnimationEnd: (() -> Unit)? = null) {
    fadeAnimator?.end()
    if (durationMs == 0) {
      paint.alpha = 255
      // TODO: Do we need to invalidate?
      return
    }
    fadeAnimator =
        ValueAnimator.ofInt(0, 255).apply {
          duration = durationMs.toLong()
          addUpdateListener(animatorUpdateListener)
          if (onAnimationEnd != null) {
            addListener(
                object : AnimatorListenerAdapter() {
                  override fun onAnimationEnd(animation: Animator) {
                    onAnimationEnd()
                  }
                },
            )
          }
          start()
        }
  }

  fun fadeOut(durationMs: Int, resetLayerWhenInvisible: Boolean = false) {
    fadeAnimator?.end()
    if (durationMs == 0) {
      paint.alpha = 0
      if (resetOnZeroDurationFadeOut && resetLayerWhenInvisible) {
        reset(endAnimator = false)
      }
      return
    }
    fadeAnimator =
        ValueAnimator.ofInt(255, 0).apply {
          duration = durationMs.toLong()
          addUpdateListener(animatorUpdateListener)
          if (resetLayerWhenInvisible) {
            addListener(
                object : AnimatorListenerAdapter() {
                  override fun onAnimationEnd(animation: Animator) {
                    reset(false)
                  }
                },
            )
          }
          start()
        }
    // TODO use fadeAnimator.reverse() and reuse
  }

  fun setAlpha(alpha: Int) {
    if (!optimizeAlphaHandling || paint.alpha != alpha) {
      paint.alpha = alpha
      invalidateLayerCallback?.invoke()
    }
  }

  fun getAlpha(): Int = paint.alpha

  fun hasBitmapWithGainmap(): Boolean = dataModel.let {
    it is BitmapImageDataModel && it.hasGainmap()
  }
}
