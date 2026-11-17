/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.comparison;

import android.app.Application;

public class ComparisonApp extends Application {

  @Override
  public void onCreate() {
    super.onCreate();
    DumpappInitializer.initialize(this);
  }
}
