/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.samples.showcase

import android.content.Context
import com.facebook.fresco.samples.showcase.misc.ImageUriProvider
import com.facebook.fresco.vito.provider.FrescoVitoProvider
import com.facebook.fresco.vito.tools.liveeditor.ImageSelector
import com.facebook.fresco.vito.tools.liveeditor.ImageTracker

/**
 * Provides showcase dependencies without requiring ShowcaseApplication as the Application class.
 */
object ShowcaseProvider {
  lateinit var imageUriProvider: ImageUriProvider
    private set

  val imageTracker = ImageTracker()

  lateinit var imageSelector: ImageSelector
    private set

  fun initIfNeeded(context: Context) {
    if (::imageUriProvider.isInitialized) {
      return
    }
    imageUriProvider = ImageUriProvider(context.applicationContext)
    imageSelector =
        ImageSelector(
            imageTracker,
            FrescoVitoProvider.getImagePipeline(),
            FrescoVitoProvider.getController(),
        )
  }
}
