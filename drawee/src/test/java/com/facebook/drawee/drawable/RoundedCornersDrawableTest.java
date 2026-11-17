/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.drawable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RoundedCornersDrawableTest {

  private Drawable mUnderlyingDrawable;
  private RoundedCornersDrawable mRoundedCornersDrawable;
  private Drawable.Callback mCallback;

  @Before
  public void setup() {
    mUnderlyingDrawable = mock(Drawable.class);
    mCallback = mock(Drawable.Callback.class);
    mRoundedCornersDrawable = new RoundedCornersDrawable(mUnderlyingDrawable);
    mRoundedCornersDrawable.setCallback(mCallback);
  }

  @Test
  public void testInitialSetup() {
    assertThat(mRoundedCornersDrawable.mType).isEqualTo(RoundedCornersDrawable.Type.OVERLAY_COLOR);
    assertThat(mRoundedCornersDrawable.isCircle()).isFalse();
    assertThat(mRoundedCornersDrawable.getRadii())
        .containsExactly(new float[] {0, 0, 0, 0, 0, 0, 0, 0});
    assertThat(mRoundedCornersDrawable.mPaint.getColor()).isEqualTo(0);
  }

  @Test
  public void testSetType() {
    RoundedCornersDrawable.Type type = RoundedCornersDrawable.Type.CLIPPING;
    mRoundedCornersDrawable.setType(type);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.mType).isEqualTo(type);
  }

  @Test
  public void testSetCircle() {
    mRoundedCornersDrawable.setCircle(true);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.isCircle()).isTrue();
  }

  @Test
  public void testSetRadii() {
    mRoundedCornersDrawable.setRadii(new float[] {1, 2, 3, 4, 5, 6, 7, 8});
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getRadii())
        .containsExactly(new float[] {1, 2, 3, 4, 5, 6, 7, 8});
  }

  @Test
  public void testSetRadius() {
    mRoundedCornersDrawable.setRadius(9);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getRadii())
        .containsExactly(new float[] {9, 9, 9, 9, 9, 9, 9, 9});
  }

  @Test
  public void testSetOverlayColor() {
    int overlayColor = 0xC0123456;
    mRoundedCornersDrawable.setOverlayColor(overlayColor);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getOverlayColor()).isEqualTo(overlayColor);
  }

  @Test
  public void testSetBorder() {
    float borderWidth = 0.7f;
    int borderColor = Color.CYAN;
    mRoundedCornersDrawable.setBorder(borderColor, borderWidth);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getBorderColor()).isEqualTo(borderColor);
    assertThat(mRoundedCornersDrawable.getBorderWidth()).isEqualTo(borderWidth);
  }

  @Test
  public void testSetPadding() {
    float padding = 10;
    mRoundedCornersDrawable.setPadding(padding);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getPadding()).isEqualTo(padding);
  }

  @Test
  public void testSetScaleDownInsideBorders() {
    mRoundedCornersDrawable.setScaleDownInsideBorders(true);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getScaleDownInsideBorders()).isTrue();
  }

  @Test
  public void testSetPaintFilterBitmap() {
    mRoundedCornersDrawable.setPaintFilterBitmap(true);
    verify(mCallback).invalidateDrawable(mRoundedCornersDrawable);
    assertThat(mRoundedCornersDrawable.getPaintFilterBitmap()).isTrue();
  }
}
