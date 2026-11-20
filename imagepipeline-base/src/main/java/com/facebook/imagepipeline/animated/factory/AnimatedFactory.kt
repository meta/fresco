/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.animated.factory

import android.content.Context
import com.facebook.imagepipeline.drawable.DrawableFactory
import javax.annotation.concurrent.NotThreadSafe

@NotThreadSafe
fun interface AnimatedFactory {
  fun getAnimatedDrawableFactory(context: Context?): DrawableFactory?
}
