/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.listener

import com.facebook.common.logging.FLog
import com.facebook.imagepipeline.request.ImageRequest
import com.facebook.infer.annotation.Nullsafe

@Nullsafe(Nullsafe.Mode.LOCAL)
class ForwardingRequestListener : RequestListener {
  private val requestListeners: MutableList<RequestListener>

  constructor(requestListeners: Set<RequestListener?>) {
    this.requestListeners = ArrayList(requestListeners.size)
    for (requestListener in requestListeners) {
      if (requestListener != null) {
        this.requestListeners.add(requestListener)
      }
    }
  }

  constructor(vararg requestListeners: RequestListener?) {
    this.requestListeners = ArrayList(requestListeners.size)
    for (requestListener in requestListeners) {
      if (requestListener != null) {
        this.requestListeners.add(requestListener)
      }
    }
  }

  fun addRequestListener(requestListener: RequestListener) {
    requestListeners.add(requestListener)
  }

  override fun onRequestStart(
      request: ImageRequest,
      callerContext: Any,
      requestId: String,
      isPrefetch: Boolean,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onRequestStart(request, callerContext, requestId, isPrefetch)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onRequestStart", exception)
      }
    }
  }

  override fun onProducerStart(requestId: String, producerName: String) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onProducerStart(requestId, producerName)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onProducerStart", exception)
      }
    }
  }

  override fun onProducerFinishWithSuccess(
      requestId: String,
      producerName: String,
      extraMap: Map<String?, String?>?,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onProducerFinishWithSuccess(requestId, producerName, extraMap)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onProducerFinishWithSuccess", exception)
      }
    }
  }

  override fun onProducerFinishWithFailure(
      requestId: String,
      producerName: String,
      t: Throwable,
      extraMap: Map<String?, String?>?,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onProducerFinishWithFailure(requestId, producerName, t, extraMap)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onProducerFinishWithFailure", exception)
      }
    }
  }

  override fun onProducerFinishWithCancellation(
      requestId: String,
      producerName: String,
      extraMap: Map<String?, String?>?,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onProducerFinishWithCancellation(requestId, producerName, extraMap)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onProducerFinishWithCancellation", exception)
      }
    }
  }

  override fun onProducerEvent(requestId: String, producerName: String, producerEventName: String) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onProducerEvent(requestId, producerName, producerEventName)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onIntermediateChunkStart", exception)
      }
    }
  }

  override fun onUltimateProducerReached(
      requestId: String,
      producerName: String,
      successful: Boolean,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onUltimateProducerReached(requestId, producerName, successful)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onProducerFinishWithSuccess", exception)
      }
    }
  }

  override fun onRequestSuccess(request: ImageRequest, requestId: String, isPrefetch: Boolean) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onRequestSuccess(request, requestId, isPrefetch)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onRequestSuccess", exception)
      }
    }
  }

  override fun onRequestFailure(
      request: ImageRequest,
      requestId: String,
      throwable: Throwable,
      isPrefetch: Boolean,
  ) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onRequestFailure(request, requestId, throwable, isPrefetch)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onRequestFailure", exception)
      }
    }
  }

  override fun onRequestCancellation(requestId: String) {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      val listener = requestListeners[i]
      try {
        listener.onRequestCancellation(requestId)
      } catch (exception: Exception) {
        // Don't punish the other listeners if we're given a bad one.
        onException("InternalListener exception in onRequestCancellation", exception)
      }
    }
  }

  override fun requiresExtraMap(id: String): Boolean {
    val numberOfListeners = requestListeners.size
    for (i in 0..<numberOfListeners) {
      if (requestListeners[i].requiresExtraMap(id)) {
        return true
      }
    }
    return false
  }

  private fun onException(message: String, t: Throwable) {
    FLog.e(TAG, message, t)
  }

  companion object {
    private const val TAG = "ForwardingRequestListener"
  }
}
