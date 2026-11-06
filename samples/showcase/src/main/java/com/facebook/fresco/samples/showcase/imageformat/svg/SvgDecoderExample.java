/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.samples.showcase.imageformat.svg;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PictureDrawable;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.facebook.imageformat.ImageFormat;
import com.facebook.imageformat.ImageFormatCheckerUtils;
import com.facebook.imagepipeline.common.ImageDecodeOptions;
import com.facebook.imagepipeline.decoder.ImageDecoder;
import com.facebook.imagepipeline.drawable.DrawableFactory;
import com.facebook.imagepipeline.image.BaseCloseableImage;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.imagepipeline.image.ImmutableQualityInfo;
import com.facebook.imagepipeline.image.QualityInfo;
import javax.annotation.Nullable;

/** SVG example that defines all classes required to decode and render SVG images. */
public class SvgDecoderExample {

  public static final ImageFormat SVG_FORMAT = new ImageFormat("SVG_FORMAT", "svg");

  // We do not include the closing ">" since there can be additional information
  private static final String HEADER_TAG = "<svg";
  private static final byte[][] POSSIBLE_HEADER_TAGS = {
    ImageFormatCheckerUtils.asciiBytes("<?xml")
  };

  // An XML prolog (<?xml ...?>, comments, DOCTYPE) can push <svg past the first few bytes,
  // so the checker must look further into the file than the length of the tag itself.
  private static final int MAX_HEADER_SIZE = 1024;

  public static class SvgFormatChecker implements ImageFormat.FormatChecker {

    public static final byte[] HEADER = ImageFormatCheckerUtils.asciiBytes(HEADER_TAG);

    @Override
    public int getHeaderSize() {
      return MAX_HEADER_SIZE;
    }

    @Nullable
    @Override
    public ImageFormat determineFormat(byte[] headerBytes, int headerSize) {
      if (headerSize < HEADER.length) {
        return null;
      }
      if (ImageFormatCheckerUtils.startsWithPattern(headerBytes, HEADER)) {
        return SVG_FORMAT;
      }
      for (byte[] possibleHeaderTag : POSSIBLE_HEADER_TAGS) {
        if (ImageFormatCheckerUtils.startsWithPattern(headerBytes, possibleHeaderTag)
            && ImageFormatCheckerUtils.indexOfPattern(headerBytes, headerSize, HEADER, HEADER.length)
                > -1) {
          return SVG_FORMAT;
        }
      }
      return null;
    }
  }

  public static class CloseableSvgImage extends BaseCloseableImage {

    private final SVG mSvg;

    private boolean mClosed = false;

    public CloseableSvgImage(SVG svg) {
      mSvg = svg;
    }

    public SVG getSvg() {
      return mSvg;
    }

    @Override
    public int getSizeInBytes() {
      return 0;
    }

    @Override
    public void close() {
      mClosed = true;
    }

    @Override
    public boolean isClosed() {
      return mClosed;
    }

    @Override
    public boolean isStateful() {
      return false;
    }

    @Override
    public int getWidth() {
      return 0;
    }

    @Override
    public int getHeight() {
      return 0;
    }

    @Override
    public QualityInfo getQualityInfo() {
      return ImmutableQualityInfo.FULL_QUALITY;
    }

    @Override
    public ImageInfo getImageInfo() {
      return this;
    }
  }

  /** Decodes a SVG_FORMAT image */
  public static class SvgDecoder implements ImageDecoder {

    @Override
    public CloseableImage decode(
        EncodedImage encodedImage,
        int length,
        QualityInfo qualityInfo,
        ImageDecodeOptions options) {
      try {
        SVG svg = SVG.getFromInputStream(encodedImage.getInputStream());
        return new CloseableSvgImage(svg);
      } catch (SVGParseException e) {
        e.printStackTrace();
      }
      return null;
    }
  }

  /** SVG drawable factory that creates {@link PictureDrawable}s for SVG images. */
  public static class SvgDrawableFactory implements DrawableFactory {

    @Override
    public boolean supportsImageType(CloseableImage image) {
      return image instanceof CloseableSvgImage;
    }

    @Nullable
    @Override
    public Drawable createDrawable(CloseableImage image) {
      return new SvgPictureDrawable(((CloseableSvgImage) image).getSvg());
    }
  }

  public static class SvgPictureDrawable extends PictureDrawable {

    private final SVG mSvg;

    public SvgPictureDrawable(SVG svg) {
      super(null);
      mSvg = svg;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
      super.onBoundsChange(bounds);
      setPicture(mSvg.renderToPicture(bounds.width(), bounds.height()));
    }
  }
}
