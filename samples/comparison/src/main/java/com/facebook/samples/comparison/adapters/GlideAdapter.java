/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.comparison.adapters;

import android.content.Context;
import android.view.ViewGroup;
import com.bumptech.glide.Glide;
import com.facebook.samples.comparison.holders.GlideHolder;
import com.facebook.samples.comparison.instrumentation.InstrumentedImageView;
import com.facebook.samples.comparison.instrumentation.PerfListener;

/** RecyclerView Adapter for Glide */
public class GlideAdapter extends ImageListAdapter {

  public GlideAdapter(Context context, PerfListener perfListener) {
    super(context, perfListener);
  }

  @Override
  public GlideHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    final InstrumentedImageView instrumentedImageView = new InstrumentedImageView(getContext());
    return new GlideHolder(getContext(), parent, instrumentedImageView, getPerfListener());
  }

  @Override
  public void shutDown() {
    Glide.get(getContext()).clearMemory();
  }
}
