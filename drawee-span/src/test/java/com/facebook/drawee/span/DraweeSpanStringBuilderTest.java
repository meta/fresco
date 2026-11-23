/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import com.facebook.drawee.view.DraweeHolder;
import com.facebook.fresco.vito.core.FrescoController2;
import com.facebook.fresco.vito.core.FrescoVitoConfig;
import com.facebook.fresco.vito.core.FrescoVitoPrefetcher;
import com.facebook.fresco.vito.core.VitoImagePipeline;
import com.facebook.fresco.vito.core.VitoImageRequest;
import com.facebook.fresco.vito.core.impl.KFrescoVitoDrawable;
import com.facebook.fresco.vito.options.ImageOptions;
import com.facebook.fresco.vito.provider.components.FrescoVitoComponents;
import com.facebook.fresco.vito.provider.setup.FrescoVitoSetup;
import com.facebook.fresco.vito.source.ImageSource;
import com.facebook.fresco.vito.textspan.DeferredFetchVitoSpan;
import com.facebook.fresco.vito.textspan.VitoSpan;
import com.facebook.fresco.vito.textspan.VitoSpanLoader;
import com.facebook.widget.text.span.BetterImageSpan;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

/** Tests {@link DraweeSpanStringBuilder} */
@RunWith(RobolectricTestRunner.class)
public class DraweeSpanStringBuilderTest {

  private static final String TEXT = "ABCDEFG";
  private static final int DRAWABLE_WIDTH = 10;
  private static final int DRAWABLE_HEIGHT = 32;

  @Mock public DraweeHolder mDraweeHolder;
  @Mock public ImageSource mImageSource;
  @Mock public ImageOptions mImageOptions;

  @Mock(answer = Answers.RETURNS_MOCKS)
  public VitoSpanLoader mVitoSpanLoader;

  @Mock(answer = Answers.RETURNS_MOCKS)
  public Context mContext;

  @Mock public Drawable mTopLevelDrawable;
  @Mock public KFrescoVitoDrawable mDrawableInterface;
  @Mock public Rect mDrawableBounds;
  @Mock public View mView;
  @Mock public FrescoController2 mController;
  @Mock public VitoImagePipeline mImagePipeline;
  @Mock public VitoImageRequest mImageRequest;
  @Mock public KFrescoVitoDrawable mDeferredDrawable;

  private DraweeSpanStringBuilder mDraweeSpanStringBuilder;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(mVitoSpanLoader.createDrawable()).thenReturn(mDrawableInterface);
    //    when((Drawable) mDrawableInterface).thenReturn(mTopLevelDrawable);
    when(mDrawableInterface.getBounds()).thenReturn(mDrawableBounds);
    when(mDraweeHolder.getTopLevelDrawable()).thenReturn(mTopLevelDrawable);
    when(mTopLevelDrawable.getBounds()).thenReturn(mDrawableBounds);
    when(mDrawableBounds.width()).thenReturn(0);
    when(mDrawableBounds.height()).thenReturn(0);
    mDraweeSpanStringBuilder = spy(new DraweeSpanStringBuilder(TEXT));
    doNothing().when(mDraweeSpanStringBuilder).vitoSpanShow(any(), any(), any(), any(), any());

    when(mController.<KFrescoVitoDrawable>createDrawable(any())).thenReturn(mDeferredDrawable);
    when(mDeferredDrawable.getBounds()).thenReturn(mDrawableBounds);
    when(mImagePipeline.createImageRequest(
            any(), any(), any(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(mImageRequest);
    FrescoVitoComponents.resetImplementation();
    FrescoVitoComponents.setImplementation(
        new FrescoVitoSetup() {
          @Override
          public FrescoController2 getController() {
            return mController;
          }

          @Override
          public FrescoVitoPrefetcher getPrefetcher() {
            return null;
          }

          @Override
          public VitoImagePipeline getImagePipeline() {
            return mImagePipeline;
          }

          @Override
          public FrescoVitoConfig getConfig() {
            return null;
          }
        });
  }

  @After
  public void tearDown() {
    FrescoVitoComponents.resetImplementation();
  }

  @Test
  public void testTextCorrect() {
    assertThat(mDraweeSpanStringBuilder.toString()).isEqualTo(TEXT);
  }

  @Test
  public void testNoDraweeSpan() {
    assertThat(mDraweeSpanStringBuilder.hasVitoSpans()).isFalse();
  }

  @Test
  public void testDraweeSpanAdded() {
    addDraweeSpan(
        mDraweeSpanStringBuilder, mContext, mVitoSpanLoader, mImageSource, mImageOptions, 3, 1);

    assertThat(mDraweeSpanStringBuilder.toString()).isEqualTo(TEXT);
    assertThat(mDraweeSpanStringBuilder.hasVitoSpans()).isTrue();
  }

  @Test
  public void testDraweeSpanInSpannable() {
    addDraweeSpan(
        mDraweeSpanStringBuilder, mContext, mVitoSpanLoader, mImageSource, mImageOptions, 3, 1);
    VitoSpan[] draweeSpans =
        mDraweeSpanStringBuilder.getSpans(0, mDraweeSpanStringBuilder.length(), VitoSpan.class);

    assertThat(draweeSpans).hasSize(1);
    assertThat(draweeSpans[0].getDrawableInterface()).isEqualTo(mDrawableInterface);
  }

  @Test
  public void testDeferredFetchSpanAddedWithoutFetching() {
    DraweeSpanStringBuilder builder = new DraweeSpanStringBuilder(TEXT);

    addDeferredFetchSpan(builder);

    assertThat(builder.toString()).isEqualTo(TEXT);
    assertThat(builder.hasVitoSpans()).isTrue();
    assertThat(builder.getSpans(0, builder.length(), VitoSpan.class))
        .hasSize(1)
        .hasOnlyElementsOfType(DeferredFetchVitoSpan.class);
    verifyDeferredFetches(0);
  }

  @Test
  public void testDeferredFetchSpanFetchesOnAttachAndReleasesOnDetach() {
    DraweeSpanStringBuilder builder = new DraweeSpanStringBuilder(TEXT);
    addDeferredFetchSpan(builder);

    builder.onAttachToView(mView);
    verifyDeferredFetches(1);
    verify(mController, never()).releaseImmediately(any());

    builder.onDetachFromView(mView);
    verify(mController, times(1)).releaseImmediately(mDeferredDrawable);
  }

  @Test
  public void testDeferredFetchSpanFetchesAtItsFirstDrawOnlyWhenNothingAttachedIt() {
    DraweeSpanStringBuilder builder = new DraweeSpanStringBuilder(TEXT);
    addDeferredFetchSpan(builder);
    VitoSpan span = builder.getSpans(0, builder.length(), VitoSpan.class)[0];

    draw(span);
    draw(span);

    verifyDeferredFetches(1);
  }

  @Test
  public void testCopyAdoptsDeferredFetchSpansOfTheBuilderItCopies() {
    DraweeSpanStringBuilder source = new DraweeSpanStringBuilder(TEXT);
    addDeferredFetchSpan(source);
    DraweeSpanStringBuilder copy = new DraweeSpanStringBuilder(source);

    copy.adoptDeferredFetchVitoSpans();
    copy.onAttachToView(mView);
    copy.onDetachFromView(mView);

    assertThat(copy.getVitoSpans()).hasSize(1).hasOnlyElementsOfType(DeferredFetchVitoSpan.class);
    verifyDeferredFetches(1);
    verify(mController, times(1)).releaseImmediately(mDeferredDrawable);
  }

  @Test
  public void testImageArrivingAfterTheFirstDrawInvalidatesTheViewTheCopyIsBoundTo() {
    DraweeSpanStringBuilder source = new DraweeSpanStringBuilder(TEXT);
    addDeferredFetchSpan(source);
    DraweeSpanStringBuilder copy = new DraweeSpanStringBuilder(source);
    copy.adoptDeferredFetchVitoSpans();
    copy.onAttachToView(mView);
    draw(copy.getSpans(0, copy.length(), VitoSpan.class)[0]);
    verify(mView, never()).invalidate();

    // The drawable invalidates itself when the image arrives.
    ArgumentCaptor<Drawable.Callback> callbacks = ArgumentCaptor.forClass(Drawable.Callback.class);
    verify(mDeferredDrawable, times(2)).setCallback(callbacks.capture());
    callbacks.getValue().invalidateDrawable(mDeferredDrawable);

    verify(mView).invalidate();
  }

  @Test
  public void testAdoptionLeavesSpansThatFetchedWhenAddedToTheirBuilder() {
    addDraweeSpan(
        mDraweeSpanStringBuilder, mContext, mVitoSpanLoader, mImageSource, mImageOptions, 3, 1);
    DraweeSpanStringBuilder copy = new DraweeSpanStringBuilder(mDraweeSpanStringBuilder);

    copy.adoptDeferredFetchVitoSpans();

    assertThat(copy.hasVitoSpans()).isFalse();
  }

  private void addDeferredFetchSpan(DraweeSpanStringBuilder builder) {
    builder.setDeferredFetchImageVitoSpan(
        mContext,
        mImageSource,
        mImageOptions,
        "this is anything",
        3, /* startIndex */
        4, /* endIndex */
        DRAWABLE_WIDTH, /* drawableWidthPx */
        DRAWABLE_HEIGHT, /* drawableHeightPx */
        false, /* enableResizing */
        BetterImageSpan.ALIGN_CENTER); /* verticalAlignment */
  }

  private void verifyDeferredFetches(int count) {
    verify(mController, times(count))
        .fetch(
            eq(mDeferredDrawable),
            eq(mImageRequest),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  private static void draw(VitoSpan span) {
    span.draw(new Canvas(), "", 0, 1, 0f, 0, 10, 10, new Paint());
  }

  private static void addDraweeSpan(
      DraweeSpanStringBuilder draweeSpanStringBuilder,
      Context context,
      VitoSpanLoader vitoSpanLoader,
      ImageSource imageSource,
      ImageOptions imageOptions,
      int index,
      int spanLength) {
    draweeSpanStringBuilder.setImageVitoSpan(
        context,
        vitoSpanLoader,
        imageSource,
        imageOptions,
        "this is anything",
        index, /* startIndex */
        index + spanLength, /* endIndex */
        DRAWABLE_WIDTH, /* drawableWidthPx */
        DRAWABLE_HEIGHT, /* drawableHeightPx */
        false, /* enableResizing */
        BetterImageSpan.ALIGN_CENTER); /* verticalAlignment */
  }
}
