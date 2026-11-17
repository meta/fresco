/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.decoder

import com.facebook.imageformat.ImageFormat
import com.facebook.imageformat.ImageFormat.FormatChecker
import com.facebook.infer.annotation.Nullsafe

/** Configuration for [ImageDecoder]. */
@Nullsafe(Nullsafe.Mode.LOCAL)
class ImageDecoderConfig private constructor(builder: Builder) {
  val customImageDecoders: Map<ImageFormat, ImageDecoder>? = builder.mCustomImageDecoders

  val customImageFormats: List<FormatChecker>? = builder.mCustomImageFormats

  class Builder {
    var mCustomImageDecoders: MutableMap<ImageFormat, ImageDecoder>? = null
    var mCustomImageFormats: MutableList<FormatChecker>? = null

    /**
     * Add a new decoding capability for a new image format.
     *
     * @param imageFormat the new image format
     * @param imageFormatChecker the format checker that can determine the new image format
     * @param decoder the decoder that can decode the new image format
     * @return the builder
     */
    fun addDecodingCapability(
        imageFormat: ImageFormat,
        imageFormatChecker: FormatChecker,
        decoder: ImageDecoder?,
    ): Builder {
      if (mCustomImageFormats == null) {
        mCustomImageFormats = ArrayList()
      }
      mCustomImageFormats!!.add(imageFormatChecker)
      if (decoder != null) {
        overrideDecoder(imageFormat, decoder)
      }
      return this
    }

    /**
     * Use a different decoder for an existing image format. This can be used for example to set a
     * custom decoder for any of the [com.facebook.imageformat.DefaultImageFormats]
     *
     * @param imageFormat the existing image format
     * @param decoder the decoder to use
     * @return the builder
     */
    fun overrideDecoder(imageFormat: ImageFormat, decoder: ImageDecoder): Builder {
      if (mCustomImageDecoders == null) {
        mCustomImageDecoders = HashMap()
      }
      mCustomImageDecoders!![imageFormat] = decoder
      return this
    }

    fun build(): ImageDecoderConfig {
      return ImageDecoderConfig(this)
    }
  }

  companion object {
    @JvmStatic
    fun newBuilder(): Builder {
      return ImageDecoderConfig.Builder()
    }
  }
}
