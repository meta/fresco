/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core

import android.content.res.Resources
import android.graphics.Rect
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.fresco.urimod.ClassicFetchStrategy
import com.facebook.fresco.urimod.Dimensions
import com.facebook.fresco.urimod.FetchStrategy
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.listener.RequestListener
import java.util.concurrent.TimeUnit

interface VitoImagePipeline {

  /**
   * @param logWithHighSamplingRate The flag is a hint to loggers that they should log all events
   *   for this request at a higher sampling rate. A logger may choose to satisfy this request for
   *   example, by logging every event (sampling ratio 1:1) at the expense of potentially increased
   *   storage and compute capacity. Each logger may interpret this field differently and can choose
   *   to ignore it.
   * @return a new instance of VitoImageRequest
   */
  fun createImageRequest(
      resources: Resources,
      imageSource: ImageSource,
      options: ImageOptions?,
      logWithHighSamplingRate: Boolean = false,
      viewport: Rect? = null,
      callerContext: Any? = null,
      contextChain: ContextChain? = null,
      fetchStrategy: FetchStrategy? = null,
  ): VitoImageRequest

  fun getCachedImage(imageRequest: VitoImageRequest): CloseableReference<CloseableImage>?

  fun fetchDecodedImage(
      imageRequest: VitoImageRequest,
      callerContext: Any?,
      requestListener: RequestListener?,
      uiComponentId: Long,
      viewport: Dimensions? = null,
  ): DataSource<CloseableReference<CloseableImage>>

  fun isInDiskCacheSync(
      imageRequest: VitoImageRequest,
  ): Boolean

  fun isInDiskCacheSync(
      imageRequest: VitoImageRequest,
      timeout: Long,
      unit: TimeUnit,
  ): Boolean? {
    throw UnsupportedOperationException("Not implemented yet")
  }

  /** Remove an image from all disk & memory caches. */
  fun evictFromCaches(imageRequest: VitoImageRequest) {
    throw UnsupportedOperationException("Method not implemented: evictFromCaches")
  }

  fun hintUnmodifiedUri(imageRequest: VitoImageRequest) = Unit

  fun determineFetchStrategy(
      requestBeforeLayout: VitoImageRequest?,
      callerContext: Any?,
      contextChain: ContextChain?,
  ): FetchStrategy = ClassicFetchStrategy.DEFAULT

  /**
   * Creates the request for an image whose [viewport] is known before the fetch, for a renderer
   * without a layout pass of its own, such as a drawable or a view. It asks
   * [determineFetchStrategy] for the strategy of the request and creates the request with that
   * strategy, so Smart Fetch sizes the URI for [viewport] when the strategy allows it. Pass the
   * same viewport to [FrescoController2.fetch].
   */
  fun createImageRequestForViewport(
      resources: Resources,
      imageSource: ImageSource,
      options: ImageOptions?,
      viewport: Rect,
      callerContext: Any?,
      contextChain: ContextChain?,
      logWithHighSamplingRate: Boolean = false,
  ): VitoImageRequest {
    val requestWithoutStrategy = createImageRequest(
        resources,
        imageSource,
        options,
        logWithHighSamplingRate,
        viewport,
        callerContext,
        contextChain,
    )
    return createImageRequest(
        resources,
        imageSource,
        options,
        logWithHighSamplingRate,
        viewport,
        callerContext,
        contextChain,
        determineFetchStrategy(requestWithoutStrategy, callerContext, contextChain),
    )
  }
}
