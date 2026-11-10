/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core

import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.facebook.drawee.drawable.VisibilityCallback
import com.facebook.fresco.ui.common.VisibilityAware
import com.facebook.fresco.vito.listener.ImageListener

interface FrescoDrawableInterface : VisibilityAware {

  val imageId: Long
  var callerContext: Any?
  val imagePerfListener: VitoImagePerfListener
  var uiFramework: String?
  var forceReloadIfImageAlreadySet: Boolean
  var retriggerListenersIfImageAlreadySet: Boolean

  fun setMutateDrawables(mutateDrawables: Boolean)

  /** Whether setting a color filter on the drawable applies it during rendering. */
  fun supportsColorFilter(): Boolean = true

  val actualImageDrawable: Drawable?

  fun hasImage(): Boolean

  val isFetchSubmitted: Boolean

  fun setFetchSubmitted(fetchSubmitted: Boolean)

  var imageRequest: VitoImageRequest?

  fun setVisibilityCallback(visibilityCallback: VisibilityCallback?)

  var imageListener: ImageListener?

  fun setOverlayDrawable(drawable: Drawable?): Drawable?

  var extras: Any?

  /**
   * Get a runnable that can be used to refetch the previous image, if set. This can for example be
   * used to refetch an image when a View has been re-attached without the need to manually trigger
   * a fetch call again.
   *
   * @return the refetch runnable if set
   */
  var refetchRunnable: Runnable?

  fun getImagePerfLoggingListener(): ImagePerfLoggingListener?

  fun setIntrinsicSize(width: Int, height: Int)

  /**
   * Configure the image, bounds, scale and reset the render command. Call it only when you are sure
   * that underlying image has changed.
   */
  fun configureWhenUnderlyingChanged()

  /** Gets the non-cropped post-scaling bounds of the actual image. */
  fun getActualImageBounds(outBounds: RectF)

  /** Return true if the actual image has a HDR gain map. */
  fun hasBitmapWithGainmap(): Boolean
}
