/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.source

/**
 * Image source that can be passed to Fresco's image components. New image sources can be created
 * via [ImageSourceProvider].
 */
fun interface ImageSource {

  fun getClassNameString(): String
}
