/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.common;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import com.facebook.common.internal.Objects;
import com.facebook.imagepipeline.decoder.ImageDecoder;
import com.facebook.imagepipeline.transformation.BitmapTransformation;
import com.facebook.infer.annotation.Nullsafe;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** Options for changing the behavior of the {@code ImageDecoder}. */
@Immutable
@Nullsafe(Nullsafe.Mode.LOCAL)
@SuppressWarnings({"rawtypes", "unchecked"})
public class ImageDecodeOptions {

  public static boolean DISALLOW_RGB565 = false;

  private static final ImageDecodeOptions DEFAULTS = ImageDecodeOptions.newBuilder().build();

  /**
   * Decoding of intermediate results for an image won't happen more often that minDecodeIntervalMs.
   */
  public final int minDecodeIntervalMs;

  /** Maximum image dimension to decode */
  public final int maxDimensionPx;

  /** Whether to decode a preview frame for animated images. */
  public final boolean decodePreviewFrame;

  /** Indicates that the last frame should be used as the preview frame instead of the first. */
  public final boolean useLastFrameForPreview;

  /** Whether to use encoded image for preview. */
  public final boolean useEncodedImageForPreview;

  /**
   * Whether to decode all the frames and store them in memory. This should only ever be used for
   * animations that are known to be small (e.g. stickers). Caching dozens of large Bitmaps in
   * memory for general GIFs or WebP's will not fit in memory.
   */
  public final boolean decodeAllFrames;

  /**
   * Force image to be rendered as a static image, even if it is an animated format.
   *
   * <p>This flag will force animated GIFs to be rendered as static images
   */
  public final boolean forceStaticImage;

  /** StaticImage and JPEG will decode with this config; */
  public final Bitmap.Config bitmapConfig;

  /** Animated Image will decode with this config; */
  public final Bitmap.Config animatedBitmapConfig;

  /** Custom image decoder override. */
  public final @Nullable ImageDecoder customImageDecoder;

  /** Bitmap transformation override */
  public final @Nullable BitmapTransformation bitmapTransformation;

  /**
   * Bitmap transformation applied only to intermediate progressive JPEG results (not the final
   * image). Useful for applying blur effects to progressive preview scans.
   */
  public final @Nullable BitmapTransformation intermediateImageBitmapTransformation;

  /**
   * Allow explicit color management, must be one of the named color space in ColorSpace.Named. This
   * flag might affect performance, if null, then SRGB color space is assumed if the SDK version >=
   * 26.
   */
  public final @Nullable ColorSpace colorSpace;

  private final boolean excludeBitmapConfigFromComparison;

  public ImageDecodeOptions(ImageDecodeOptionsBuilder b) {
    this.minDecodeIntervalMs = b.getMinDecodeIntervalMs();
    this.maxDimensionPx = b.getMaxDimensionPx();
    this.decodePreviewFrame = b.getDecodePreviewFrame();
    this.useLastFrameForPreview = b.getUseLastFrameForPreview();
    this.useEncodedImageForPreview = b.getUseEncodedImageForPreview();
    this.decodeAllFrames = b.getDecodeAllFrames();
    this.forceStaticImage = b.getForceStaticImage();
    this.bitmapConfig = b.getBitmapConfig();
    this.animatedBitmapConfig = b.getAnimatedBitmapConfig();
    this.customImageDecoder = b.getCustomImageDecoder();
    this.bitmapTransformation = b.getBitmapTransformation();
    this.intermediateImageBitmapTransformation = b.getIntermediateImageBitmapTransformation();
    this.colorSpace = b.getColorSpace();
    this.excludeBitmapConfigFromComparison = b.getExcludeBitmapConfigFromComparison();
  }

  /**
   * Gets the default options.
   *
   * @return the default options
   */
  public static ImageDecodeOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static ImageDecodeOptionsBuilder newBuilder() {
    return new ImageDecodeOptionsBuilder();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) return true;
    if (other == null || getClass() != other.getClass()) return false;

    ImageDecodeOptions that = (ImageDecodeOptions) other;

    if (minDecodeIntervalMs != that.minDecodeIntervalMs) return false;
    if (maxDimensionPx != that.maxDimensionPx) return false;
    if (decodePreviewFrame != that.decodePreviewFrame) return false;
    if (useLastFrameForPreview != that.useLastFrameForPreview) return false;
    if (useEncodedImageForPreview != that.useEncodedImageForPreview) return false;
    if (decodeAllFrames != that.decodeAllFrames) return false;
    if (forceStaticImage != that.forceStaticImage) return false;
    if (!excludeBitmapConfigFromComparison && bitmapConfig != that.bitmapConfig) return false;
    if (!excludeBitmapConfigFromComparison && animatedBitmapConfig != that.animatedBitmapConfig)
      return false;
    if (customImageDecoder != that.customImageDecoder) return false;
    if (bitmapTransformation != that.bitmapTransformation) return false;
    if (intermediateImageBitmapTransformation != that.intermediateImageBitmapTransformation)
      return false;
    if (colorSpace != that.colorSpace) return false;
    return true;
  }

  @Override
  public int hashCode() {
    int result = minDecodeIntervalMs;
    result = 31 * result + maxDimensionPx;
    result = 31 * result + (decodePreviewFrame ? 1 : 0);
    result = 31 * result + (useLastFrameForPreview ? 1 : 0);
    result = 31 * result + (useEncodedImageForPreview ? 1 : 0);
    result = 31 * result + (decodeAllFrames ? 1 : 0);
    result = 31 * result + (forceStaticImage ? 1 : 0);
    if (!excludeBitmapConfigFromComparison) result = 31 * result + bitmapConfig.ordinal();
    if (!excludeBitmapConfigFromComparison)
      result = 31 * result + (animatedBitmapConfig != null ? animatedBitmapConfig.ordinal() : 0);
    result = 31 * result + (customImageDecoder != null ? customImageDecoder.hashCode() : 0);
    result = 31 * result + (bitmapTransformation != null ? bitmapTransformation.hashCode() : 0);
    result =
        31 * result
            + (intermediateImageBitmapTransformation != null
                ? intermediateImageBitmapTransformation.hashCode()
                : 0);
    result = 31 * result + (colorSpace != null ? colorSpace.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "ImageDecodeOptions{" + toStringHelper().toString() + "}";
  }

  protected Objects.ToStringHelper toStringHelper() {
    return Objects.toStringHelper(this)
        .add("minDecodeIntervalMs", minDecodeIntervalMs)
        .add("maxDimensionPx", maxDimensionPx)
        .add("decodePreviewFrame", decodePreviewFrame)
        .add("useLastFrameForPreview", useLastFrameForPreview)
        .add("useEncodedImageForPreview", useEncodedImageForPreview)
        .add("decodeAllFrames", decodeAllFrames)
        .add("forceStaticImage", forceStaticImage)
        .add("bitmapConfigName", bitmapConfig.name())
        .add("animatedBitmapConfigName", animatedBitmapConfig.name())
        .add("customImageDecoder", customImageDecoder)
        .add("bitmapTransformation", bitmapTransformation)
        .add("intermediateImageBitmapTransformation", intermediateImageBitmapTransformation)
        .add("colorSpace", colorSpace);
  }
}
