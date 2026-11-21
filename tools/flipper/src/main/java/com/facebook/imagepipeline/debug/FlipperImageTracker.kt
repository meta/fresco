/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.debug

import com.facebook.cache.common.CacheKey
import com.facebook.cache.common.CacheKeyUtil
import com.facebook.fresco.ui.common.ImageLoadStatus
import com.facebook.fresco.ui.common.ImagePerfData
import com.facebook.fresco.ui.common.ImagePerfDataListener
import com.facebook.fresco.ui.common.VisibilityState
import com.facebook.imagepipeline.request.ImageRequest

private const val MAX_IMAGES_TO_TRACK = 1_000

/** Fresco image tracker for Sonar */
class FlipperImageTracker : DebugImageTracker, ImagePerfDataListener {

  private val imageRequestDebugDataMap: MutableMap<ImageRequest?, ImageDebugData> =
      LruMap(MAX_IMAGES_TO_TRACK)
  private val imageDebugDataMap: MutableMap<CacheKey, ImageDebugData> = LruMap(MAX_IMAGES_TO_TRACK)

  @Synchronized
  override fun trackImage(imageRequest: ImageRequest?, cacheKey: CacheKey) {
    var imageDebugData = imageRequestDebugDataMap[imageRequest]
    if (imageDebugData == null) {
      imageDebugData = ImageDebugData(imageRequest)
      imageDebugDataMap[cacheKey] = imageDebugData
      imageRequestDebugDataMap[imageRequest] = imageDebugData
    }
    imageDebugData.addCacheKey(cacheKey)
    imageDebugData.addResourceId(CacheKeyUtil.getFirstResourceId(cacheKey))
  }

  @Synchronized
  override fun trackImageRequest(imageRequest: ImageRequest?, requestId: String?) {
    var imageDebugData = imageRequestDebugDataMap[imageRequest]
    if (imageDebugData == null) {
      imageDebugData = ImageDebugData(imageRequest)
      imageRequestDebugDataMap[imageRequest] = imageDebugData
    }
    imageDebugData.addRequestId(requestId)
  }

  @Synchronized
  fun trackImage(localPath: String?, key: CacheKey): ImageDebugData {
    val data = ImageDebugData(localPath)
    imageDebugDataMap[key] = data
    return data
  }

  @Synchronized
  fun trackImage(key: CacheKey): ImageDebugData {
    val data = ImageDebugData()
    imageDebugDataMap[key] = data
    return data
  }

  @Synchronized
  fun getUriString(key: CacheKey): String {
    val imageDebugData = getImageDebugData(key)
    if (imageDebugData != null) {
      val imageRequest = imageDebugData.imageRequest
      if (imageRequest != null) {
        return imageRequest.sourceUri.toString()
      }
    }
    return checkNotNull(key.uriString)
  }

  @Synchronized
  fun getLocalPath(key: CacheKey): String? {
    val imageDebugData = getImageDebugData(key)
    return imageDebugData?.localPath
  }

  @Synchronized fun getImageDebugData(key: CacheKey): ImageDebugData? = imageDebugDataMap[key]

  @Synchronized
  fun getDebugDataForRequestId(requestId: String?): ImageDebugData? {
    for (debugData in imageRequestDebugDataMap.values) {
      val requestIds = debugData.requestIds
      if (requestIds != null && requestIds.contains(requestId)) {
        return debugData
      }
    }
    return null
  }

  @Synchronized
  fun getDebugDataForResourceId(resourceId: String): ImageDebugData? {
    for (debugData in imageRequestDebugDataMap.values) {
      val ids = debugData.resourceIds
      if (ids != null && ids.contains(resourceId)) {
        return debugData
      }
    }
    return null
  }

  @Synchronized
  fun getCacheKey(imageId: String): CacheKey? {
    for ((key, value) in imageDebugDataMap) {
      if (value.uniqueId == imageId) {
        return key
      }
    }
    return null
  }

  @Synchronized
  override fun onImageLoadStatusUpdated(
      imagePerfData: ImagePerfData,
      imageLoadStatus: ImageLoadStatus,
  ) {
    if (imagePerfData.imageRequest == null) {
      return
    }
    val debugData = imageRequestDebugDataMap[imagePerfData.imageRequest]
    if (debugData != null) {
      debugData.imagePerfData = imagePerfData
    } else {
      val imageRequest = imagePerfData.imageRequest as? ImageRequest
      val imageDebugData = ImageDebugData(imageRequest)
      imageDebugData.imagePerfData = imagePerfData
      imageRequestDebugDataMap[imageRequest] = imageDebugData
    }
  }

  @Synchronized
  override fun onImageVisibilityUpdated(
      imagePerfData: ImagePerfData,
      visibilityState: VisibilityState,
  ) {
    // ignore
  }

  @Synchronized
  override fun clear() {
    imageRequestDebugDataMap.clear()
    imageDebugDataMap.clear()
  }

  class ImageDebugData
  @JvmOverloads
  constructor(val imageRequest: ImageRequest? = null, val localPath: String? = null) {
    var imagePerfData: ImagePerfData? = null
    private var _cacheKeys: MutableSet<CacheKey>? = null
    private var _requestIds: MutableSet<String?>? = null
    private var _resourceIds: MutableSet<String>? = null

    constructor(localPath: String?) : this(null, localPath) {}

    val cacheKeys: Set<CacheKey>?
      get() = _cacheKeys

    fun addCacheKey(cacheKey: CacheKey) {
      if (_cacheKeys == null) {
        _cacheKeys = HashSet()
      }
      _cacheKeys?.add(cacheKey)
    }

    val requestIds: Set<String?>?
      get() = _requestIds

    val uniqueId: String
      get() = hashCode().toString()

    fun addRequestId(requestId: String?) {
      if (_requestIds == null) {
        _requestIds = HashSet()
      }
      _requestIds?.add(requestId)
    }

    fun addResourceId(resourceId: String?) {
      if (resourceId == null) {
        return
      }
      if (_resourceIds == null) {
        _resourceIds = HashSet()
      }
      _resourceIds?.add(resourceId)
    }

    val resourceIds: Set<String>?
      get() = _resourceIds
  }
}
