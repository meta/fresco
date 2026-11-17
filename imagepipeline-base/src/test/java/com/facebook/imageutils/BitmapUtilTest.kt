/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imageutils

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Pair
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Tests [BitmapUtil] */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BitmapUtilTest {

  @Test
  fun testGetSizeInBytes() {
    // 0 for null
    assertThat(BitmapUtil.getSizeInBytes(null)).isEqualTo(0)

    // 240 * 181 * 4 = 173760
    val bitmap1 =
        BitmapFactory.decodeStream(BitmapUtilTest::class.java.getResourceAsStream("pngs/1.png"))
    assertThat(BitmapUtil.getSizeInBytes(bitmap1)).isEqualTo(173760)

    // 240 * 246 * 4 = 236160
    val bitmap2 =
        BitmapFactory.decodeStream(BitmapUtilTest::class.java.getResourceAsStream("pngs/2.png"))
    assertThat(BitmapUtil.getSizeInBytes(bitmap2)).isEqualTo(236160)

    // 240 * 180 * 4 = 172800
    val bitmap3 =
        BitmapFactory.decodeStream(BitmapUtilTest::class.java.getResourceAsStream("pngs/3.png"))
    assertThat(BitmapUtil.getSizeInBytes(bitmap3)).isEqualTo(172800)
  }

  @Test
  fun testDecodeDimensionsUri_test() {
    assertThat(BitmapUtil.decodeDimensions(Uri.parse("pngs/1.png"))).isEqualTo(Pair(100, 100))
    assertThat(BitmapUtil.decodeDimensions(Uri.parse("jpegs/1.jpeg"))).isEqualTo(Pair(100, 100))
  }

  @Test
  fun testDecodeDimensions_testPngs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("pngs/1.png"),
        ),
    )
        .isEqualTo(Pair(240, 181))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("pngs/2.png"),
        ),
    )
        .isEqualTo(Pair(240, 246))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("pngs/3.png"),
        ),
    )
        .isEqualTo(Pair(240, 180))
  }

  @Test
  fun testDecodeDimensions_testJpegs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/1.jpeg"),
        ),
    )
        .isEqualTo(Pair(240, 181))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/2.jpeg"),
        ),
    )
        .isEqualTo(Pair(240, 93))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/3.jpeg"),
        ),
    )
        .isEqualTo(Pair(240, 240))
  }

  @Test
  fun testDecodeDimensions_testIncompleteJpegs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/1cut.jpeg"),
        ),
    )
        .isEqualTo(Pair(240, 181))
  }

  @Test
  fun testDecodeDimensions_testProgressiveJpegs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/1prog.jpeg"),
        ),
    )
        .isEqualTo(Pair(981, 657))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("jpegs/2prog.jpeg"),
        ),
    )
        .isEqualTo(Pair(800, 531))
  }

  @Test
  fun testDecodeDimensions_testStaticGifs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("gifs/1.gif"),
        ),
    )
        .isEqualTo(Pair(240, 181))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("gifs/2.gif"),
        ),
    )
        .isEqualTo(Pair(240, 246))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("gifs/3.gif"),
        ),
    )
        .isEqualTo(Pair(240, 180))
  }

  @Test
  fun testDecodeDimensions_testAnimatedGifs() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("animatedgifs/1.gif"),
        ),
    )
        .isEqualTo(Pair(500, 500))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("animatedgifs/2.gif"),
        ),
    )
        .isEqualTo(Pair(550, 400))
  }

  @Test
  fun testDecodeDimensions_testBmps() {
    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("bmps/1.bmp"),
        ),
    )
        .isEqualTo(Pair(240, 181))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("bmps/2.bmp"),
        ),
    )
        .isEqualTo(Pair(240, 246))

    assertThat(
        BitmapUtil.decodeDimensions(
            BitmapUtilTest::class.java.getResourceAsStream("bmps/3.bmp"),
        ),
    )
        .isEqualTo(Pair(240, 180))
  }
}
