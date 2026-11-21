/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.cache.common

import com.facebook.common.util.SecureHashUtil
import java.io.UnsupportedEncodingException
import java.util.ArrayList

object CacheKeyUtil {

  /**
   * Get a list of possible resourceIds from MultiCacheKey or get single resourceId from CacheKey.
   */
  @JvmStatic
  fun getResourceIds(key: CacheKey): List<String> =
      try {
        val ids: MutableList<String>
        if (key is MultiCacheKey) {
          val keys = key.cacheKeys
          ids = ArrayList(keys.size)
          for (i in keys.indices) {
            ids.add(secureHashKey(keys[i]))
          }
        } else {
          ids = ArrayList(1)
          ids.add(
              if (key.isResourceIdForDebugging) checkNotNull(key.uriString) else secureHashKey(key),
          )
        }
        ids
      } catch (e: UnsupportedEncodingException) {
        // This should never happen. All VMs support UTF-8
        throw RuntimeException(e)
      }

  /**
   * Get the resourceId from the first key in MultiCacheKey or get single resourceId from CacheKey.
   */
  @JvmStatic
  fun getFirstResourceId(key: CacheKey): String =
      try {
        if (key is MultiCacheKey) {
          val keys = key.cacheKeys
          secureHashKey(keys[0])
        } else {
          secureHashKey(key)
        }
      } catch (e: UnsupportedEncodingException) {
        // This should never happen. All VMs support UTF-8
        throw RuntimeException(e)
      }

  @Throws(UnsupportedEncodingException::class)
  private fun secureHashKey(key: CacheKey): String =
      SecureHashUtil.makeSHA1HashBase64(checkNotNull(key.uriString).toByteArray(charset("UTF-8")))
}
