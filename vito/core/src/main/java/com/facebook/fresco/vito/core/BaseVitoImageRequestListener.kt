/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core

import android.graphics.drawable.Drawable
import com.facebook.fresco.ui.common.ControllerListener2
import com.facebook.imagepipeline.image.ImageInfo

open class BaseVitoImageRequestListener() : VitoImageRequestListener {
  override fun onSubmit(
      id: Long,
      imageRequest: VitoImageRequest,
      callerContext: Any?,
      extras: ControllerListener2.Extras?,
  ) = Unit

  override fun onPlaceholderSet(id: Long, imageRequest: VitoImageRequest, placeholder: Drawable?) =
      Unit

  override fun onFinalImageSet(
      id: Long,
      imageRequest: VitoImageRequest,
      imageOrigin: Int,
      imageInfo: ImageInfo?,
      extras: ControllerListener2.Extras?,
      drawable: Drawable?,
  ) = Unit

  override fun onIntermediateImageSet(
      id: Long,
      imageRequest: VitoImageRequest,
      imageInfo: ImageInfo?,
  ) = Unit

  override fun onIntermediateImageFailed(
      id: Long,
      imageRequest: VitoImageRequest,
      throwable: Throwable?,
  ) = Unit

  override fun onFailure(
      id: Long,
      imageRequest: VitoImageRequest,
      error: Drawable?,
      throwable: Throwable?,
      extras: ControllerListener2.Extras?,
  ) = Unit

  override fun onRelease(
      id: Long,
      imageRequest: VitoImageRequest,
      extras: ControllerListener2.Extras?,
  ) = Unit

  override fun onEmptyEvent(callerContext: Any?) = Unit
}
