/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.span;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.view.View;
import androidx.annotation.VisibleForTesting;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.lifecycle.AttachDetachListener;
import com.facebook.drawee.controller.BaseControllerListener;
import com.facebook.fresco.vito.core.FrescoDrawableInterface;
import com.facebook.fresco.vito.draweesupport.ControllerListenerWrapper;
import com.facebook.fresco.vito.options.ImageOptions;
import com.facebook.fresco.vito.source.ImageSource;
import com.facebook.fresco.vito.textspan.DeferredFetchVitoSpan;
import com.facebook.fresco.vito.textspan.VitoSpan;
import com.facebook.fresco.vito.textspan.VitoSpanLoader;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.widget.text.span.BetterImageSpan;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * DraweeSpanStringBuilder that can be used to add {@link DraweeSpan}s to strings.
 *
 * <p>The containing view must also call {@link #onDetachFromView(View)} ()} from its {@link
 * View#onStartTemporaryDetach()} and {@link View#onDetachedFromWindow()} methods. Similarly, it
 * must call {@link #onAttachToView(View)} from its {@link View#onFinishTemporaryDetach()} and
 * {@link View#onAttachedToWindow()} methods.
 *
 * <p>If you attach the same DraweeSpanStringBuilder to different views, only the most recent view
 * will be updated correctly since you can only bind the same builder to 1 view at a time. Older
 * views will be automatically unbound.
 *
 * <p>{@see DraweeHolder}
 */
public class DraweeSpanStringBuilder extends SpannableStringBuilder
    implements AttachDetachListener {

  public interface DraweeSpanChangedListener {

    public void onDraweeSpanChanged(DraweeSpanStringBuilder draweeSpanStringBuilder);
  }

  public static final int UNSET_SIZE = -1;

  private final Set<VitoSpan> mVitoSpans = new HashSet<>();

  private final DrawableCallback mDrawableCallback = new DrawableCallback();

  @Nullable private View mBoundView;
  @Nullable private Drawable mBoundDrawable;
  @Nullable private DraweeSpanChangedListener mDraweeSpanChangedListener;

  public DraweeSpanStringBuilder() {
    super();
  }

  public DraweeSpanStringBuilder(CharSequence text) {
    super(text);
  }

  public DraweeSpanStringBuilder(CharSequence text, int start, int end) {
    super(text, start, end);
  }

  public void setImageVitoSpan(
      Context context,
      ImageSource imageSource,
      ImageOptions imageOptions,
      Object callerContext,
      int startIndex,
      int endIndex,
      final int drawableWidthPx,
      final int drawableHeightPx,
      boolean enableResizing,
      @BetterImageSpan.BetterImageSpanAlignment int verticalAlignment) {
    setImageVitoSpan(
        context,
        VitoSpanLoader.INSTANCE,
        imageSource,
        imageOptions,
        callerContext,
        startIndex,
        endIndex,
        drawableWidthPx,
        drawableHeightPx,
        enableResizing,
        verticalAlignment);
  }

  public void setImageVitoSpan(
      Context context,
      VitoSpanLoader vitoSpanLoader,
      ImageSource imageSource,
      ImageOptions imageOptions,
      Object callerContext,
      int startIndex,
      int endIndex,
      final int drawableWidthPx,
      final int drawableHeightPx,
      boolean enableResizing,
      @BetterImageSpan.BetterImageSpanAlignment int verticalAlignment) {
    if (endIndex >= length()) {
      // Unfortunately, some callers use this wrong. The original implementation also swallows
      // an exception if this happens (e.g. if you tap on a video that has a minutiae as well.
      // Example: Text = "ABC", insert image at position 18.
      return;
    }
    FrescoDrawableInterface drawableInterface = vitoSpanLoader.createDrawable();
    Drawable topLevelDrawable = (Drawable) drawableInterface;
    if (topLevelDrawable.getBounds().isEmpty()) {
      topLevelDrawable.setBounds(0, 0, drawableWidthPx, drawableHeightPx);
    }
    VitoSpan span =
        new VitoSpan(
            context.getResources(),
            drawableInterface,
            verticalAlignment,
            new BetterImageSpan((Drawable) drawableInterface, verticalAlignment),
            mDrawableCallback);
    mVitoSpans.add(span);
    vitoSpanShow(
        imageSource,
        imageOptions,
        callerContext,
        ControllerListenerWrapper.create(
            new VitoDrawableChangedListener(span, enableResizing, drawableHeightPx)),
        span);
    setSpan(span, startIndex, endIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  /**
   * Same as {@link #setImageVitoSpan(Context, ImageSource, ImageOptions, Object, int, int, int,
   * int, boolean, int)}, except that the image request does not start now. It starts when this
   * builder is attached, or as {@link DeferredFetchVitoSpan} says when nothing attaches it.
   */
  public void setDeferredFetchImageVitoSpan(
      Context context,
      ImageSource imageSource,
      ImageOptions imageOptions,
      Object callerContext,
      int startIndex,
      int endIndex,
      final int drawableWidthPx,
      final int drawableHeightPx,
      boolean enableResizing,
      @BetterImageSpan.BetterImageSpanAlignment int verticalAlignment) {
    if (endIndex >= length()) {
      return;
    }
    FrescoDrawableInterface drawableInterface = VitoSpanLoader.INSTANCE.createDrawable();
    Drawable topLevelDrawable = (Drawable) drawableInterface;
    if (topLevelDrawable.getBounds().isEmpty()) {
      topLevelDrawable.setBounds(0, 0, drawableWidthPx, drawableHeightPx);
    }
    DeferredFetchVitoSpan span =
        new DeferredFetchVitoSpan(
            context.getResources(),
            drawableInterface,
            verticalAlignment,
            new BetterImageSpan(topLevelDrawable, verticalAlignment),
            mDrawableCallback);
    mVitoSpans.add(span);
    VitoSpanLoader.showWhenAttachedOrDrawn(
        imageSource,
        imageOptions,
        false,
        callerContext,
        null,
        ControllerListenerWrapper.create(
            new VitoDrawableChangedListener(span, enableResizing, drawableHeightPx)),
        span);
    setSpan(span, startIndex, endIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  /**
   * Takes over the {@link DeferredFetchVitoSpan}s in this text, such as those of another builder
   * whose text was copied into this one: this builder's attach starts their requests, its detach
   * releases them, and their drawables invalidate what this builder is bound to.
   */
  public void adoptDeferredFetchVitoSpans() {
    for (DeferredFetchVitoSpan span : getSpans(0, length(), DeferredFetchVitoSpan.class)) {
      if (mVitoSpans.add(span)) {
        ((Drawable) span.getDrawableInterface()).setCallback(mDrawableCallback);
      }
    }
  }

  public void vitoSpanShow(
      ImageSource imageSource,
      ImageOptions imageOptions,
      Object callerContext,
      ControllerListenerWrapper controllerListenerWrapper,
      VitoSpan span) {
    VitoSpanLoader.show(
        imageSource, imageOptions, false, callerContext, null, controllerListenerWrapper, span);
  }

  public void setDraweeSpanChangedListener(DraweeSpanChangedListener draweeSpanChangedListener) {
    mDraweeSpanChangedListener = draweeSpanChangedListener;
  }

  public boolean hasVitoSpans() {
    return !mVitoSpans.isEmpty();
  }

  @Override
  public void onAttachToView(View view) {
    bindToView(view);
    onAttach();
  }

  @Override
  public void onDetachFromView(View view) {
    unbindFromView(view);
    onDetach();
  }

  @VisibleForTesting
  void onAttach() {
    for (VitoSpan span : mVitoSpans) {
      if (span.getImageFetchCommand() != null) {
        span.getImageFetchCommand().invoke();
      }
    }
  }

  @VisibleForTesting
  void onDetach() {
    for (VitoSpan span : mVitoSpans) {
      VitoSpanLoader.release(span);
    }
  }

  @VisibleForTesting
  public Set<VitoSpan> getVitoSpans() {
    return mVitoSpans;
  }

  protected void bindToView(View view) {
    unbindFromPreviousComponent();
    mBoundView = view;
  }

  protected void bindToDrawable(Drawable drawable) {
    unbindFromPreviousComponent();
    mBoundDrawable = drawable;
  }

  protected void unbindFromView(View view) {
    if (view != mBoundView) {
      return; // we are bound to a different view already
    }
    mBoundView = null;
  }

  protected void unbindFromDrawable(Drawable drawable) {
    if (drawable != mBoundDrawable) {
      return; // we are bound to a different view already
    }
    mBoundDrawable = null;
  }

  protected void unbindFromPreviousComponent() {
    if (mBoundView != null) {
      unbindFromView(mBoundView);
    }
    if (mBoundDrawable != null) {
      unbindFromDrawable(mBoundDrawable);
    }
  }

  private class DrawableCallback implements Drawable.Callback {

    @Override
    public void invalidateDrawable(Drawable who) {
      if (mBoundView != null) {
        // invalidateDrawable might not work correctly since we don't know the exact location
        // of the drawable and invalidateDrawable could mark the wrong rect as dirty
        mBoundView.invalidate();
      } else if (mBoundDrawable != null) {
        mBoundDrawable.invalidateSelf();
      }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
      if (mBoundView != null) {
        // 'mBoundView.scheduleDrawable(who, what, when)' wouldn't work because
        // it cannot determine the 'who' drawable with 'verifyDrawable(who)'.
        // So we're re-implementing 'scheduleDrawable' manually.
        final long delay = when - SystemClock.uptimeMillis();
        mBoundView.postDelayed(what, delay);
      } else if (mBoundDrawable != null) {
        mBoundDrawable.scheduleSelf(what, when);
      }
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
      if (mBoundView != null) {
        mBoundView.removeCallbacks(what);
      } else if (mBoundDrawable != null) {
        mBoundDrawable.unscheduleSelf(what);
      }
    }
  }

  private class VitoDrawableChangedListener extends BaseControllerListener<ImageInfo> {

    private final VitoSpan mVitoSpan;

    private final boolean mEnableResizing;

    private final int mFixedHeight;

    public VitoDrawableChangedListener(VitoSpan vitoSpan) {
      this(vitoSpan, false);
    }

    public VitoDrawableChangedListener(VitoSpan vitoSpan, boolean enableResizing) {
      this(vitoSpan, enableResizing, UNSET_SIZE);
    }

    /**
     * Create a new DrawableChangedListener If resizing is enabled, the drawable will be resized to
     * the size of the actual image once it is available. If a fixed height is given and resizing is
     * enabled, the drawable will be resized to match the aspect ratio of the original image but
     * will have the given fixed height.
     *
     * @param vitoSpan the Vito span to listen to
     * @param enableResizing if true, the drawable will be resized according to the final image size
     * @param fixedHeight use a fixed height even if resizing is enabled {@link #UNSET_SIZE}
     */
    public VitoDrawableChangedListener(VitoSpan vitoSpan, boolean enableResizing, int fixedHeight) {
      Preconditions.checkNotNull(vitoSpan);
      mVitoSpan = vitoSpan;
      mEnableResizing = enableResizing;
      mFixedHeight = fixedHeight;
    }

    @Override
    public void onFinalImageSet(
        String id, @Nullable ImageInfo imageInfo, @Nullable Animatable animatable) {
      if (mEnableResizing && imageInfo != null) {
        Drawable topLevelDrawable = (Drawable) mVitoSpan.getDrawableInterface();
        Rect topLevelDrawableBounds = topLevelDrawable.getBounds();
        if (mFixedHeight != UNSET_SIZE) {
          float imageWidth = ((float) mFixedHeight / imageInfo.getHeight()) * imageInfo.getWidth();
          int imageWidthPx = (int) imageWidth;
          if (topLevelDrawableBounds.width() != imageWidthPx
              || topLevelDrawableBounds.height() != mFixedHeight) {
            topLevelDrawable.setBounds(0, 0, imageWidthPx, mFixedHeight);

            if (mDraweeSpanChangedListener != null) {
              mDraweeSpanChangedListener.onDraweeSpanChanged(DraweeSpanStringBuilder.this);
            }
          }
        } else if (topLevelDrawableBounds.width() != imageInfo.getWidth()
            || topLevelDrawableBounds.height() != imageInfo.getHeight()) {
          topLevelDrawable.setBounds(0, 0, imageInfo.getWidth(), imageInfo.getHeight());

          if (mDraweeSpanChangedListener != null) {
            mDraweeSpanChangedListener.onDraweeSpanChanged(DraweeSpanStringBuilder.this);
          }
        }
      }
    }
  }
}
