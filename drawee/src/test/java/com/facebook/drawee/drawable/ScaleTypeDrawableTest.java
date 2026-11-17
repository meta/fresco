/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.drawable;

import static com.facebook.drawee.drawable.ScalingUtils.ScaleType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import com.facebook.common.internal.Suppliers;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ScaleTypeDrawableTest {
  private final Drawable mUnderlyingDrawable = mock(Drawable.class);
  private final PointF mFocusPoint = new PointF(0.1f, 0.4f);
  private final Drawable.Callback mCallback = mock(Drawable.Callback.class);
  private final Rect mViewBounds = new Rect(10, 10, 410, 310);
  private ScaleTypeDrawable mScaleTypeDrawable;

  @Before
  public void setUp() {
    ScaleTypeDrawable.fixStatefulScaleTypeExactSizeBounds = Suppliers.BOOLEAN_FALSE;
    mScaleTypeDrawable = new ScaleTypeDrawable(mUnderlyingDrawable, ScaleType.CENTER);
    mScaleTypeDrawable.setCallback(mCallback);
  }

  @After
  public void tearDown() {
    ScaleTypeDrawable.fixStatefulScaleTypeExactSizeBounds = Suppliers.BOOLEAN_FALSE;
  }

  @Test
  public void testIntrinsicDimensions() {
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(100);
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(200);
    assertThat(mScaleTypeDrawable.getIntrinsicWidth()).isEqualTo(100);
    assertThat(mScaleTypeDrawable.getIntrinsicHeight()).isEqualTo(200);
  }

  @Test
  public void testBasics() {
    // initial state
    assertThat(mScaleTypeDrawable.getCurrent()).isEqualTo(mUnderlyingDrawable);
    assertThat(mScaleTypeDrawable.getScaleType()).isEqualTo(ScaleType.CENTER);
    assertThat(mScaleTypeDrawable.getFocusPoint()).isNull();

    mScaleTypeDrawable.setScaleType(ScaleType.FIT_XY);
    assertThat(mScaleTypeDrawable.getScaleType()).isEqualTo(ScaleType.FIT_XY);

    mScaleTypeDrawable.setScaleType(ScaleType.FOCUS_CROP);
    assertThat(mScaleTypeDrawable.getScaleType()).isEqualTo(ScaleType.FOCUS_CROP);

    mScaleTypeDrawable.setFocusPoint(mFocusPoint);
    AndroidGraphicsTestUtils.assertEquals(mFocusPoint, mScaleTypeDrawable.getFocusPoint(), 0f);
  }

  @Test
  public void testConfigureBounds_NoIntrinsicDimensions() {
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.FIT_XY, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.FIT_START, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.FIT_CENTER, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.FIT_END, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.CENTER, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.CENTER_INSIDE, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.CENTER_CROP, mViewBounds);
    testConfigureBounds_NoIntrinsicDimensions(ScaleType.FOCUS_CROP, mViewBounds);
  }

  private void testConfigureBounds_NoIntrinsicDimensions(ScaleType scaleType, Rect viewBounds) {
    mScaleTypeDrawable.setScaleType(scaleType);
    mScaleTypeDrawable.setBounds(viewBounds);
    reset(mUnderlyingDrawable);
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(-1);
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(-1);
    mScaleTypeDrawable.configureBounds();
    verify(mUnderlyingDrawable).getIntrinsicWidth();
    verify(mUnderlyingDrawable).getIntrinsicHeight();
    verify(mUnderlyingDrawable).setBounds(viewBounds);
    assertThat(mScaleTypeDrawable.mDrawMatrix).isNull();
    verifyNoMoreInteractions(mUnderlyingDrawable);
  }

  @Test
  public void testConfigureBounds_SameAsView() {
    testConfigureBounds_SameAsView(ScaleType.FIT_XY, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.FIT_START, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.FIT_CENTER, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.FIT_END, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.CENTER, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.CENTER_INSIDE, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.CENTER_CROP, mViewBounds);
    testConfigureBounds_SameAsView(ScaleType.FOCUS_CROP, mViewBounds);
  }

  private void testConfigureBounds_SameAsView(ScaleType scaleType, Rect viewBounds) {
    mScaleTypeDrawable.setScaleType(scaleType);
    mScaleTypeDrawable.setBounds(viewBounds);
    reset(mUnderlyingDrawable);
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(viewBounds.width());
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(viewBounds.height());
    mScaleTypeDrawable.configureBounds();
    verify(mUnderlyingDrawable).getIntrinsicWidth();
    verify(mUnderlyingDrawable).getIntrinsicHeight();
    verify(mUnderlyingDrawable).setBounds(viewBounds);
    assertThat(mScaleTypeDrawable.mDrawMatrix).isNull();
    verifyNoMoreInteractions(mUnderlyingDrawable);
  }

  @Test
  public void testConfigureBounds_SameAsView_StatefulScaleTypeUsesFastPathWhenFixDisabled() {
    TestStatefulScaleType scaleType = new TestStatefulScaleType();
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(mViewBounds.width());
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(mViewBounds.height());

    mScaleTypeDrawable.setScaleType(scaleType);
    mScaleTypeDrawable.setBounds(mViewBounds);

    assertThat(scaleType.mParentBounds.isEmpty()).isTrue();
    assertThat(scaleType.mTransformCallCount).isEqualTo(1);
    assertThat(mScaleTypeDrawable.mDrawMatrix).isNull();
  }

  @Test
  public void testConfigureBounds_SameAsView_StatefulScaleTypeReceivesBoundsWhenFixEnabled() {
    TestStatefulScaleType scaleType = new TestStatefulScaleType();
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(mViewBounds.width());
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(mViewBounds.height());
    ScaleTypeDrawable.fixStatefulScaleTypeExactSizeBounds = Suppliers.BOOLEAN_TRUE;

    mScaleTypeDrawable.setScaleType(scaleType);
    assertThat(scaleType.mParentBounds.isEmpty()).isTrue();

    mScaleTypeDrawable.setBounds(mViewBounds);

    assertThat(scaleType.mParentBounds).isEqualTo(mViewBounds);
    assertThat(scaleType.mTransformCallCount).isEqualTo(2);
    assertThat(mScaleTypeDrawable.mDrawMatrix).isNotNull();
  }

  @Test
  public void testConfigureBounds_FIT_XY() {
    mScaleTypeDrawable.setScaleType(ScaleType.FIT_XY);
    mScaleTypeDrawable.setBounds(mViewBounds);
    reset(mUnderlyingDrawable);
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(40);
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(30);
    mScaleTypeDrawable.configureBounds();
    verify(mUnderlyingDrawable).getIntrinsicWidth();
    verify(mUnderlyingDrawable).getIntrinsicHeight();
    verify(mUnderlyingDrawable).setBounds(mViewBounds);
    assertThat(mScaleTypeDrawable.mDrawMatrix).isNull();
    verifyNoMoreInteractions(mUnderlyingDrawable);
  }

  /**
   * Underlying drawable's aspect ratio is bigger than view's, so it has to be slided horizontally
   * after scaling.
   */
  @Test
  public void testConfigureBounds_CENTER_CROP_H() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 400;
    int height = 200;
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(1.5f, 1.5f);
    expectedMatrix.postTranslate(-89, 10);
    testConfigureBounds(bounds, width, height, ScaleType.CENTER_CROP, null, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is smaller than view's, so it has to be slided vertically
   * after scaling.
   */
  @Test
  public void testConfigureBounds_CENTER_CROP_V() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 200;
    int height = 300;
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(2.0f, 2.0f);
    expectedMatrix.postTranslate(10, -139);
    testConfigureBounds(bounds, width, height, ScaleType.CENTER_CROP, null, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is bigger than view's, so it has to be slided horizontally
   * after scaling. Focus point is too much left, so it cannot be completely centered. Left-most
   * part of the image is displayed.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_HL() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 400;
    int height = 200;
    PointF focusPoint = new PointF(0.1f, 0.5f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(1.5f, 1.5f);
    expectedMatrix.postTranslate(10, 10);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is bigger than view's, so it has to be slided horizontally
   * after scaling. Focus point is at 40% and it can be completely centered.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_HC() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 400;
    int height = 200;
    PointF focusPoint = new PointF(0.40f, 0.5f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(1.5f, 1.5f);
    expectedMatrix.postTranslate(-29, 10);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is bigger than view's, so it has to be slided horizontally
   * after scaling. Focus point is too much right, so it cannot be completely centered. Right-most
   * part of the image is displayed.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_HR() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 400;
    int height = 200;
    PointF focusPoint = new PointF(0.9f, 0.5f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(1.5f, 1.5f);
    expectedMatrix.postTranslate(-189, 10);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is smaller than view's, so it has to be slided vertically
   * after scaling. Focus point is too much top, so it cannot be completely centered. Top-most part
   * of the image is displayed.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_VT() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 200;
    int height = 300;
    PointF focusPoint = new PointF(0.5f, 0.1f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(2.0f, 2.0f);
    expectedMatrix.postTranslate(10, 10);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
  }

  /**
   * Underlying drawable's aspect ratio is smaller than view's, so it has to be slided vertically
   * after scaling. Focus point is at 40% and it can be completely centered.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_VC() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 200;
    int height = 300;
    PointF focusPoint = new PointF(0.5f, 0.4f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(2.0f, 2.0f);
    expectedMatrix.postTranslate(10, -79);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
    // expected bounds of the actual image after the scaling has been performed (without cropping)
    testActualImageBounds(new RectF(10f, -79f, 410f, 521f));
  }

  /**
   * Underlying drawable's aspect ratio is smaller than view's, so it has to be slided vertically
   * after scaling. Focus point is too much bottom, so it cannot be completely centered. Bottom-most
   * part of the image is displayed.
   */
  @Test
  public void testConfigureBounds_FOCUS_CROP_VB() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 200;
    int height = 300;
    PointF focusPoint = new PointF(0.5f, 0.9f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(2.0f, 2.0f);
    expectedMatrix.postTranslate(10, -289);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);
  }

  @Test
  public void testConfigureBoundsNoOpWhenScaleTypeNotChanged() {
    Rect bounds = new Rect(10, 10, 410, 310);
    int width = 200;
    int height = 300;
    PointF focusPoint = new PointF(0.5f, 0.9f);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.setScale(2.0f, 2.0f);
    expectedMatrix.postTranslate(10, -289);
    testConfigureBounds(bounds, width, height, ScaleType.FOCUS_CROP, focusPoint, expectedMatrix);

    mScaleTypeDrawable.setScaleType(ScaleType.FOCUS_CROP);
    verifyNoMoreInteractions(mUnderlyingDrawable);
  }

  private void testConfigureBounds(
      Rect viewBounds,
      int underlyingWidth,
      int underlyingHeight,
      ScaleType scaleType,
      @Nullable PointF focusPoint,
      Matrix expectedMatrix) {
    mScaleTypeDrawable.setScaleType(scaleType);
    if (focusPoint != null) {
      mScaleTypeDrawable.setFocusPoint(focusPoint);
    }
    mScaleTypeDrawable.setBounds(viewBounds);
    reset(mUnderlyingDrawable);
    when(mUnderlyingDrawable.getIntrinsicWidth()).thenReturn(underlyingWidth);
    when(mUnderlyingDrawable.getIntrinsicHeight()).thenReturn(underlyingHeight);
    mScaleTypeDrawable.configureBounds();
    verify(mUnderlyingDrawable).getIntrinsicWidth();
    verify(mUnderlyingDrawable).getIntrinsicHeight();
    verify(mUnderlyingDrawable).setBounds(0, 0, underlyingWidth, underlyingHeight);
    AndroidGraphicsTestUtils.assertEquals(expectedMatrix, mScaleTypeDrawable.mDrawMatrix);
    verifyNoMoreInteractions(mUnderlyingDrawable);
  }

  private void testActualImageBounds(RectF expectedActualImageBounds) {
    // TODO(5469563): enable this once we have a decent implementation of ShadowMatrix
    // RectF actualImageBounds = new RectF();
    // mScaleTypeDrawable.getTransformedBounds(actualImageBounds);
    // Assert.assertEquals(expectedActualImageBounds, actualImageBounds);
  }

  private static class TestStatefulScaleType implements ScaleType, ScalingUtils.StatefulScaleType {
    private final Rect mParentBounds = new Rect();
    private int mTransformCallCount;

    @Override
    public Matrix getTransform(
        Matrix outTransform,
        Rect parentBounds,
        int childWidth,
        int childHeight,
        float focusX,
        float focusY) {
      mTransformCallCount++;
      mParentBounds.set(parentBounds);
      return ScaleType.FIT_XY.getTransform(
          outTransform, parentBounds, childWidth, childHeight, focusX, focusY);
    }

    @Override
    public String getDescription() {
      return "test_stateful";
    }

    @Override
    public Object getState() {
      return "state";
    }
  }
}
