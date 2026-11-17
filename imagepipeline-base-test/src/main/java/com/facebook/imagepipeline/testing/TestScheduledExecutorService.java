/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.testing;

import com.facebook.infer.annotation.Nullsafe;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class TestScheduledExecutorService extends TestExecutorService
    implements ScheduledExecutorService {

  public TestScheduledExecutorService(FakeClock fakeClock) {
    super(fakeClock);
  }

  @Override
  @SuppressWarnings("rawtypes")
  public ScheduledFuture<?> schedule(final Runnable runnable, long delay, TimeUnit timeUnit) {
    return new TestScheduledFuture(
        getFakeClock(), scheduledQueue, TimeUnit.MILLISECONDS.convert(delay, timeUnit), runnable);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit timeUnit) {
    return new TestScheduledFuture<V>(
        getFakeClock(), scheduledQueue, TimeUnit.MILLISECONDS.convert(delay, timeUnit), callable);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable runnable, long initialDelay, long period, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable runnable, long initialDelay, long delay, TimeUnit timeUnit) {
    throw new UnsupportedOperationException();
  }
}
