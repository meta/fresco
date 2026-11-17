/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.animated.base

import com.facebook.imagepipeline.image.EncodedImage

fun interface AnimatedImageValidator {
  fun validateImage(encodedImage: EncodedImage): ValidationResult
}

sealed class ValidationResult(val isValid: Boolean, val message: String? = null) {

  object Success : ValidationResult(true)

  class Failure(message: String) : ValidationResult(false, message)
}
