/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.comparison.holders;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import com.facebook.samples.comparison.instrumentation.InstrumentedVitoView;
import com.facebook.samples.comparison.instrumentation.PerfListener;

/** This is the Holder class for the RecycleView to use with Volley and Vito */
public class VolleyDraweeHolder extends BaseViewHolder<InstrumentedVitoView> {

  public VolleyDraweeHolder(
      Context context, View parentView, InstrumentedVitoView view, PerfListener perfListener) {
    super(context, parentView, view, perfListener);
  }

  @Override
  protected void onBind(String uri) {
    mImageView.setImageURI(Uri.parse(uri));
  }
}
