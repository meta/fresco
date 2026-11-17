/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request

/**
 * Typed key for app-defined data carried on an [ImageRequest].
 *
 * Fresco never reads extras itself. They let an app attach request data that its own producers or
 * image loaders understand, without subclassing [ImageRequest]. Keys are compared by identity, so
 * declare each key once, as a constant.
 *
 * @param name a human-readable name, used in [ImageRequest.toString]
 * @param affectsEquality whether two requests that differ only in this extra are different
 *   requests. Keep it for data that changes the delivered image; turn it off for metadata such as
 *   logging hints, so that otherwise identical requests stay equal.
 */
class ImageRequestExtra<T : Any>
@JvmOverloads
constructor(
    val name: String,
    val affectsEquality: Boolean = true,
) {
  override fun toString(): String = name
}
