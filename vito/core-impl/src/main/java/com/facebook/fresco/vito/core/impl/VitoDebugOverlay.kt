/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

/**
 * A debug overlay for [KFrescoController] images, such as [DebugOverlayHandler]: [update] is called
 * after the image listeners whenever the drawable's image changes, on the thread that changed it.
 */
fun interface VitoDebugOverlay {
  fun update(drawable: KFrescoVitoDrawable)
}
