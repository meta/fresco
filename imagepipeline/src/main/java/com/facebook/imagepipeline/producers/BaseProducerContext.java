/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import com.facebook.common.internal.ImmutableSet;
import com.facebook.fresco.middleware.HasExtraData;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.core.ImagePipelineConfigInterface;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.infer.annotation.Nullsafe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * ProducerContext that can be cancelled. Exposes low level API to manipulate state of the
 * ProducerContext.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class BaseProducerContext implements ProducerContext {

  private static final String ORIGIN_SUBCATEGORY_DEFAULT = "default";

  public static final Set<String> INITIAL_KEYS =
      ImmutableSet.of(HasExtraData.KEY_ID, HasExtraData.KEY_URI_SOURCE);
  public static final Object CALLER_CONTEXT_UNSET = new Object();

  private final ImageRequest mImageRequest;
  private final String mId;
  private final @Nullable String mUiComponentId;
  private final ProducerListener2 mProducerListener;
  private final Object mCallerContext;
  private final ImageRequest.RequestLevel mLowestPermittedRequestLevel;
  private final Map<String, Object> mExtras;

  @GuardedBy("this")
  private boolean mIsPrefetch;

  @GuardedBy("this")
  private Priority mPriority;

  @GuardedBy("this")
  private boolean mIsIntermediateResultExpected;

  @GuardedBy("this")
  private boolean mIsCancelled;

  @GuardedBy("this")
  private final List<ProducerContextCallbacks> mCallbacks;

  @Nullable private final ImagePipelineConfigInterface mImagePipelineConfig;

  public BaseProducerContext(
      ImageRequest imageRequest,
      String id,
      ProducerListener2 producerListener,
      @Nullable Object callerContext,
      ImageRequest.RequestLevel lowestPermittedRequestLevel,
      boolean isPrefetch,
      boolean isIntermediateResultExpected,
      Priority priority,
      @Nullable ImagePipelineConfigInterface imagePipelineConfig) {
    this(
        imageRequest,
        id,
        null,
        null,
        producerListener,
        callerContext,
        lowestPermittedRequestLevel,
        isPrefetch,
        isIntermediateResultExpected,
        priority,
        imagePipelineConfig);
  }

  @SuppressWarnings("this-escape")
  public BaseProducerContext(
      ImageRequest imageRequest,
      String id,
      @Nullable String uiComponentId,
      @Nullable Map<String, ?> extras,
      ProducerListener2 producerListener,
      @Nullable Object callerContext,
      ImageRequest.RequestLevel lowestPermittedRequestLevel,
      boolean isPrefetch,
      boolean isIntermediateResultExpected,
      Priority priority,
      @Nullable ImagePipelineConfigInterface imagePipelineConfig) {
    mImageRequest = imageRequest;
    mId = id;

    mExtras = new HashMap<>();
    mExtras.put(HasExtraData.KEY_ID, mId);
    mExtras.put(
        HasExtraData.KEY_URI_SOURCE,
        imageRequest == null ? "null-request" : imageRequest.getSourceUri());
    putExtras(extras);

    mUiComponentId = uiComponentId;
    mProducerListener = producerListener;
    mCallerContext = callerContext != null ? callerContext : CALLER_CONTEXT_UNSET;
    mLowestPermittedRequestLevel = lowestPermittedRequestLevel;

    mIsPrefetch = isPrefetch;
    mPriority = priority;
    mIsIntermediateResultExpected = isIntermediateResultExpected;

    mIsCancelled = false;
    mCallbacks = new ArrayList<>();

    mImagePipelineConfig = imagePipelineConfig;
  }

  @Override
  public ImageRequest getImageRequest() {
    return mImageRequest;
  }

  @Override
  public String getId() {
    return mId;
  }

  @Nullable
  public String getUiComponentId() {
    return mUiComponentId;
  }

  @Override
  public ProducerListener2 getProducerListener() {
    return mProducerListener;
  }

  @Override
  public Object getCallerContext() {
    return mCallerContext;
  }

  @Override
  public ImageRequest.RequestLevel getLowestPermittedRequestLevel() {
    return mLowestPermittedRequestLevel;
  }

  @Override
  public synchronized boolean isPrefetch() {
    return mIsPrefetch;
  }

  @Override
  public synchronized Priority getPriority() {
    return mPriority;
  }

  @Override
  public synchronized boolean isIntermediateResultExpected() {
    return mIsIntermediateResultExpected;
  }

  public synchronized boolean isCancelled() {
    return mIsCancelled;
  }

  @Override
  public void addCallbacks(ProducerContextCallbacks callbacks) {
    boolean cancelImmediately = false;
    synchronized (this) {
      mCallbacks.add(callbacks);
      if (mIsCancelled) {
        cancelImmediately = true;
      }
    }

    if (cancelImmediately) {
      callbacks.onCancellationRequested();
    }
  }

  @Override
  public ImagePipelineConfigInterface getImagePipelineConfig() {
    if (mImagePipelineConfig == null) {
      throw new UnsupportedOperationException("No ImagePipelineConfig for this producer sequence");
    }
    return mImagePipelineConfig;
  }

  /** For generic producer stages where native pipeline configuration is optional. */
  @Nullable
  public static ImagePipelineConfigInterface getImagePipelineConfigOrNull(ProducerContext context) {
    if (context instanceof BaseProducerContext) {
      return ((BaseProducerContext) context).mImagePipelineConfig;
    }
    try {
      return context.getImagePipelineConfig();
    } catch (UnsupportedOperationException e) {
      return null;
    }
  }

  /** Cancels the request processing and calls appropriate callbacks. */
  public void cancel() {
    BaseProducerContext.callOnCancellationRequested(cancelNoCallbacks());
  }

  /**
   * Changes isPrefetch property.
   *
   * <p>This method does not call any callbacks. Instead, caller of this method is responsible for
   * iterating over returned list and calling appropriate method on each callback object. {@see
   * #callOnIsPrefetchChanged}
   *
   * @return list of callbacks if the value actually changes, null otherwise
   */
  @Nullable
  public synchronized List<ProducerContextCallbacks> setIsPrefetchNoCallbacks(boolean isPrefetch) {
    if (isPrefetch == mIsPrefetch) {
      return null;
    }
    this.mIsPrefetch = isPrefetch;
    return new ArrayList<>(mCallbacks);
  }

  /**
   * Changes priority.
   *
   * <p>This method does not call any callbacks. Instead, caller of this method is responsible for
   * iterating over returned list and calling appropriate method on each callback object. {@see
   * #callOnPriorityChanged}
   *
   * @return list of callbacks if the value actually changes, null otherwise
   */
  @Nullable
  public synchronized List<ProducerContextCallbacks> setPriorityNoCallbacks(Priority priority) {
    if (priority == mPriority) {
      return null;
    }
    this.mPriority = priority;
    return new ArrayList<>(mCallbacks);
  }

  /**
   * Changes isIntermediateResultExpected property.
   *
   * <p>This method does not call any callbacks. Instead, caller of this method is responsible for
   * iterating over returned list and calling appropriate method on each callback object. {@see
   * #callOnIntermediateResultChanged}
   *
   * @return list of callbacks if the value actually changes, null otherwise
   */
  @Nullable
  public synchronized List<ProducerContextCallbacks> setIsIntermediateResultExpectedNoCallbacks(
      boolean isIntermediateResultExpected) {
    if (isIntermediateResultExpected == mIsIntermediateResultExpected) {
      return null;
    }
    this.mIsIntermediateResultExpected = isIntermediateResultExpected;
    return new ArrayList<>(mCallbacks);
  }

  /**
   * Marks this ProducerContext as cancelled.
   *
   * <p>This method does not call any callbacks. Instead, caller of this method is responsible for
   * iterating over returned list and calling appropriate method on each callback object. {@see
   * #callOnCancellationRequested}
   *
   * @return list of callbacks if the value actually changes, null otherwise
   */
  @Nullable
  public synchronized List<ProducerContextCallbacks> cancelNoCallbacks() {
    if (mIsCancelled) {
      return null;
    }
    mIsCancelled = true;
    return new ArrayList<>(mCallbacks);
  }

  /**
   * Calls {@code onCancellationRequested} on each element of the list. Does nothing if list == null
   */
  public static void callOnCancellationRequested(
      @Nullable List<ProducerContextCallbacks> callbacks) {
    if (callbacks == null) {
      return;
    }
    for (ProducerContextCallbacks callback : callbacks) {
      callback.onCancellationRequested();
    }
  }

  /** Calls {@code onIsPrefetchChanged} on each element of the list. Does nothing if list == null */
  public static void callOnIsPrefetchChanged(@Nullable List<ProducerContextCallbacks> callbacks) {
    if (callbacks == null) {
      return;
    }
    for (ProducerContextCallbacks callback : callbacks) {
      callback.onIsPrefetchChanged();
    }
  }

  /**
   * Calls {@code onIsIntermediateResultExpected} on each element of the list. Does nothing if list
   * == null
   */
  public static void callOnIsIntermediateResultExpectedChanged(
      @Nullable List<ProducerContextCallbacks> callbacks) {
    if (callbacks == null) {
      return;
    }
    for (ProducerContextCallbacks callback : callbacks) {
      callback.onIsIntermediateResultExpectedChanged();
    }
  }

  /** Calls {@code onPriorityChanged} on each element of the list. Does nothing if list == null */
  public static void callOnPriorityChanged(@Nullable List<ProducerContextCallbacks> callbacks) {
    if (callbacks == null) {
      return;
    }
    for (ProducerContextCallbacks callback : callbacks) {
      callback.onPriorityChanged();
    }
  }

  @Override
  public <E> void putExtra(String key, @Nullable E value) {
    if (INITIAL_KEYS.contains(key)) return;
    mExtras.put(key, value);
  }

  @Override
  public void putExtras(@Nullable Map<String, ?> extras) {
    if (extras == null) return;
    for (Map.Entry<String, ?> entry : extras.entrySet()) {
      putExtra(entry.getKey(), entry.getValue());
    }
  }

  @Nullable
  @Override
  @SuppressWarnings("unchecked")
  public <T> T getExtra(String key) {
    return (T) mExtras.get(key);
  }

  @Nullable
  @Override
  @SuppressWarnings("unchecked")
  public <E> E getExtra(String key, @Nullable E valueIfNotFound) {
    Object maybeValue = mExtras.get(key);
    if (maybeValue == null) {
      return valueIfNotFound;
    }
    return (E) maybeValue;
  }

  @Override
  public Map<String, Object> getExtras() {
    return mExtras;
  }

  @Override
  public void putOriginExtra(@Nullable String origin, @Nullable String subcategory) {
    mExtras.put(HasExtraData.KEY_ORIGIN, origin);
    mExtras.put(HasExtraData.KEY_ORIGIN_SUBCATEGORY, subcategory);
  }

  @Override
  public void putOriginExtra(@Nullable String origin) {
    putOriginExtra(origin, ORIGIN_SUBCATEGORY_DEFAULT);
  }
}
