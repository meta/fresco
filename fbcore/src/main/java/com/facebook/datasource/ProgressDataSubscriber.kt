/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.datasource

/**
 * A subscriber that can decline progress notifications without changing the DataSource's progress.
 */
interface ProgressDataSubscriber<T> : DataSubscriber<T> {
  /**
   * Queried on the producer thread before dispatch. Must be fast, thread-safe and side-effect free.
   */
  fun shouldNotifyProgress(): Boolean
}
