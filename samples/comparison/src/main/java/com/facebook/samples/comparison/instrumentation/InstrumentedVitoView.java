/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.comparison.instrumentation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.widget.ImageView;
import com.facebook.fresco.vito.listener.BaseImageListener;
import com.facebook.fresco.vito.listener.ImageListener;
import com.facebook.fresco.vito.options.ImageOptions;
import com.facebook.fresco.vito.view.VitoView;
import com.facebook.imagepipeline.image.ImageInfo;
import javax.annotation.Nullable;

/** {@link ImageView} with instrumentation using Vito. */
@SuppressWarnings("this-escape")
public class InstrumentedVitoView extends ImageView implements Instrumented {

  private Instrumentation mInstrumentation;
  private ImageListener mListener;
  private ImageOptions mImageOptions;

  public InstrumentedVitoView(Context context, ImageOptions imageOptions) {
    super(context);
    mImageOptions = imageOptions;
    init();
  }

  public InstrumentedVitoView(Context context) {
    super(context);
    mImageOptions = ImageOptions.defaults();
    init();
  }

  public InstrumentedVitoView(Context context, AttributeSet attrs) {
    super(context, attrs);
    mImageOptions = ImageOptions.defaults();
    init();
  }

  public InstrumentedVitoView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    mImageOptions = ImageOptions.defaults();
    init();
  }

  private void init() {
    mInstrumentation = new Instrumentation(this);
    mListener =
        new BaseImageListener() {
          @Override
          public void onSubmit(long id, @Nullable Object callerContext) {
            mInstrumentation.onStart();
          }

          @Override
          public void onFinalImageSet(
              long id,
              int imageOrigin,
              @Nullable ImageInfo imageInfo,
              @Nullable Drawable drawable) {
            mInstrumentation.onSuccess();
          }

          @Override
          public void onFailure(long id, @Nullable Drawable error, @Nullable Throwable throwable) {
            mInstrumentation.onFailure();
          }

          @Override
          public void onRelease(long id) {
            mInstrumentation.onCancellation();
          }
        };
  }

  @Override
  public void initInstrumentation(String tag, PerfListener perfListener) {
    mInstrumentation.init(tag, perfListener);
  }

  @Override
  public void onDraw(final Canvas canvas) {
    super.onDraw(canvas);
    mInstrumentation.onDraw(canvas);
  }

  public void setImageURI(Uri uri, @Nullable Object callerContext) {
    VitoView.show(uri, mImageOptions, callerContext, mListener, this);
  }

  public void setImageURI(Uri uri) {
    setImageURI(uri, null);
  }

  public ImageListener getListener() {
    return mListener;
  }

  public ImageOptions getImageOptions() {
    return mImageOptions;
  }

  public void setImageOptions(ImageOptions imageOptions) {
    mImageOptions = imageOptions;
  }
}
