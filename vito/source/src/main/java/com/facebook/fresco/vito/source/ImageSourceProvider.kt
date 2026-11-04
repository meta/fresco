/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.source

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import com.facebook.drawee.drawable.SizingHint
import com.facebook.fresco.middleware.HasExtraData

/**
 * Create image sources that can be passed to Fresco's image components. For example, to create a
 * single image source for a given URI, call [forUri(Uri)] or [forUri(String)].
 */
object ImageSourceProvider {

  /**
   * Uri parsing function from String to URI. The default implementation can be replaced with any
   * custom URI parsing logic if required.
   */
  var uriParser: (String) -> Uri? = { uriString ->
    val uri: Uri = Uri.parse(uriString)
    uri
  }

  var shortcutResUris: Boolean = false

  /**
   * When true, an empty (but non-null) [Uri] passed to [forUri] is treated as an empty image source
   * (it maps to an empty image source) instead of building a fetchable request that fails
   * downstream in the producer with "Unsupported uri scheme! Uri is: <>". The host app sets this
   * from MobileConfig (android_fresco_vito_config.handle_empty_uri_earlier); defaults to false to
   * preserve the legacy behavior. Note: the [forUri(String)] path is additionally short-circuited
   * by [uriParser] when the same flag is on; this covers the [forUri(Uri)] path that bypasses
   * parsing.
   */
  var treatEmptyUriAsImageSource: Boolean = false

  var uriCacheSize: Int = 0

  val uriCache: LruCache<String, Uri>? by
      lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val size = uriCacheSize
        if (size > 0) {
          LruCache(uriCacheSize)
        } else {
          null
        }
      }

  private val emptyImageSource = EmptyImageSource("emptySource()")
  private val nullUriImageSource = EmptyImageSource("forUri(null)")
  private val emptyUriImageSource = EmptyImageSource("forUri(empty)")

  /** @return an empty image source if no image URI is available to pass to the UI component */
  @JvmStatic fun emptySource(): ImageSource = emptyImageSource

  /**
   * Create a single image source for a given image URI.
   *
   * @param uri the image URI to use
   * @return the ImageSource to be passed to the UI component
   */
  @JvmOverloads
  @JvmStatic
  fun forUri(uri: Uri?, extras: Map<String, Any>? = null): ImageSource =
      if (uri == null) {
        nullUriImageSource
      } else if (treatEmptyUriAsImageSource && uri.toString().isEmpty()) {
        // Empty (non-null) Uri would otherwise build a fetchable request whose scheme matches no
        // SourceUriType and throws "Unsupported uri scheme! Uri is: <>" in ProducerSequenceFactory.
        emptyUriImageSource
      } else if (shortcutResUris && uri.scheme == "res") {
        val resId = uri.lastPathSegment?.toInt() ?: 0
        DrawableResImageSource(resId)
      } else {
        SingleImageSourceImpl(
            uri,
            buildMap {
              if (extras != null) {
                putAll(extras)
              }
              put(HasExtraData.KEY_URI_SOURCE, uri)
            },
        )
      }

  /**
   * Create a single image source for a given image URI.
   *
   * @param uriString the image URI String to use
   * @return the ImageSource to be passed to the UI component
   */
  @JvmOverloads
  @JvmStatic
  fun forUri(uriString: String?, extras: Map<String, Any>? = null): ImageSource {
    if (uriString != null) {
      val cached: Uri? = uriCache?.get(uriString)
      if (cached != null) {
        return forUri(cached, extras)
      }
    }

    val uri: Uri? = uriString?.let { uriParser(it) }
    if (uri != null) {
      uriCache?.put(uriString, uri)
    }

    return forUri(uri, extras)
  }

  /**
   * Create a Smart Fetch image source for a given image URI.
   *
   * A null URI gives the same empty image source as [forUri]. An empty URI also gives that empty
   * image source when [treatEmptyUriAsImageSource] is true. A `res:` URI with a valid resource ID
   * gives the same [DrawableResImageSource] as [forUri] when [shortcutResUris] is true: Smart Fetch
   * cannot resize a local resource. A `res:` URI without a valid resource ID gives a
   * [SmartImageSource], so its request fails in the image pipeline instead of throwing. Every other
   * URI gives a [SmartImageSource].
   *
   * @param uri the image URI to use
   * @param smartFetchOptIn the Smart Fetch behavior to request
   * @param sizingHint the sizing hint for the Smart Fetch request
   * @param extras optional extra metadata of the image source
   * @return the ImageSource to be passed to the UI component
   */
  @JvmOverloads
  @JvmStatic
  fun smartForUri(
      uri: Uri?,
      smartFetchOptIn: SmartFetchOptIn,
      sizingHint: SizingHint = SizingHint.DEFAULT,
      extras: Map<String, Any>? = null,
  ): ImageSource =
      if (uri == null) {
        nullUriImageSource
      } else if (treatEmptyUriAsImageSource && uri.toString().isEmpty()) {
        emptyUriImageSource
      } else {
        shortcutResId(uri)?.let { DrawableResImageSource(it) }
            ?: SmartImageSource(uri, sizingHint, extras, smartFetchOptIn)
      }

  /**
   * Create a Smart Fetch image source for a given image URI String. [uriParser] parses the String,
   * as in [forUri].
   *
   * @param uriString the image URI String to use
   * @param smartFetchOptIn the Smart Fetch behavior to request
   * @param sizingHint the sizing hint for the Smart Fetch request
   * @param extras optional extra metadata of the image source
   * @return the ImageSource to be passed to the UI component
   */
  @JvmOverloads
  @JvmStatic
  fun smartForUri(
      uriString: String?,
      smartFetchOptIn: SmartFetchOptIn,
      sizingHint: SizingHint = SizingHint.DEFAULT,
      extras: Map<String, Any>? = null,
  ): ImageSource =
      smartForUri(uriString?.let { uriParser(it) }, smartFetchOptIn, sizingHint, extras)

  /**
   * The resource ID of a `res:` URI that [smartForUri] draws without an image request, or null when
   * [shortcutResUris] is false or the URI has no valid nonzero resource ID.
   */
  private fun shortcutResId(uri: Uri): Int? =
      if (shortcutResUris && uri.scheme == "res") {
        uri.lastPathSegment?.toIntOrNull()?.takeIf { it != 0 }
      } else {
        null
      }

  /**
   * Create a multi image source for a given set of sources. Image sources are obtained in order.
   * Only if the current source fails, or if it finishes without a result, the next one will be
   * tried.
   *
   * @param imageSources the list of image sources to be used
   * @return the ImageSource to be passed to the UI component
   */
  @JvmStatic
  fun firstAvailable(vararg imageSources: ImageSource): ImageSource =
      FirstAvailableImageSource(imageSources)

  /**
   * Create a multi image source for a low- and high resolution image. Both requests will be sent
   * off, the low resolution will be used as an intermediate image until the high resolution one is
   * available.
   *
   * @param lowResImageSource the low resolution image source to be used
   * @param highResImageSource the high resolution image source to be used
   * @return the ImageSource to be passed to the UI component
   */
  @JvmStatic
  fun increasingQuality(
      lowResImageSource: ImageSource,
      highResImageSource: ImageSource,
  ): ImageSource = IncreasingQualityImageSource(lowResImageSource, highResImageSource, null)

  /**
   * Create a multi image source for a low- and high resolution image. Both requests will be sent
   * off, the low resolution will be used as an intermediate image until the high resolution one is
   * available.
   *
   * @param lowResImageSource the low resolution image source to be used
   * @param highResImageSource the high resolution image source to be used
   * @param extras optional extra metadata to be used for logging
   * @return the ImageSource to be passed to the UI component
   */
  @JvmStatic
  fun increasingQuality(
      lowResImageSource: ImageSource,
      highResImageSource: ImageSource,
      extras: Map<String, Any>? = null,
  ): ImageSource = IncreasingQualityImageSource(lowResImageSource, highResImageSource, extras)

  /**
   * Create a multi image source for a low- and high resolution image. Both requests will be sent
   * off, the low resolution will be used as an intermediate image until the high resolution one is
   * available.
   *
   * @param lowResImageUri the low resolution image URI to be used
   * @param highResImageUri the high resolution image URI to be used
   * @return the ImageSource to be passed to the UI component
   */
  @JvmStatic
  fun increasingQuality(
      lowResImageUri: Uri?,
      highResImageUri: Uri?,
  ): ImageSource =
      if (lowResImageUri == null) {
        forUri(highResImageUri)
      } else IncreasingQualityImageSource(forUri(lowResImageUri), forUri(highResImageUri))

  /**
   * Create a bitmap image source for a given bitmap image buffer.
   *
   * @param bitmap the bitmap image buffer to be used
   * @return the ImageSource be be passed to the UI component
   */
  @JvmStatic fun bitmap(bitmap: Bitmap): ImageSource = BitmapImageSource(bitmap)

  /**
   * Create a drawable image source for a given drawable.
   *
   * @param drawable the drawable to be used
   * @return the ImageSource to be passed to the UI component
   */
  @JvmStatic fun drawable(drawable: Drawable): ImageSource = DrawableImageSource(drawable)
}
