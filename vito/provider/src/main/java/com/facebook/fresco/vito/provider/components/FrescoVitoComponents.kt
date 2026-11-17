/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.provider.components

import com.facebook.common.logging.FLog
import com.facebook.fresco.vito.core.FrescoController2
import com.facebook.fresco.vito.core.FrescoVitoConfig
import com.facebook.fresco.vito.core.FrescoVitoPrefetcher
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.provider.setup.FrescoVitoSetup
import java.lang.RuntimeException

@Suppress("KotlinSplitStaticMonitor")
object FrescoVitoComponents : FrescoVitoSetup {

  private var _implementation: FrescoVitoSetup? = null

  @Synchronized
  override fun getController(): FrescoController2 = getImplementation().getController()

  @Synchronized
  override fun getPrefetcher(): FrescoVitoPrefetcher = getImplementation().getPrefetcher()

  @Synchronized
  override fun getImagePipeline(): VitoImagePipeline = getImplementation().getImagePipeline()

  @Synchronized override fun getConfig(): FrescoVitoConfig = getImplementation().getConfig()

  /**
   * Reset the implementation. This will remove any implementation currently set up and has to be
   * used with caution.
   */
  @JvmStatic
  @Synchronized
  fun resetImplementation() {
    _implementation = null
  }

  @JvmStatic @Synchronized fun hasBeenInitialized(): Boolean = _implementation != null

  // We do not allow to re-initialize Vito directly.
  // You can use #resetImplementation() if you must manually tear down Vito.
  @JvmStatic
  @Synchronized
  fun getImplementation(): FrescoVitoSetup {
    return _implementation ?: throw RuntimeException("Fresco context provider must be set")
  }

  @JvmStatic
  @Synchronized
  fun setImplementation(implementation: FrescoVitoSetup) {
    // We do not allow to re-initialize Vito directly.
    // You can use #resetImplementation() if you must manually tear down Vito.
    if (_implementation != null) {
      FLog.e(
          "FrescoVitoProvider",
          "Fresco Vito already initialized! Vito must be initialized only once.",
      )
    }
    _implementation = implementation
  }
}
