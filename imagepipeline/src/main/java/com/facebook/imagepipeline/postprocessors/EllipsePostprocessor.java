/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.postprocessors;

import android.graphics.Bitmap;
import com.facebook.cache.common.CacheKey;
import com.facebook.cache.common.SimpleCacheKey;
import com.facebook.imagepipeline.filter.InPlaceEllipseRoundFilter;
import com.facebook.imagepipeline.request.BasePostprocessor;
import javax.annotation.Nullable;

/** Postprocessor that rounds a given image as a ellipse using non-native code. */
public class EllipsePostprocessor extends BasePostprocessor {

    private @Nullable CacheKey mCacheKey;

    public EllipsePostprocessor() {;}

    @Override
    public void process(Bitmap bitmap) {
        InPlaceEllipseRoundFilter.roundEllipseBitmapInPlace(bitmap);
    }

    @Nullable
    @Override
    public CacheKey getPostprocessorCacheKey() {
        if (mCacheKey == null) {
            mCacheKey = new SimpleCacheKey("InPlaceEllipseRoundFilter");
        }
        return mCacheKey;
    }
}
