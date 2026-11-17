/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.common.callercontext;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Context chain that adds additional metadata about the current product and infra that's active.
 * These can be chained together, which allows us to identify a given product / infra.
 *
 * <p>Each element in the chain is a simple tag / value pair. Tags can be arbitrary strings that can
 * be used to filter later on. For example, for product metadata, use {@link #TAG_PRODUCT}, for
 * infra {@link #TAG_INFRA} and for both (e.g. if you'd like to report the parent surface, like feed
 * or profile) {@link #TAG_PRODUCT_AND_INFRA}.
 *
 * <p>The context chain can be serialized and used for logging. An example for a serialized chain:
 * "pi:FEED/p:feature_x/i:HeaderComponentSpec/p:profile_photo/i:MyFrescoImage"
 */
public class ContextChain implements Parcelable {

  public static final String TAG_PRODUCT = "p";
  public static final String TAG_INFRA = "i";
  public static final String TAG_PRODUCT_AND_INFRA = TAG_PRODUCT + TAG_INFRA;

  private static final char PARENT_SEPARATOR = '/';

  private final String mTag;
  private final String mName;
  private final @Nullable ContextChain mParent;

  // Allows setting arbitrary key:value String pairs without polluting Context Chain names.
  private @Nullable Map<String, Object> mExtraData;

  private @Nullable String mSerializedChainString;
  private volatile @Nullable String[] mSerializedChainArray;
  private String mSerializedNodeString;

  public ContextChain(
      final String tag,
      final String name,
      final @Nullable Map<String, String> extraData,
      final @Nullable ContextChain parent) {
    mTag = tag;
    mName = name;
    mSerializedNodeString = mTag + ":" + mName;
    mParent = parent;

    initializeExtraData(parent, extraData);
  }

  private void initializeExtraData(
      final @Nullable ContextChain parent, final @Nullable Map<String, ?> extraData) {
    Map<String, Object> parentExtraData = null;
    if (parent != null) {
      parentExtraData = parent.getExtraData();
    }
    if (parentExtraData != null) {
      mExtraData = new ConcurrentHashMap<>(parentExtraData);
    }

    if (extraData != null) {
      if (mExtraData == null) {
        mExtraData = new ConcurrentHashMap<>();
      }
      mExtraData.putAll(extraData);
    }
  }

  public ContextChain(
      final String serializedNodeString,
      final @Nullable Map<String, Object> extraData,
      final @Nullable ContextChain parent) {
    mTag = "serialized_tag";
    mName = "serialized_name";
    mSerializedNodeString = serializedNodeString;
    mParent = parent;

    initializeExtraData(parent, extraData);
  }

  public ContextChain(final String serializedNodeString, final @Nullable ContextChain parent) {
    this(serializedNodeString, (Map<String, Object>) null, parent);
  }

  public ContextChain(final String tag, final String name, final @Nullable ContextChain parent) {
    this(tag, name, null, parent);
  }

  protected ContextChain(Parcel in) {
    mTag = in.readString();
    mName = in.readString();
    mSerializedNodeString = in.readString();
    mParent = in.readParcelable(ContextChain.class.getClassLoader());
  }

  public String getName() {
    return mName;
  }

  public String getTag() {
    return mTag;
  }

  @Nullable
  public Map<String, Object> getExtraData() {
    return mExtraData;
  }

  @Nullable
  public ContextChain getParent() {
    return mParent;
  }

  /** Get the top level context chain, which can be the current context chain itself. */
  public ContextChain getRootContextChain() {
    return mParent == null ? this : mParent.getRootContextChain();
  }

  public @Nullable String getStringExtra(String key) {
    if (mExtraData == null) {
      return null;
    }
    // concurrenthashmap will throw NPE when key parameter is null
    if (key == null) {
      return null;
    }
    Object val = mExtraData.get(key);
    return val == null ? null : String.valueOf(val);
  }

  public void putObjectExtra(String key, Object value) {
    // concurrenthashmap will throw NPE when key or value is null
    if (key == null || value == null) {
      return;
    }
    if (mExtraData == null) {
      mExtraData = new ConcurrentHashMap<>();
    }
    mExtraData.put(key, value);
  }

  @Override
  public String toString() {
    if (mSerializedChainString == null) {
      mSerializedChainString = getNodeString();
      if (mParent != null) {
        mSerializedChainString = mParent.toString() + PARENT_SEPARATOR + mSerializedChainString;
      }
    }
    return mSerializedChainString;
  }

  /**
   * Get serialized representation of ContextChain node
   *
   * @return serialized string
   */
  protected String getNodeString() {
    return mSerializedNodeString;
  }

  public String[] toStringArray() {
    return toString().split(String.valueOf(PARENT_SEPARATOR));
  }

  /** Returns a shared token array. Callers must not mutate the returned value. */
  public String[] toStringArrayCached() {
    String[] serializedChainArray = mSerializedChainArray;
    if (serializedChainArray == null) {
      serializedChainArray = toStringArray();
      mSerializedChainArray = serializedChainArray;
    }
    return serializedChainArray;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    ContextChain other = (ContextChain) obj;
    return Objects.equals(getNodeString(), other.getNodeString())
        && (Objects.equals(mParent, other.mParent));
  }

  @Override
  public int hashCode() {
    return Objects.hash(mParent, getNodeString());
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(mTag);
    dest.writeString(mName);
    dest.writeString(getNodeString());
    dest.writeParcelable(mParent, flags);
  }

  public static final Creator<ContextChain> CREATOR =
      new Creator<ContextChain>() {
        @Override
        public ContextChain createFromParcel(Parcel in) {
          return new ContextChain(in);
        }

        @Override
        public ContextChain[] newArray(int size) {
          return new ContextChain[size];
        }
      };
}
