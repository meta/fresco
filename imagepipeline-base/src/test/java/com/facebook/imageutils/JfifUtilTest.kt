/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imageutils

import com.facebook.common.memory.PooledByteBufferInputStream
import com.facebook.imagepipeline.testing.TrivialPooledByteBuffer
import com.facebook.imageutils.JfifTestUtils.APP0
import com.facebook.imageutils.JfifTestUtils.APP2
import com.facebook.imageutils.JfifTestUtils.DHT
import com.facebook.imageutils.JfifTestUtils.DQT
import com.facebook.imageutils.JfifTestUtils.DRI
import com.facebook.imageutils.JfifTestUtils.EOI
import com.facebook.imageutils.JfifTestUtils.IFD_ENTRY_TAG_1
import com.facebook.imageutils.JfifTestUtils.IFD_ENTRY_TAG_2
import com.facebook.imageutils.JfifTestUtils.IFD_ENTRY_TAG_3
import com.facebook.imageutils.JfifTestUtils.SOF
import com.facebook.imageutils.JfifTestUtils.SOI
import com.facebook.imageutils.JfifTestUtils.SOS
import com.facebook.imageutils.JfifTestUtils.TYPE_SHORT
import com.facebook.imageutils.JfifTestUtils.hexStringToByteArray
import com.facebook.imageutils.JfifTestUtils.makeAPP1_EXIF
import com.facebook.imageutils.JfifTestUtils.makeIfd
import com.facebook.imageutils.JfifTestUtils.makeIfdEntry
import com.facebook.imageutils.JfifTestUtils.makeOrientationEntry
import com.facebook.imageutils.JfifTestUtils.makeTestImageWithAPP1
import com.facebook.imageutils.JfifTestUtils.makeTiff
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tests [JfifUtil] */
@RunWith(RobolectricTestRunner::class)
class JfifUtilTest {
  // Test cases without APP1 block
  private val noOriImage1: String = SOI + APP0 + APP2 + DQT + DHT + DRI + SOF + SOS + EOI
  private val noOriImage2: String = SOI + DQT + DHT + SOF + SOS + EOI

  @Test
  fun testGetOrientation_NoAPP1() {
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(noOriImage1))).isEqualTo(0)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(noOriImage2))).isEqualTo(0)
  }

  @Test
  fun testGetOrientation_SegmentLengthBelowTwo() {
    val app1: String =
        makeAPP1_EXIF(makeTiff(makeIfd(arrayOf(makeOrientationEntry(6, false)), 0, false), false))
    // A COM segment with the given length field, ahead of an APP1 block with orientation 6.
    fun image(comLength: String): ByteArray =
        hexStringToByteArray(SOI + "FFFE" + comLength + app1 + DQT + DHT + SOF + SOS + EOI)
    fun pooledStream(bytes: ByteArray) = PooledByteBufferInputStream(TrivialPooledByteBuffer(bytes))

    assertThat(JfifUtil.getOrientation(image("0002"))).isEqualTo(6)
    assertThat(JfifUtil.getOrientation(pooledStream(image("0002")))).isEqualTo(6)
    for (comLength in listOf("0000", "0001")) {
      assertThat(JfifUtil.getOrientation(image(comLength))).isEqualTo(0)
      assertThat(JfifUtil.getOrientation(pooledStream(image(comLength)))).isEqualTo(0)
    }
  }

  @Test
  fun testGetOrientation_BigEndian() {
    testGetOrientationWithEndian(false)
  }

  @Test
  fun testGetOrientation_LittleEndian() {
    testGetOrientationWithEndian(true)
  }

  private fun testGetOrientationWithEndian(littleEnd: Boolean) {
    val ifdEntry1: String = makeIfdEntry(IFD_ENTRY_TAG_1, TYPE_SHORT, 1, 255, 2, littleEnd)
    val ifdEntry2: String = makeIfdEntry(IFD_ENTRY_TAG_2, TYPE_SHORT, 1, 255, 2, littleEnd)
    val ifdEntry3: String = makeIfdEntry(IFD_ENTRY_TAG_3, TYPE_SHORT, 1, 255, 2, littleEnd)
    val tiffIfd0: String = makeIfd(arrayOf(ifdEntry1, ifdEntry2, ifdEntry3), 0, littleEnd)
    val tiffIfd1: String =
        makeIfd(arrayOf(makeOrientationEntry(1, littleEnd), ifdEntry1, ifdEntry2), 0, littleEnd)
    val tiffIfd3: String =
        makeIfd(arrayOf(makeOrientationEntry(3, littleEnd), ifdEntry1, ifdEntry2), 0, littleEnd)
    val tiffIfd6a: String =
        makeIfd(arrayOf(makeOrientationEntry(6, littleEnd), ifdEntry1, ifdEntry2), 0, littleEnd)
    val tiffIfd6b: String =
        makeIfd(arrayOf(ifdEntry1, makeOrientationEntry(6, littleEnd), ifdEntry2), 0, littleEnd)
    val tiffIfd6c: String =
        makeIfd(arrayOf(ifdEntry1, ifdEntry2, makeOrientationEntry(6, littleEnd)), 0, littleEnd)
    val tiffIfd8: String =
        makeIfd(arrayOf(makeOrientationEntry(8, littleEnd), ifdEntry1, ifdEntry2), 0, littleEnd)

    val app10: String = makeAPP1_EXIF(makeTiff(tiffIfd0, littleEnd))
    val app11: String = makeAPP1_EXIF(makeTiff(tiffIfd1, littleEnd))
    val app13: String = makeAPP1_EXIF(makeTiff(tiffIfd3, littleEnd))
    val app16a: String = makeAPP1_EXIF(makeTiff(tiffIfd6a, littleEnd))
    val app16b: String = makeAPP1_EXIF(makeTiff(tiffIfd6b, littleEnd))
    val app16c: String = makeAPP1_EXIF(makeTiff(tiffIfd6c, littleEnd))
    val app18: String = makeAPP1_EXIF(makeTiff(tiffIfd8, littleEnd))

    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app10))))
        .isEqualTo(0)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app11))))
        .isEqualTo(1)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app13))))
        .isEqualTo(3)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app16a))))
        .isEqualTo(6)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app16b))))
        .isEqualTo(6)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app16c))))
        .isEqualTo(6)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(makeTestImageWithAPP1(app18))))
        .isEqualTo(8)

    testGetOrientationVariousApp1Location(app13, 3)
  }

  private fun testGetOrientationVariousApp1Location(app1: String, expectedOrientation: Int) {
    val imageWithStruct1: String = SOI + app1 + DQT + DHT + SOF + SOS + EOI
    val imageWithStruct2: String = SOI + DQT + app1 + DHT + SOF + SOS + EOI
    val imageWithStruct3: String = SOI + DQT + DHT + app1 + DRI + SOF + SOS + EOI
    val imageWithStruct4: String = SOI + DQT + DHT + DRI + app1 + SOF + SOS + EOI
    val imageWithStruct5: String = SOI + DQT + DHT + DRI + SOF + app1 + SOS + EOI
    val imageWithStruct6: String = SOI + DQT + DHT + SOF + app1 + SOS + EOI
    val imageWithStruct7: String = SOI + APP0 + APP2 + app1 + DQT + DHT + DRI + SOF + SOS + EOI
    val imageWithStruct8: String = SOI + APP0 + app1 + APP2 + DQT + DHT + DRI + SOF + SOS + EOI
    val imageWithStruct9: String = SOI + app1 + APP2 + DQT + DHT + DRI + SOF + SOS + EOI
    val imageWithStruct10: String = SOI + app1 + SOS + EOI

    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct1)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct2)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct3)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct4)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct5)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct6)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct7)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct8)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct9)))
        .isEqualTo(expectedOrientation)
    assertThat(JfifUtil.getOrientation(hexStringToByteArray(imageWithStruct10)))
        .isEqualTo(expectedOrientation)
  }
}
