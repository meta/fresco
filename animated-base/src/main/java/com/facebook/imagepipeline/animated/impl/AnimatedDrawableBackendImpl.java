/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.animated.impl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.animated.base.AnimatedDrawableBackend;
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo;
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo.BlendOperation;
import com.facebook.imagepipeline.animated.base.AnimatedDrawableFrameInfo.DisposalMethod;
import com.facebook.imagepipeline.animated.base.AnimatedImage;
import com.facebook.imagepipeline.animated.base.AnimatedImageFrame;
import com.facebook.imagepipeline.animated.base.AnimatedImageResult;
import com.facebook.imagepipeline.animated.util.AnimatedDrawableUtil;
import com.facebook.infer.annotation.Nullsafe;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** An {@link AnimatedDrawableBackend} that renders {@link AnimatedImage}. */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class AnimatedDrawableBackendImpl implements AnimatedDrawableBackend {

  private final AnimatedDrawableUtil mAnimatedDrawableUtil;

  private final AnimatedImageResult mAnimatedImageResult;
  private final AnimatedImage mAnimatedImage;
  private final Rect mRenderedBounds;
  private final int[] mFrameDurationsMs;
  private final int[] mFrameTimestampsMs;
  private final int mDurationMs;
  private final AnimatedDrawableFrameInfo[] mFrameInfos;
  private final Rect mRenderSrcRect = new Rect();
  private final Rect mRenderDstRect = new Rect();
  private final boolean mDownscaleFrameToDrawableDimensions;
  private final Paint mTransparentPaint;

  @GuardedBy("this")
  private @Nullable Bitmap mTempBitmap;

  public AnimatedDrawableBackendImpl(
      AnimatedDrawableUtil animatedDrawableUtil,
      AnimatedImageResult animatedImageResult,
      @Nullable Rect bounds,
      boolean downscaleFrameToDrawableDimensions) {
    mAnimatedDrawableUtil = animatedDrawableUtil;
    mAnimatedImageResult = animatedImageResult;
    mAnimatedImage = animatedImageResult.getImage();
    mFrameDurationsMs = mAnimatedImage.getFrameDurations();
    mAnimatedDrawableUtil.fixFrameDurations(mFrameDurationsMs);
    mDurationMs = mAnimatedDrawableUtil.getTotalDurationFromFrameDurations(mFrameDurationsMs);
    mFrameTimestampsMs = mAnimatedDrawableUtil.getFrameTimeStampsFromDurations(mFrameDurationsMs);
    mRenderedBounds = getBoundsToUse(mAnimatedImage, bounds);
    mDownscaleFrameToDrawableDimensions = downscaleFrameToDrawableDimensions;
    mFrameInfos = new AnimatedDrawableFrameInfo[mAnimatedImage.getFrameCount()];
    for (int i = 0; i < mAnimatedImage.getFrameCount(); i++) {
      mFrameInfos[i] = mAnimatedImage.getFrameInfo(i);
    }
    mTransparentPaint = new Paint();
    mTransparentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
  }

  private static Rect getBoundsToUse(AnimatedImage image, @Nullable Rect targetBounds) {
    if (targetBounds == null) {
      return new Rect(0, 0, image.getWidth(), image.getHeight());
    }
    return new Rect(
        0,
        0,
        Math.min(targetBounds.width(), image.getWidth()),
        Math.min(targetBounds.height(), image.getHeight()));
  }

  @Override
  public AnimatedImageResult getAnimatedImageResult() {
    return mAnimatedImageResult;
  }

  @Override
  public int getDurationMs() {
    return mDurationMs;
  }

  @Override
  public int getFrameCount() {
    return mAnimatedImage.getFrameCount();
  }

  @Override
  public int getLoopCount() {
    return mAnimatedImage.getLoopCount();
  }

  @Override
  public int getWidth() {
    return mAnimatedImage.getWidth();
  }

  @Override
  public int getHeight() {
    return mAnimatedImage.getHeight();
  }

  @Override
  public int getRenderedWidth() {
    return mRenderedBounds.width();
  }

  @Override
  public int getRenderedHeight() {
    return mRenderedBounds.height();
  }

  @Override
  public AnimatedDrawableFrameInfo getFrameInfo(int frameNumber) {
    return mFrameInfos[frameNumber];
  }

  @Override
  public int getFrameForTimestampMs(int timestampMs) {
    return mAnimatedDrawableUtil.getFrameForTimestampMs(mFrameTimestampsMs, timestampMs);
  }

  @Override
  public int getTimestampMsForFrame(int frameNumber) {
    Preconditions.checkElementIndex(frameNumber, mFrameTimestampsMs.length);
    return mFrameTimestampsMs[frameNumber];
  }

  @Override
  public int getDurationMsForFrame(int frameNumber) {
    return mFrameDurationsMs[frameNumber];
  }

  @Override
  public int getFrameForPreview() {
    return mAnimatedImageResult.getFrameForPreview();
  }

  @Override
  public AnimatedDrawableBackend forNewBounds(@Nullable Rect bounds) {
    Rect boundsToUse = getBoundsToUse(mAnimatedImage, bounds);
    if (boundsToUse.equals(mRenderedBounds)) {
      // Actual bounds aren't changed.
      return this;
    }
    return new AnimatedDrawableBackendImpl(
        mAnimatedDrawableUtil, mAnimatedImageResult, bounds, mDownscaleFrameToDrawableDimensions);
  }

  @Override
  public synchronized int getMemoryUsage() {
    int bytes = 0;
    if (mTempBitmap != null) {
      bytes += mAnimatedDrawableUtil.getSizeOfBitmap(mTempBitmap);
    }
    bytes += mAnimatedImage.getSizeInBytes();
    return bytes;
  }

  @Override
  public @Nullable CloseableReference<Bitmap> getPreDecodedFrame(int frameNumber) {
    return mAnimatedImageResult.getDecodedFrame(frameNumber);
  }

  @Override
  public boolean hasPreDecodedFrame(int index) {
    return mAnimatedImageResult.hasDecodedFrame(index);
  }

  @Override
  public void renderFrame(int frameNumber, Canvas canvas) {
    AnimatedImageFrame frame = mAnimatedImage.getFrame(frameNumber);
    try {
      if (frame.getWidth() <= 0 || frame.getHeight() <= 0) {
        return; // Frame not visible -> skipping
      }

      if (mAnimatedImage.doesRenderSupportScaling()) {
        renderImageSupportsScaling(canvas, frame);
      } else {
        renderImageDoesNotSupportScaling(canvas, frame);
      }
    } finally {
      frame.dispose();
    }
  }

  @Override
  public void renderDeltas(int frameNumber, Canvas canvas) {
    AnimatedImageFrame frame = mAnimatedImage.getFrame(frameNumber);
    AnimatedDrawableFrameInfo frameInfo = mAnimatedImage.getFrameInfo(frameNumber);
    AnimatedDrawableFrameInfo previousFrameInfo =
        frameNumber == 0 ? null : mAnimatedImage.getFrameInfo(frameNumber - 1);
    try {
      if (frame.getWidth() <= 0 || frame.getHeight() <= 0) {
        return; // Frame not visible -> skipping
      }

      if (mAnimatedImage.doesRenderSupportScaling()) {
        renderScalingFrames(canvas, frame, frameInfo, previousFrameInfo);
      } else {
        renderNonScalingFrames(canvas, frame, frameInfo, previousFrameInfo);
      }

    } finally {
      frame.dispose();
    }
  }

  private synchronized Bitmap prepareTempBitmapForThisSize(int width, int height) {
    // Different gif frames can be different size,
    // So we need to ensure we can fit next frame to temporary bitmap
    if (mTempBitmap != null
        && (mTempBitmap.getWidth() < width || mTempBitmap.getHeight() < height)) {
      clearTempBitmap();
    }
    if (mTempBitmap == null) {
      mTempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    mTempBitmap.eraseColor(Color.TRANSPARENT);
    return mTempBitmap;
  }

  private void renderImageSupportsScaling(Canvas canvas, AnimatedImageFrame frame) {
    double xScale = (double) mRenderedBounds.width() / (double) mAnimatedImage.getWidth();
    double yScale = (double) mRenderedBounds.height() / (double) mAnimatedImage.getHeight();

    int frameWidth = (int) Math.round(frame.getWidth() * xScale);
    int frameHeight = (int) Math.round(frame.getHeight() * yScale);
    int xOffset = (int) (frame.getXOffset() * xScale);
    int yOffset = (int) (frame.getYOffset() * yScale);

    synchronized (this) {
      int renderedWidth = mRenderedBounds.width();
      int renderedHeight = mRenderedBounds.height();
      // Update the temp bitmap to be >= rendered dimensions
      prepareTempBitmapForThisSize(renderedWidth, renderedHeight);
      if (mTempBitmap != null) {
        frame.renderFrame(frameWidth, frameHeight, mTempBitmap);
      }
      // Temporary bitmap can be bigger than frame, so we should draw only rendered area of bitmap
      mRenderSrcRect.set(0, 0, renderedWidth, renderedHeight);
      mRenderDstRect.set(xOffset, yOffset, xOffset + renderedWidth, yOffset + renderedHeight);

      if (mTempBitmap != null) {
        canvas.drawBitmap(mTempBitmap, mRenderSrcRect, mRenderDstRect, null);
      }
    }
  }

  private void renderScalingFrames(
      Canvas canvas,
      AnimatedImageFrame frame,
      AnimatedDrawableFrameInfo frameInfo,
      @Nullable AnimatedDrawableFrameInfo previousFrameInfo) {
    int assetWidth = mAnimatedImage.getWidth();
    int assetHeight = mAnimatedImage.getHeight();

    // Find the best scale asset size. Maximum scaleSize would be the assetSize.
    float scaledWidth = assetWidth;
    float scaledHeight = assetHeight;

    // Apply the scale to the frame
    float xScale = 1f;
    float yScale = 1f;

    int frameWidth = frame.getWidth();
    int frameHeight = frame.getHeight();
    int xOffset = frame.getXOffset();
    int yOffset = frame.getYOffset();

    // Check if we need to down scale the asset to the canvas size
    if (scaledWidth > canvas.getWidth() || scaledHeight > canvas.getHeight()) {
      // Canvas could have wrong sizes as 314573336x200. Then we limit the frame sizes
      int maxCanvasWidth = Math.min(canvas.getWidth(), assetWidth);
      int maxCanvasHeight = Math.min(canvas.getHeight(), assetHeight);

      float assetRatio = assetWidth / (float) assetHeight;
      if (maxCanvasWidth > maxCanvasHeight) {
        scaledWidth = maxCanvasWidth;
        scaledHeight = maxCanvasWidth / assetRatio;
      } else {
        scaledWidth = maxCanvasHeight * assetRatio;
        scaledHeight = maxCanvasHeight;
      }

      xScale = scaledWidth / (float) assetWidth;
      yScale = scaledHeight / (float) assetHeight;

      frameWidth = (int) Math.ceil(frame.getWidth() * xScale);
      frameHeight = (int) Math.ceil(frame.getHeight() * yScale);
      xOffset = (int) Math.ceil(frame.getXOffset() * xScale);
      yOffset = (int) Math.ceil(frame.getYOffset() * yScale);
    }

    Rect renderSrcRect = new Rect(0, 0, frameWidth, frameHeight);
    Rect renderDstRect = new Rect(xOffset, yOffset, xOffset + frameWidth, yOffset + frameHeight);

    // Clean previous frame surface if that frame was disposable
    if (previousFrameInfo != null) {
      maybeDisposeBackground(canvas, xScale, yScale, previousFrameInfo);
    }

    // If current frame is no_blend, then we have to clean their surface before rendering
    if (frameInfo.blendOperation == BlendOperation.NO_BLEND) {
      canvas.drawRect(renderDstRect, mTransparentPaint);
    }

    synchronized (this) {
      // Impress the frame in the bitmap
      Bitmap frameBitmap = prepareTempBitmapForThisSize(frameWidth, frameHeight);
      frame.renderFrame(frameWidth, frameHeight, frameBitmap);
      canvas.drawBitmap(frameBitmap, renderSrcRect, renderDstRect, null);
    }
  }

  private void maybeDisposeBackground(
      Canvas canvas, float xScale, float yScale, AnimatedDrawableFrameInfo previousFrameInfo) {
    if (previousFrameInfo.disposalMethod == DisposalMethod.DISPOSE_TO_BACKGROUND) {
      boolean roundUp = mAnimatedImage.doesRenderSupportScaling();
      int prevFrameWidth = scaleFrameCoordinate(previousFrameInfo.width, xScale, roundUp);
      int prevFrameHeight = scaleFrameCoordinate(previousFrameInfo.height, yScale, roundUp);
      int prevXOffset = scaleFrameCoordinate(previousFrameInfo.xOffset, xScale, roundUp);
      int prevYOffset = scaleFrameCoordinate(previousFrameInfo.yOffset, yScale, roundUp);
      Rect prevFrameSurface =
          new Rect(
              prevXOffset,
              prevYOffset,
              prevXOffset + prevFrameWidth,
              prevYOffset + prevFrameHeight);
      canvas.drawRect(prevFrameSurface, mTransparentPaint);
    }
  }

  private static int scaleFrameCoordinate(int value, float scale, boolean roundUp) {
    return roundUp ? (int) Math.ceil(value * scale) : (int) (value * scale);
  }

  private void renderImageDoesNotSupportScaling(Canvas canvas, AnimatedImageFrame frame) {
    int frameWidth, frameHeight, xOffset, yOffset;
    if (mDownscaleFrameToDrawableDimensions) {
      final int fittedWidth = Math.min(frame.getWidth(), canvas.getWidth());
      final int fittedHeight = Math.min(frame.getHeight(), canvas.getHeight());

      final float scaleX = (float) frame.getWidth() / (float) fittedWidth;
      final float scaleY = (float) frame.getHeight() / (float) fittedHeight;
      final float scale = Math.max(scaleX, scaleY);

      frameWidth = (int) (frame.getWidth() / scale);
      frameHeight = (int) (frame.getHeight() / scale);
      xOffset = (int) (frame.getXOffset() / scale);
      yOffset = (int) (frame.getYOffset() / scale);
    } else {
      frameWidth = frame.getWidth();
      frameHeight = frame.getHeight();
      xOffset = frame.getXOffset();
      yOffset = frame.getYOffset();
    }

    synchronized (this) {
      mTempBitmap = prepareTempBitmapForThisSize(frameWidth, frameHeight);
      frame.renderFrame(frameWidth, frameHeight, mTempBitmap);

      canvas.save();
      canvas.translate(xOffset, yOffset);
      canvas.drawBitmap(mTempBitmap, 0, 0, null);
      canvas.restore();
    }
  }

  private void renderNonScalingFrames(
      Canvas canvas,
      AnimatedImageFrame frame,
      AnimatedDrawableFrameInfo frameInfo,
      @Nullable AnimatedDrawableFrameInfo previousFrameInfo) {
    if (mRenderedBounds == null || mRenderedBounds.width() <= 0 || mRenderedBounds.height() <= 0) {
      return;
    }

    float scale = (float) canvas.getWidth() / mRenderedBounds.width();

    // Clean previous frame surface if that frame was disposable
    if (previousFrameInfo != null) {
      maybeDisposeBackground(canvas, scale, scale, previousFrameInfo);
    }

    // Prepare the new frame
    int frameWidth = frame.getWidth();
    int frameHeight = frame.getHeight();
    Rect src = new Rect(0, 0, frameWidth, frameHeight);

    int resizedWidth = (int) (frameWidth * scale);
    int resizedHeight = (int) (frameHeight * scale);

    int xOffset = (int) (frame.getXOffset() * scale);
    int yOffset = (int) (frame.getYOffset() * scale);

    // Clear the canvas if this frame doesnt blend
    Rect renderDstRect =
        new Rect(xOffset, yOffset, xOffset + resizedWidth, yOffset + resizedHeight);
    if (frameInfo.blendOperation == BlendOperation.NO_BLEND) {
      canvas.drawRect(renderDstRect, mTransparentPaint);
    }
    synchronized (this) {
      // Draw canvas frame
      Bitmap bitmap = prepareTempBitmapForThisSize(frameWidth, frameHeight);
      frame.renderFrame(frameWidth, frameHeight, bitmap);
      canvas.drawBitmap(bitmap, src, renderDstRect, null);
    }
  }

  @Override
  public synchronized void dropCaches() {
    clearTempBitmap();
  }

  private synchronized void clearTempBitmap() {
    if (mTempBitmap != null) {
      mTempBitmap.recycle();
      mTempBitmap = null;
    }
  }
}
