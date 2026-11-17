/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.samples.showcase;

import android.content.Context;
import com.facebook.imagepipeline.stetho.FrescoStethoPlugin;
import com.facebook.stetho.Stetho;

public final class DumpappInitializer {
  private DumpappInitializer() {}

  public static void initialize(Context context) {
    Stetho.initialize(
        Stetho.newInitializerBuilder(context)
            .enableDumpapp(
                () ->
                    new Stetho.DefaultDumperPluginsBuilder(context)
                        .provide(new FrescoStethoPlugin())
                        .finish())
            .build());
  }
}
