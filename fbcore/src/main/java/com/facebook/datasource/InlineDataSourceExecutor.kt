/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.datasource

import java.util.concurrent.Executor

/** An executor that explicitly permits allocation-free DataSource notification on its thread. */
interface InlineDataSourceExecutor : Executor {
  /**
   * Whether execute(runnable) on the current thread is equivalent to runnable.run(), with no
   * scheduling, ordering, decoration or other bookkeeping. Must be safe to query from any thread.
   */
  fun canRunInline(): Boolean
}
