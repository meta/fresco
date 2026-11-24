/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.imageutils

import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Util for obtaining information from JPEG file. */
object JfifUtil {

  /**
   * Definitions of jpeg markers as well as overall description of jpeg file format can be found
   * here: [Recommendation T.81](http://www.w3.org/Graphics/JPEG/itu-t81.pdf)
   */
  const val MARKER_FIRST_BYTE = 0xFF
  const val MARKER_ESCAPE_BYTE = 0x00
  const val MARKER_SOI = 0xD8
  const val MARKER_TEM = 0x01
  const val MARKER_EOI = 0xD9
  const val MARKER_SOS = 0xDA
  const val MARKER_APP1 = 0xE1
  const val MARKER_SOFn = 0xC0
  const val MARKER_RST0 = 0xD0
  const val MARKER_RST7 = 0xD7
  const val APP1_EXIF_MAGIC = 0x45786966

  /**
   * Determines auto-rotate angle based on orientation information.
   *
   * @param orientation orientation information, one of {1, 3, 6, 8}.
   * @return orientation: 1/3/6/8 -> 0/180/90/270.
   */
  @JvmStatic
  fun getAutoRotateAngleFromOrientation(orientation: Int): Int =
      TiffUtil.getAutoRotateAngleFromOrientation(orientation)

  /**
   * Gets orientation information from jpeg byte array.
   *
   * @param jpeg the input byte array of jpeg image
   * @return orientation: 1/8/3/6. Returns 0 if there is no valid orientation information.
   */
  @JvmStatic
  fun getOrientation(jpeg: ByteArray?): Int =
      // wrapping with ByteArrayInputStream is cheap and we don't have duplicate implementation
      getOrientation(ByteArrayInputStream(jpeg))

  /**
   * Get orientation information from jpeg input stream.
   *
   * @param inputStream the input stream of jpeg image
   * @return orientation: 1/8/3/6. Returns {@value
   * * android.media.ExifInterface#ORIENTATION_UNDEFINED} if there is no valid orientation
   *   information.
   */
  @JvmStatic
  fun getOrientation(inputStream: InputStream): Int =
      try {
        val length = moveToAPP1EXIF(inputStream)
        if (length == 0) {
          ExifInterface.ORIENTATION_UNDEFINED
        } else {
          TiffUtil.readOrientationFromTIFF(inputStream, length)
        }
      } catch (ioe: IOException) {
        ExifInterface.ORIENTATION_UNDEFINED
      }

  /**
   * Reads the content of the input stream until specified marker is found. Marker will be consumed
   * and the input stream will be positioned after the specified marker.
   *
   * @param inputStream the input stream to read bytes from
   * @param markerToFind the marker we are looking for
   * @return boolean: whether or not we found the expected marker from input stream.
   */
  @JvmStatic
  @Throws(IOException::class)
  fun moveToMarker(inputStream: InputStream, markerToFind: Int): Boolean {
    checkNotNull(inputStream)
    // ISO/IEC 10918-1:1993(E)
    while (StreamProcessor.readPackedInt(inputStream, 1, false) == MARKER_FIRST_BYTE) {
      var marker = MARKER_FIRST_BYTE
      while (marker == MARKER_FIRST_BYTE) {
        marker = StreamProcessor.readPackedInt(inputStream, 1, false)
      }
      if (markerToFind == MARKER_SOFn && isSOFn(marker)) {
        return true
      }
      if (marker == markerToFind) {
        return true
      }

      // Check if the marker is SOI or TEM. These two don't have length field, so we skip it.
      if (marker == MARKER_SOI || marker == MARKER_TEM) {
        continue
      }

      // Check if the marker is EOI or SOS. We will stop reading since metadata markers don't
      // come after these two markers.
      if (marker == MARKER_EOI || marker == MARKER_SOS) {
        return false
      }

      // read block length
      // subtract 2 as length contain SIZE field we just read
      val length = StreamProcessor.readPackedInt(inputStream, 2, false) - 2
      // The length field counts its own two bytes, so a smaller value means the data is malformed.
      if (length < 0) {
        return false
      }
      // Skip other markers.
      inputStream.skip(length.toLong())
    }
    return false
  }

  private fun isSOFn(marker: Int): Boolean =
      // There are no SOF4, SOF8, SOF12
      when (marker) {
        0xC0,
        0xC1,
        0xC2,
        0xC3,
        0xC5,
        0xC6,
        0xC7,
        0xC9,
        0xCA,
        0xCB,
        0xCD,
        0xCE,
        0xCF -> true
        else -> false
      }

  /**
   * Positions the given input stream to the beginning of the EXIF data in the JPEG APP1 block.
   *
   * @param inputStream the input stream of jpeg image
   * @return length of EXIF data
   */
  @Throws(IOException::class)
  private fun moveToAPP1EXIF(inputStream: InputStream): Int {
    if (moveToMarker(inputStream, MARKER_APP1)) {
      // read block length
      // subtract 2 as length contain SIZE field we just read
      var length = StreamProcessor.readPackedInt(inputStream, 2, false) - 2
      if (length > 6) {
        val magic = StreamProcessor.readPackedInt(inputStream, 4, false)
        length -= 4
        val zero = StreamProcessor.readPackedInt(inputStream, 2, false)
        length -= 2
        if (magic == APP1_EXIF_MAGIC && zero == 0) {
          // JEITA CP-3451 Exif Version 2.2
          return length
        }
      }
    }
    return 0
  }
}
