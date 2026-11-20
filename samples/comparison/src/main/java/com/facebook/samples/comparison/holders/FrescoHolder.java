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
import com.facebook.common.util.UriUtil;
import com.facebook.fresco.vito.options.ImageOptions;
import com.facebook.fresco.vito.source.ImageSourceProvider;
import com.facebook.fresco.vito.view.VitoView;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.samples.comparison.instrumentation.InstrumentedVitoView;
import com.facebook.samples.comparison.instrumentation.PerfListener;

/** This is the Holder class for the RecycleView to use with Fresco */
public class FrescoHolder extends BaseViewHolder<InstrumentedVitoView> {

  public FrescoHolder(
      Context context,
      View parentView,
      InstrumentedVitoView instrumentedVitoView,
      PerfListener perfListener) {
    super(context, parentView, instrumentedVitoView, perfListener);
  }

  @Override
  protected void onBind(String uriString) {
    Uri uri = Uri.parse(uriString);
    ImageOptions.Builder optionsBuilder = mImageView.getImageOptions().extend();
    if (UriUtil.isNetworkUri(uri)) {
      optionsBuilder.progressiveRendering(true);
    } else {
      optionsBuilder.resize(
          new ResizeOptions(
              mImageView.getLayoutParams().width, mImageView.getLayoutParams().height));
    }
    optionsBuilder.autoPlay(true);

    VitoView.show(
        ImageSourceProvider.forUri(uri),
        optionsBuilder.build(),
        null,
        mImageView.getListener(),
        mImageView);
  }
}
