/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.cache.common;

import android.net.Uri;
import com.facebook.common.internal.Preconditions;
import com.facebook.infer.annotation.Nullsafe;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A cache key that wraps multiple cache keys.
 *
 * <p>Note: {@code equals} and {@code hashcode} are implemented in a way that two MultiCacheKeys are
 * equal if and only if the underlying list of cache keys is equal. That implies AllOf semantics.
 * Unfortunately, it is not possible to implement AnyOf semantics for {@code equals} because the
 * transitivity requirement wouldn't be satisfied. I.e. we would have: {A} = {A, B}, {A, B} = {B},
 * but {A} != {B}.
 *
 * <p>It is fine to use this key with AnyOf semantics, but one should be aware of {@code equals} and
 * {@code hashcode} behavior, and should implement AnyOf logic manually.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class MultiCacheKey implements CacheKey {

  final List<CacheKey> mCacheKeys;

  public MultiCacheKey(List<CacheKey> cacheKeys) {
    mCacheKeys = Preconditions.checkNotNull(cacheKeys);
  }

  public List<CacheKey> getCacheKeys() {
    return mCacheKeys;
  }

  @Override
  public String toString() {
    return "MultiCacheKey:" + mCacheKeys.toString();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other == this) {
      return true;
    }
    if (other instanceof MultiCacheKey) {
      final MultiCacheKey otherKey = (MultiCacheKey) other;
      return mCacheKeys.equals(otherKey.mCacheKeys);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return mCacheKeys.hashCode();
  }

  @Override
  public boolean containsUri(Uri uri) {
    for (int i = 0; i < mCacheKeys.size(); i++) {
      if (mCacheKeys.get(i).containsUri(uri)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public @Nullable String getUriString() {
    return mCacheKeys.get(0).getUriString();
  }

  @Override
  public boolean isResourceIdForDebugging() {
    return false;
  }
}
