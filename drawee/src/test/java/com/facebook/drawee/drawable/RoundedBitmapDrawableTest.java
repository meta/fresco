/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.drawable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class RoundedBitmapDrawableTest {
  private Resources mResources;
  private Bitmap mBitmap;
  private DisplayMetrics mDisplayMetrics;

  RoundedBitmapDrawable mRoundedBitmapDrawable;
  RoundedBitmapDrawable mRoundedBitmapDrawableWithNullBitmap;

  private final Drawable.Callback mCallback = mock(Drawable.Callback.class);

  @Before
  public void setUp() {
    mResources = mock(Resources.class);
    mBitmap = mock(Bitmap.class);
    mDisplayMetrics = mock(DisplayMetrics.class);
    when(mResources.getDisplayMetrics()).thenReturn(mDisplayMetrics);
    mRoundedBitmapDrawable = new RoundedBitmapDrawable(mResources, mBitmap);
    mRoundedBitmapDrawable.setCallback(mCallback);

    mRoundedBitmapDrawableWithNullBitmap = new RoundedBitmapDrawable(mResources, null);
    mRoundedBitmapDrawable.setCallback(mCallback);
  }

  @Test
  public void testSetCircle() {
    mRoundedBitmapDrawable.setCircle(true);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.isCircle()).isTrue();
  }

  @Test
  public void testSetRadii() {
    mRoundedBitmapDrawable.setRadii(new float[] {1, 2, 3, 4, 5, 6, 7, 8});
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getRadii())
        .containsExactly(new float[] {1, 2, 3, 4, 5, 6, 7, 8});
  }

  @Test
  public void testSetRadius() {
    mRoundedBitmapDrawable.setRadius(9);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getRadii())
        .containsExactly(new float[] {9, 9, 9, 9, 9, 9, 9, 9});
  }

  @Test
  public void testSetBorder() {
    int color = 0x12345678;
    float width = 5;
    mRoundedBitmapDrawable.setBorder(color, width);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getBorderColor()).isEqualTo(color);
    assertThat(mRoundedBitmapDrawable.getBorderWidth()).isEqualTo(width);
  }

  @Test
  public void testSetPadding() {
    float padding = 10;
    mRoundedBitmapDrawable.setPadding(padding);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getPadding()).isEqualTo(padding);
  }

  @Test
  public void testSetScaleDownInsideBorders() {
    mRoundedBitmapDrawable.setScaleDownInsideBorders(true);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getScaleDownInsideBorders()).isTrue();
  }

  @Test
  public void testSetPaintFilterBitmap() {
    mRoundedBitmapDrawable.setPaintFilterBitmap(true);
    verify(mCallback).invalidateDrawable(mRoundedBitmapDrawable);
    assertThat(mRoundedBitmapDrawable.getPaintFilterBitmap()).isTrue();
  }

  @Test
  public void testShouldRoundDefault() {
    assertThat(mRoundedBitmapDrawable.shouldRound()).isFalse();
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
  }

  @Test
  public void testShouldRoundRadius() {
    mRoundedBitmapDrawable.setRadius(5);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isTrue();
    mRoundedBitmapDrawable.setRadius(0);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isFalse();

    mRoundedBitmapDrawableWithNullBitmap.setRadius(5);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
    mRoundedBitmapDrawableWithNullBitmap.setRadius(0);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
  }

  @Test
  public void testShouldRoundRadii() {
    mRoundedBitmapDrawable.setRadii(new float[] {0, 0, 0, 0, 0, 0, 0, 1});
    assertThat(mRoundedBitmapDrawable.shouldRound()).isTrue();
    mRoundedBitmapDrawable.setRadii(new float[] {0, 0, 0, 0, 0, 0, 0, 0});
    assertThat(mRoundedBitmapDrawable.shouldRound()).isFalse();

    mRoundedBitmapDrawableWithNullBitmap.setRadii(new float[] {0, 0, 0, 0, 0, 0, 0, 1});
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
    mRoundedBitmapDrawableWithNullBitmap.setRadii(new float[] {0, 0, 0, 0, 0, 0, 0, 0});
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
  }

  @Test
  public void testShouldRoundCircle() {
    mRoundedBitmapDrawable.setCircle(true);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isTrue();
    mRoundedBitmapDrawable.setCircle(false);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isFalse();

    mRoundedBitmapDrawableWithNullBitmap.setCircle(true);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
    mRoundedBitmapDrawableWithNullBitmap.setCircle(false);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
  }

  @Test
  public void testShouldRoundBorder() {
    mRoundedBitmapDrawable.setBorder(0xFFFFFFFF, 1);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isTrue();
    mRoundedBitmapDrawable.setBorder(0x00000000, 0);
    assertThat(mRoundedBitmapDrawable.shouldRound()).isFalse();

    mRoundedBitmapDrawableWithNullBitmap.setBorder(0xFFFFFFFF, 1);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
    mRoundedBitmapDrawableWithNullBitmap.setBorder(0x00000000, 0);
    assertThat(mRoundedBitmapDrawableWithNullBitmap.shouldRound()).isFalse();
  }

  @Test
  public void testPreservePaintOnDrawableCopy() {
    ColorFilter colorFilter = mock(ColorFilter.class);
    Paint originalPaint = mock(Paint.class);
    BitmapDrawable originalVersion = mock(BitmapDrawable.class);

    originalPaint.setColorFilter(colorFilter);
    when(originalVersion.getPaint()).thenReturn(originalPaint);

    RoundedBitmapDrawable roundedVersion =
        RoundedBitmapDrawable.fromBitmapDrawable(mResources, originalVersion);

    assertThat(roundedVersion.getPaint().getColorFilter())
        .isEqualTo(originalVersion.getPaint().getColorFilter());
  }
}
