/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.fresco.middleware.HasExtraData
import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.core.ImagePipelineConfigInterface
import com.facebook.imagepipeline.request.ImageRequest

/**
 * Used to pass context information to producers.
 *
 * Object implementing this interface is passed to all producers participating in pipeline request
 * {@see Producer#produceResults}. Its responsibility is to instruct producers which image should be
 * fetched/decoded/resized/cached etc. This class also handles request cancellation.
 *
 * In order to be notified when cancellation is requested, a producer should use the
 * `runOnCancellationRequested` method which takes an instance of Runnable and executes it when the
 * pipeline client cancels the image request.
 */
interface ProducerContext : HasExtraData {

  /** @return image request that is being executed */
  val imageRequest: ImageRequest

  /** @return id of this request */
  val id: String

  /** @return optional id of the UI component requesting the image */
  val uiComponentId: String?

  /** @return ProducerListener2 for producer's events */
  val producerListener: ProducerListener2

  /** @return the [Object] that indicates the caller's context */
  val callerContext: Any

  /** @return the lowest permitted [ImageRequest.RequestLevel] */
  val lowestPermittedRequestLevel: ImageRequest.RequestLevel

  /** @return true if the request is a prefetch, false otherwise. */
  val isPrefetch: Boolean

  /** @return priority of the request. */
  val priority: Priority

  /** @return true if request's owner expects intermediate results */
  val isIntermediateResultExpected: Boolean

  /**
   * Adds callbacks to the set of callbacks that are executed at various points during the
   * processing of a request.
   *
   * @param callbacks callbacks to be executed
   */
  fun addCallbacks(callbacks: ProducerContextCallbacks)

  /**
   * Native pipeline configuration.
   *
   * @throws UnsupportedOperationException for contexts created by a config-free pipeline. Such
   *   sequences must perform their work without reading this property.
   */
  val imagePipelineConfig: ImagePipelineConfigInterface

  /** Helper to set [HasExtraData.KEY_ORIGIN] and [HasExtraData.KEY_ORIGIN_SUBCATEGORY] */
  fun putOriginExtra(origin: String?, subcategory: String?)

  /** Helper to set [HasExtraData.KEY_ORIGIN] */
  fun putOriginExtra(origin: String?)
}
