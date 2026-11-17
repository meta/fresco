/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.datasource;

import android.util.Pair;
import com.facebook.common.internal.Preconditions;
import com.facebook.infer.annotation.Nullsafe;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * An abstract implementation of {@link DataSource} interface.
 *
 * <p>It is highly recommended that other data sources extend this class as it takes care of the
 * state, as well as of notifying listeners when the state changes.
 *
 * <p>Subclasses should override {@link #closeResult(T result)} if results need clean up
 *
 * @param <T>
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public abstract class AbstractDataSource<T> implements DataSource<T> {

  private @Nullable Map<String, Object> mExtras;

  /** Describes state of data source */
  private enum DataSourceStatus {
    // data source has not finished yet
    IN_PROGRESS,

    // data source has finished with success
    SUCCESS,

    // data source has finished with failure
    FAILURE,
  }

  @GuardedBy("this")
  private DataSourceStatus mDataSourceStatus;

  @GuardedBy("this")
  private boolean mIsClosed;

  @GuardedBy("this")
  private @Nullable T mResult = null;

  @Nullable
  @GuardedBy("this")
  private Throwable mFailureThrowable = null;

  @GuardedBy("this")
  private float mProgress = 0;

  private final ConcurrentLinkedQueue<Pair<DataSubscriber<T>, Executor>> mSubscribers;

  @Nullable private static volatile DataSourceInstrumenter sDataSourceInstrumenter;

  public static void provideInstrumenter(@Nullable DataSourceInstrumenter dataSourceInstrumenter) {
    sDataSourceInstrumenter = dataSourceInstrumenter;
  }

  protected AbstractDataSource() {
    mIsClosed = false;
    mDataSourceStatus = DataSourceStatus.IN_PROGRESS;
    mSubscribers = new ConcurrentLinkedQueue<Pair<DataSubscriber<T>, Executor>>();
  }

  @Override
  public synchronized boolean isClosed() {
    return mIsClosed;
  }

  @Override
  public synchronized boolean isFinished() {
    return mDataSourceStatus != DataSourceStatus.IN_PROGRESS;
  }

  @Override
  public synchronized boolean hasResult() {
    return mResult != null;
  }

  @Override
  @Nullable
  public synchronized T getResult() {
    return mResult;
  }

  @Override
  public @Nullable Map<String, Object> getExtras() {
    return mExtras;
  }

  protected void setExtras(@Nullable Map<String, Object> extras) {
    mExtras = extras;
  }

  @Override
  public synchronized boolean hasFailed() {
    return mDataSourceStatus == DataSourceStatus.FAILURE;
  }

  @Override
  @Nullable
  public synchronized Throwable getFailureCause() {
    return mFailureThrowable;
  }

  @Override
  public synchronized float getProgress() {
    return mProgress;
  }

  @Override
  public boolean close() {
    T resultToClose;
    synchronized (this) {
      if (mIsClosed) {
        return false;
      }
      mIsClosed = true;
      resultToClose = mResult;
      mResult = null;
    }
    if (resultToClose != null) {
      closeResult(resultToClose);
    }
    if (!isFinished()) {
      notifyDataSubscribers();
    }
    synchronized (this) {
      mSubscribers.clear();
    }
    return true;
  }

  /**
   * Subclasses should override this method to close the result that is not needed anymore.
   *
   * <p>This method is called in two cases: 1. to clear the result when data source gets closed 2.
   * to clear the previous result when a new result is set
   */
  protected void closeResult(@Nullable T result) {
    // default implementation does nothing
  }

  @Override
  public void subscribe(final DataSubscriber<T> dataSubscriber, final Executor executor) {
    Preconditions.checkNotNull(dataSubscriber);
    Preconditions.checkNotNull(executor);
    boolean shouldNotify;

    synchronized (this) {
      if (mIsClosed) {
        return;
      }

      if (mDataSourceStatus == DataSourceStatus.IN_PROGRESS) {
        mSubscribers.add(Pair.create(dataSubscriber, executor));
      }

      shouldNotify = hasResult() || isFinished() || wasCancelled();
    }

    if (shouldNotify) {
      notifyDataSubscriber(dataSubscriber, executor, hasFailed(), wasCancelled());
    }
  }

  private void notifyDataSubscribers() {
    final boolean isFailure = hasFailed();
    final boolean isCancellation = wasCancelled();
    for (Pair<DataSubscriber<T>, Executor> pair : mSubscribers) {
      notifyDataSubscriber(pair.first, pair.second, isFailure, isCancellation);
    }
  }

  protected void notifyDataSubscriber(
      final DataSubscriber<T> dataSubscriber,
      final Executor executor,
      final boolean isFailure,
      final boolean isCancellation) {
    final DataSourceInstrumenter instrumenter = getDataSourceInstrumenter();
    if (instrumenter == null && canRunInline(executor)) {
      deliverDataSubscriber(dataSubscriber, isFailure, isCancellation);
      return;
    }
    Runnable runnable =
        new Runnable() {
          @Override
          public void run() {
            deliverDataSubscriber(dataSubscriber, isFailure, isCancellation);
          }
        };
    if (instrumenter != null) {
      runnable = instrumenter.decorateRunnable(runnable, "AbstractDataSource_notifyDataSubscriber");
    }
    executor.execute(runnable);
  }

  private void deliverDataSubscriber(
      DataSubscriber<T> dataSubscriber, boolean isFailure, boolean isCancellation) {
    if (isFailure) {
      dataSubscriber.onFailure(this);
    } else if (isCancellation) {
      dataSubscriber.onCancellation(this);
    } else {
      dataSubscriber.onNewResult(this);
    }
  }

  private static boolean canRunInline(Executor executor) {
    return executor instanceof InlineDataSourceExecutor
        && ((InlineDataSourceExecutor) executor).canRunInline();
  }

  private synchronized boolean wasCancelled() {
    return isClosed() && !isFinished();
  }

  /**
   * Subclasses should invoke this method to set the result to {@code value}.
   *
   * <p>This method will return {@code true} if the value was successfully set, or {@code false} if
   * the data source has already been set, failed or closed.
   *
   * <p>If the value was successfully set and {@code isLast} is {@code true}, state of the data
   * source will be set to {@link AbstractDataSource.DataSourceStatus#SUCCESS}.
   *
   * <p>{@link #closeResult} will be called for the previous result if the new value was
   * successfully set, OR for the new result otherwise.
   *
   * <p>This will also notify the subscribers if the value was successfully set.
   *
   * <p>Do NOT call this method from a synchronized block as it invokes external code of the
   * subscribers.
   *
   * @param value the value that was the result of the task.
   * @param isLast whether or not the value is last.
   * @param extras an object with extra data for this datasource
   * @return true if the value was successfully set.
   */
  protected boolean setResult(
      @Nullable T value, boolean isLast, @Nullable Map<String, Object> extras) {
    setExtras(extras);
    boolean result = setResultInternal(value, isLast);
    if (result) {
      notifyDataSubscribers();
    }
    return result;
  }

  public boolean setResult(@Nullable T value, boolean isLast) {
    return setResult(value, isLast, null);
  }

  /**
   * Subclasses should invoke this method to set the failure.
   *
   * <p>This method will return {@code true} if the failure was successfully set, or {@code false}
   * if the data source has already been set, failed or closed.
   *
   * <p>If the failure was successfully set, state of the data source will be set to {@link
   * AbstractDataSource.DataSourceStatus#FAILURE}.
   *
   * <p>This will also notify the subscribers if the failure was successfully set.
   *
   * <p>Do NOT call this method from a synchronized block as it invokes external code of the
   * subscribers.
   *
   * @param throwable the failure cause to be set.
   * @return true if the failure was successfully set.
   */
  protected boolean setFailure(Throwable throwable) {
    return setFailure(throwable, null);
  }

  protected boolean setFailure(
      @Nullable Throwable throwable, @Nullable Map<String, Object> extras) {
    boolean result = setFailureInternal(throwable, extras);
    if (result) {
      notifyDataSubscribers();
    }
    return result;
  }

  /**
   * Subclasses should invoke this method to set the progress.
   *
   * <p>This method will return {@code true} if the progress was successfully set, or {@code false}
   * if the data source has already been set, failed or closed.
   *
   * <p>This will also notify the subscribers if the progress was successfully set.
   *
   * <p>Do NOT call this method from a synchronized block as it invokes external code of the
   * subscribers.
   *
   * @param progress the progress in range [0, 1] to be set.
   * @return true if the progress was successfully set.
   */
  protected boolean setProgress(float progress) {
    boolean result = setProgressInternal(progress);
    if (result) {
      notifyProgressUpdate();
    }
    return result;
  }

  private boolean setResultInternal(@Nullable T value, boolean isLast) {
    T resultToClose = null;
    try {
      synchronized (this) {
        if (mIsClosed || mDataSourceStatus != DataSourceStatus.IN_PROGRESS) {
          resultToClose = value;
          return false;
        } else {
          if (isLast) {
            mDataSourceStatus = DataSourceStatus.SUCCESS;
            mProgress = 1;
          }
          if (mResult != value) {
            resultToClose = mResult;
            mResult = value;
          }
          return true;
        }
      }
    } finally {
      if (resultToClose != null) {
        closeResult(resultToClose);
      }
    }
  }

  private synchronized boolean setFailureInternal(
      @Nullable Throwable throwable, @Nullable Map<String, Object> extras) {
    if (mIsClosed || mDataSourceStatus != DataSourceStatus.IN_PROGRESS) {
      return false;
    } else {
      mDataSourceStatus = DataSourceStatus.FAILURE;
      mFailureThrowable = throwable;
      mExtras = extras;
      return true;
    }
  }

  private synchronized boolean setProgressInternal(float progress) {
    if (mIsClosed || mDataSourceStatus != DataSourceStatus.IN_PROGRESS) {
      return false;
    } else if (progress < mProgress) {
      return false;
    } else {
      mProgress = progress;
      return true;
    }
  }

  protected void notifyProgressUpdate() {
    for (Pair<DataSubscriber<T>, Executor> pair : mSubscribers) {
      final DataSubscriber<T> subscriber = pair.first;
      if (subscriber instanceof ProgressDataSubscriber
          && !((ProgressDataSubscriber<?>) subscriber).shouldNotifyProgress()) {
        continue;
      }
      Executor executor = pair.second;
      if (canRunInline(executor)) {
        subscriber.onProgressUpdate(this);
        continue;
      }
      executor.execute(
          new Runnable() {
            @Override
            public void run() {
              subscriber.onProgressUpdate(AbstractDataSource.this);
            }
          });
    }
  }

  @Nullable
  public static DataSourceInstrumenter getDataSourceInstrumenter() {
    return sDataSourceInstrumenter;
  }

  @Override
  public boolean hasMultipleResults() {
    return false;
  }

  /** Allows to capture unit of works for instrumentation purposes. */
  public interface DataSourceInstrumenter {

    /**
     * Called when a unit of work is about to be scheduled.
     *
     * @param runnable that will be executed.
     * @param tag name.
     * @return the wrapped input runnable or just the input one.
     */
    Runnable decorateRunnable(Runnable runnable, String tag);
  }
}
