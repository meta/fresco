/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.animated.webp

import com.facebook.common.internal.DoNotStrip
import com.facebook.imagepipeline.animated.factory.AnimatedImageDecoderBase
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.decoder.ImageDecoder
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.image.QualityInfo

@DoNotStrip
class WebPImageDecoder
@DoNotStrip
constructor(
    platformBitmapFactory: PlatformBitmapFactory,
    isNewRenderImplementation: Boolean,
    downscaleFrameToDrawableDimensions: Boolean,
    treatAnimatedImagesAsStateful: Boolean = true,
) :
    AnimatedImageDecoderBase(
        platformBitmapFactory,
        downscaleFrameToDrawableDimensions,
        isNewRenderImplementation,
        treatAnimatedImagesAsStateful,
    ),
    ImageDecoder {

  /**
   * Decodes an animated WebP image into a CloseableImage.
   *
   * @param encodedImage encoded image (native byte array holding the encoded bytes and meta data)
   * @param length the length of the encoded data
   * @param qualityInfo quality information about the image
   * @param options decode options specifying how the image should be decoded
   * @return a CloseableImage
   */
  @DoNotStrip
  override fun decode(
      encodedImage: EncodedImage,
      length: Int,
      qualityInfo: QualityInfo,
      options: ImageDecodeOptions,
  ): CloseableImage? {
    val bytesRef = encodedImage.byteBufferRef
    checkNotNull(bytesRef)

    bytesRef.use {
      val input = bytesRef.get()
      val image =
          input.byteBuffer?.let { byteBuffer ->
            WebPImage.createFromByteBuffer(byteBuffer, options)
          } ?: WebPImage.createFromNativeMemory(input.nativePtr, input.size(), options)
      return getCloseableImage(
          encodedImage.source,
          options,
          checkNotNull(image),
          options.animatedBitmapConfig,
      )
    }
  }
}
