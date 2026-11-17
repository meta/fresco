/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.drawable

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import com.facebook.common.internal.Objects
import com.facebook.common.internal.Supplier
import com.facebook.common.internal.Suppliers
import com.facebook.drawee.drawable.ScalingUtils.StatefulScaleType
import kotlin.jvm.JvmField

/**
 * Drawable that can scale underlying drawable based on specified [ScaleType] options.
 *
 * Based on [android.widget.ImageView.ScaleType].
 */
class ScaleTypeDrawable : ForwardingDrawable {

  // Specified scale type.
  @VisibleForTesting var mScaleType: ScalingUtils.ScaleType

  @JvmField @VisibleForTesting var mScaleTypeState: Any? = null

  // Specified focus point to use with FOCUS_CROP.
  @JvmField @VisibleForTesting var mFocusPoint: PointF? = null

  // Last known dimensions of the underlying drawable. Used to avoid computing bounds every time
  // if underlying size hasn't changed.
  @JvmField @VisibleForTesting var mUnderlyingWidth: Int = 0

  @JvmField @VisibleForTesting var mUnderlyingHeight: Int = 0

  // Matrix that is actually being used for drawing.
  @JvmField @VisibleForTesting var mDrawMatrix: Matrix? = null

  // Temporary objects preallocated in advance to save future allocations.
  private val tempMatrix = Matrix()

  /**
   * Creates a new ScaleType drawable with given underlying drawable and scale type.
   *
   * @param drawable underlying drawable to apply scale type on
   * @param scaleType scale type to be applied
   */
  constructor(drawable: Drawable?, scaleType: ScalingUtils.ScaleType) : super(drawable) {
    this.mScaleType = scaleType
  }

  /**
   * Creates a new ScaleType drawable with given underlying drawable, scale type, and focus point.
   *
   * @param drawable underlying drawable to apply scale type on
   * @param scaleType scale type to be applied
   * @param focusPoint focus point of the image
   */
  constructor(
      drawable: Drawable?,
      scaleType: ScalingUtils.ScaleType,
      focusPoint: PointF?,
  ) : super(drawable) {
    this.mScaleType = scaleType
    this.mFocusPoint = focusPoint
  }

  override fun setCurrent(newDelegate: Drawable?): Drawable? {
    val previousDelegate = super.setCurrent(newDelegate)
    configureBounds()

    return previousDelegate
  }

  var scaleType: ScalingUtils.ScaleType
    /**
     * Gets the current scale type.
     *
     * @return scale type
     */
    get() = mScaleType
    /**
     * Sets the scale type.
     *
     * @param scaleType scale type to set
     */
    set(scaleType) {
      if (Objects.equal(this.mScaleType, scaleType)) {
        return
      }

      this.mScaleType = scaleType
      mScaleTypeState = null
      configureBounds()
      invalidateSelf()
    }

  var focusPoint: PointF?
    /**
     * Gets the focus point.
     *
     * @return focus point of the image
     */
    get() = mFocusPoint
    /**
     * Sets the focus point. If ScaleType.FOCUS_CROP is used, focus point will attempted to be
     * centered within a view. Each coordinate is a real number in [0,1] range, in the coordinate
     * system where top-left corner of the image corresponds to (0, 0) and the bottom-right corner
     * corresponds to (1, 1).
     *
     * @param focusPoint focus point of the image
     */
    set(focusPoint) {
      if (Objects.equal(this.mFocusPoint, focusPoint)) {
        return
      }
      if (focusPoint == null) {
        this.mFocusPoint = null
      } else {
        if (this.mFocusPoint == null) {
          this.mFocusPoint = PointF()
        }
        mFocusPoint!!.set(focusPoint)
      }
      configureBounds()
      invalidateSelf()
    }

  override fun draw(canvas: Canvas) {
    configureBoundsIfUnderlyingChanged()
    if (mDrawMatrix != null) {
      val saveCount = canvas.save()
      canvas.clipRect(bounds)
      canvas.concat(mDrawMatrix)
      super.draw(canvas)
      canvas.restoreToCount(saveCount)
    } else {
      // mDrawMatrix == null means our bounds match and we can take fast path
      super.draw(canvas)
    }
  }

  override fun onBoundsChange(bounds: Rect) {
    configureBounds()
  }

  private fun configureBoundsIfUnderlyingChanged() {
    var scaleTypeChanged = false
    if (mScaleType is StatefulScaleType) {
      val state: Any? = (mScaleType as StatefulScaleType).state
      scaleTypeChanged = (state == null || state != mScaleTypeState)
      mScaleTypeState = state
    }
    val current = current ?: return
    val underlyingChanged =
        mUnderlyingWidth != current.intrinsicWidth || mUnderlyingHeight != current.intrinsicHeight
    if (underlyingChanged || scaleTypeChanged) {
      configureBounds()
    }
  }

  /**
   * Determines bounds for the underlying drawable and a matrix that should be applied on it.
   * Adopted from android.widget.ImageView
   */
  @VisibleForTesting
  fun configureBounds() {
    val underlyingDrawable = current
    // If there is no underlying Drawable, we do not need a draw matrix.
    if (underlyingDrawable == null) {
      mUnderlyingHeight = 0
      mUnderlyingWidth = mUnderlyingHeight
      mDrawMatrix = null
      return
    }
    val bounds = bounds
    val viewWidth = bounds.width()
    val viewHeight = bounds.height()
    this.mUnderlyingWidth = underlyingDrawable.intrinsicWidth
    val underlyingWidth = this.mUnderlyingWidth
    this.mUnderlyingHeight = underlyingDrawable.intrinsicHeight
    val underlyingHeight = this.mUnderlyingHeight

    // If the drawable has no intrinsic size, we just fill our entire view.
    if (underlyingWidth <= 0 || underlyingHeight <= 0) {
      underlyingDrawable.bounds = bounds
      mDrawMatrix = null
      return
    }

    // If the drawable fits exactly, no transform is needed unless the host enables state updates.
    if (
        underlyingWidth == viewWidth &&
            underlyingHeight == viewHeight &&
            (mScaleType !is StatefulScaleType || !fixStatefulScaleTypeExactSizeBounds.get())
    ) {
      underlyingDrawable.bounds = bounds
      mDrawMatrix = null
      return
    }

    // If we're told to scale to fit, we just fill our entire view.
    // (ScaleType.getTransform would do, but this is faster)
    if (mScaleType === ScalingUtils.ScaleType.FIT_XY) {
      underlyingDrawable.bounds = bounds
      mDrawMatrix = null
      return
    }

    // We need to do the scaling ourselves, so have the underlying drawable use its preferred size.
    underlyingDrawable.setBounds(0, 0, underlyingWidth, underlyingHeight)
    tempMatrix.reset()
    mScaleType.getTransform(
        tempMatrix,
        bounds,
        underlyingWidth,
        underlyingHeight,
        if (mFocusPoint != null) mFocusPoint!!.x else 0.5f,
        if (mFocusPoint != null) mFocusPoint!!.y else 0.5f,
    )
    mDrawMatrix = tempMatrix
  }

  /**
   * TransformationCallback method
   *
   * @param transform
   */
  override fun getTransform(transform: Matrix) {
    getParentTransform(transform)
    // IMPORTANT: {@code configureBounds} should be called after {@code getParentTransform},
    // because the parent may have to change our bounds.
    configureBoundsIfUnderlyingChanged()
    if (mDrawMatrix != null) {
      transform.preConcat(mDrawMatrix)
    }
  }

  companion object {
    @JvmField var fixStatefulScaleTypeExactSizeBounds: Supplier<Boolean> = Suppliers.BOOLEAN_FALSE
  }
}
