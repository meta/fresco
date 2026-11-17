/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.comparison.holders;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

import android.content.Context;
import android.view.View;
import com.bumptech.glide.Glide;
import com.facebook.samples.comparison.Drawables;
import com.facebook.samples.comparison.instrumentation.InstrumentedImageView;
import com.facebook.samples.comparison.instrumentation.PerfListener;

/** This is the Holder class for the RecycleView to use with Glide */
public class GlideHolder extends BaseViewHolder<InstrumentedImageView> {

  public GlideHolder(
      Context context,
      View layoutView,
      InstrumentedImageView instrumentedImageView,
      PerfListener perfListener) {
    super(context, layoutView, instrumentedImageView, perfListener);
  }

  @Override
  protected void onBind(String uri) {
    Glide.with(mImageView.getContext())
        .load(uri)
        .placeholder(Drawables.sPlaceholderDrawable)
        .error(Drawables.sErrorDrawable)
        .transition(withCrossFade())
        .into(mImageView);
  }
}
