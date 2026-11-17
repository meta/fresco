/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.middleware

interface HasExtraData {

  fun <E> putExtra(key: String, value: E?)

  fun <E> getExtra(key: String): E?

  fun <E> getExtra(key: String, valueIfNotFound: E? = null): E?

  fun getExtras(): Map<String, Any>

  fun putExtras(extras: Map<String, Any>)

  companion object {
    const val KEY_ID = "id"
    const val KEY_ENCODED_SIZE = "encoded_size"
    const val KEY_ENCODED_WIDTH = "encoded_width"
    const val KEY_ENCODED_HEIGHT = "encoded_height"
    const val KEY_URI_SOURCE = "uri_source"
    const val KEY_IMAGE_FORMAT = "image_format"
    const val KEY_BITMAP_CONFIG = "bitmap_config"
    const val KEY_IS_ROUNDED = "is_rounded"
    const val KEY_NON_FATAL_DECODE_ERROR = "non_fatal_decode_error"

    /** The original URL if it was modified by Dynamic Image URL */
    const val KEY_SF_ORIGINAL_URL = "smart_original_url"

    /** Information on if/why the url was modified by Dynamic Image URL */
    const val KEY_SF_FETCH_STRATEGY = "smart_fetch_strategy"
    const val KEY_SF_MOD_RESULT = "smart_mod_result"
    const val KEY_SF_ADAPTIVE = "smart_adaptive"
    const val KEY_SF_VARIATION = "smart_variation"
    const val KEY_SF_QUERY = "smart_query"
    const val KEY_SF_AIQ = "smart_aiq"

    const val KEY_ORIGIN = "origin"
    const val KEY_ORIGIN_SUBCATEGORY = "origin_sub"
    const val ORIGIN_CANCELLED = "cancelled"
    const val ORIGIN_FAILED = "failed"

    /**
     * Which cache a prefetch is populating, set only on prefetch requests. The decision is made by
     * whichever `ImagePipeline.prefetchTo*` entry point the caller used and is not otherwise
     * recoverable further down the pipeline, where a prefetch to either destination looks the same.
     */
    const val KEY_PREFETCH_DESTINATION = "prefetch_destination"

    /** Prefetch that decodes, populating the bitmap memory cache. */
    const val PREFETCH_DESTINATION_BITMAP = "bitmap"

    /**
     * Prefetch that stops at encoded bytes, asked for via `prefetchToEncodedCache`.
     *
     * Shares a producer sequence with [PREFETCH_DESTINATION_DISK] today, so both populate the
     * encoded memory cache and the disk cache. They are recorded separately because they are
     * distinct requests from the caller's side; a consumer that does not care can treat them alike,
     * but one that collapsed them could not tell them apart again.
     */
    const val PREFETCH_DESTINATION_ENCODED = "encoded"

    /** Prefetch that stops at encoded bytes, asked for via `prefetchToDiskCache`. */
    const val PREFETCH_DESTINATION_DISK = "disk"

    /* number of deduped request in BitmapMemoryCacheKeyMultiplexProducer */
    const val KEY_MULTIPLEX_BITMAP_COUNT = "multiplex_bmp_cnt"

    /* number of deduped request in EncodedCacheKeyMultiplexProducer */
    const val KEY_MULTIPLEX_ENCODED_COUNT = "multiplex_enc_cnt"
    const val KEY_LAST_SCAN_NUMBER = "last_scan_num"
    const val KEY_FAILED_SCAN_NUMBER = "failed_scan_num"
    const val KEY_FAILED_SCAN_IS_INTERMEDIATE = "failed_scan_intermediate"
    const val KEY_FIRST_FAILED_SCAN_NUMBER = "first_failed_scan_num"
    const val KEY_FAILED_SCAN_COUNT = "failed_scan_count"
    const val KEY_FAILED_SCAN_END_OFFSET = "failed_scan_end_offset"
    const val KEY_FAILED_SCAN_ERROR_CLASS = "failed_scan_error_class"
    const val TARGET_SCAN = "target_scan"

    const val KEY_IMAGE_SOURCE_EXTRAS = "image_source_extras"
    const val KEY_COLOR_SPACE = "image_color_space"

    const val KEY_VIEWPORT = "viewport"
    const val KEY_SCALETYPE = "scaletype"
    const val KEY_SIZING_HINT = "sizing_hint"
    const val KEY_IMAGEOPTIONS = "imageoptions"

    const val KEY_DISK_CACHE_ID = "disk_cache_id"

    /* HDR related image extra data */
    const val STORED_IMAGE_HAS_GAIN_MAP = "stored_image_has_gain_map"
    const val FETCHED_IMAGE_HAS_GAIN_MAP = "fetched_image_has_gain_map"
    const val IS_HDR = "is_HDR"
  }
}
