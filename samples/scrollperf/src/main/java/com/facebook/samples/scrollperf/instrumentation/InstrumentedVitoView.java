/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.instrumentation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import com.facebook.fresco.vito.listener.BaseImageListener;
import com.facebook.fresco.vito.listener.ImageListener;
import com.facebook.fresco.vito.options.ImageOptions;
import com.facebook.fresco.vito.view.VitoView;
import com.facebook.imagepipeline.image.ImageInfo;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.samples.scrollperf.conf.Config;
import com.facebook.samples.scrollperf.util.VitoUtil;

/** {@link ImageView} with instrumentation and Vito utils. */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class InstrumentedVitoView extends ImageView implements Instrumented {

  private Instrumentation mInstrumentation;
  private Config mConfig;
  // NULLSAFE_FIXME[Field Not Initialized]
  private ImageListener mListener;
  private ImageOptions.Builder mImageOptionsBuilder;

  @SuppressWarnings("this-escape")
  public InstrumentedVitoView(
      Context context, ImageOptions.Builder imageOptionsBuilder, Config config) {
    super(context);
    mConfig = config;
    mImageOptionsBuilder = imageOptionsBuilder;
    init();
  }

  private void init() {
    mInstrumentation = new Instrumentation(this);
    if (mConfig.instrumentationEnabled) {
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
            public void onFailure(
                long id, @Nullable Drawable error, @Nullable Throwable throwable) {
              mInstrumentation.onFailure();
            }

            @Override
            public void onRelease(long id) {
              mInstrumentation.onCancellation();
            }
          };
    }
    VitoUtil.setBgColor(this, mConfig);
  }

  @Override
  public void initInstrumentation(String tag, PerfListener perfListener) {
    if (mConfig.instrumentationEnabled) {
      mInstrumentation.init(tag, perfListener);
    }
  }

  @Override
  public void onDraw(final Canvas canvas) {
    super.onDraw(canvas);
    if (mConfig.instrumentationEnabled) {
      mInstrumentation.onDraw(canvas);
    }
  }

  public void setImageURI(Uri uri, Object callerContext) {
    ImageListener listener = mConfig.instrumentationEnabled ? mListener : null;
    VitoView.show(uri, mImageOptionsBuilder.build(), callerContext, listener, this);
  }

  public ImageOptions.Builder getImageOptionsBuilder() {
    return mImageOptionsBuilder;
  }

  public ImageListener getListener() {
    return mListener;
  }
}
