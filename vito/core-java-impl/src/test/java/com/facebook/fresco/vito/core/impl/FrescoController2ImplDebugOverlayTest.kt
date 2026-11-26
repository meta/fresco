/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.core.VitoImageRequestListener
import com.facebook.fresco.vito.core.impl.debug.DebugOverlayFactory2
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ColorImageSource
import java.util.concurrent.Executor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrescoController2ImplDebugOverlayTest {

  @Test
  fun testDebugOverlayFactory_whenReplaced_thenTheNewFactoryIsUpdated() {
    val initial = mutableListOf<FrescoDrawable2>()
    val replacement = mutableListOf<FrescoDrawable2>()
    val controller = createController(DebugOverlayFactory2 { drawable, _ -> initial.add(drawable) })
    val drawable = controller.createDrawable<FrescoDrawable2Impl>("unit-test")

    controller.debugOverlayFactory = DebugOverlayFactory2 { d, _ -> replacement.add(d) }
    controller.fetch(
        drawable = drawable,
        imageRequest =
            VitoImageRequest(
                resources = Resources.getSystem(),
                imageSource = ColorImageSource(Color.RED),
                imageOptions = ImageOptions.defaults(),
                finalImageRequest = null,
                finalImageCacheKey = null,
            ),
        callerContext = "FrescoController2ImplDebugOverlayTest",
        contextChain = null,
        listener = null,
        onFadeListener = null,
        viewportDimensions = null,
    )

    assertThat(initial).isEmpty()
    assertThat(replacement).containsExactly(drawable)
    drawable.close()
  }

  @Test
  fun testGlobalImageListener_whenSetAfterCreation_thenLaterFetchesNotifyIt() {
    val controller = createController(DebugOverlayFactory2 { _, _ -> })
    val listener = mock<VitoImageRequestListener>()
    val drawable = controller.createDrawable<FrescoDrawable2Impl>("unit-test")

    controller.globalImageListener = listener
    controller.fetch(
        drawable = drawable,
        imageRequest =
            VitoImageRequest(
                resources = Resources.getSystem(),
                imageSource = ColorImageSource(Color.RED),
                imageOptions = ImageOptions.defaults(),
                finalImageRequest = null,
                finalImageCacheKey = null,
            ),
        callerContext = "FrescoController2ImplDebugOverlayTest",
        contextChain = null,
        listener = null,
        onFadeListener = null,
        viewportDimensions = null,
    )

    verify(listener).onSubmit(any(), any(), anyOrNull(), anyOrNull())
    drawable.close()
  }

  private fun createController(factory: DebugOverlayFactory2): FrescoController2Impl {
    val hierarcher = mock<Hierarcher>()
    whenever(hierarcher.applyRoundingOptions(any(), any(), any())).thenAnswer {
      it.arguments[1] as Drawable
    }
    return FrescoController2Impl(
        config = DefaultFrescoVitoConfig(),
        hierarcher = hierarcher,
        lightweightBackgroundThreadExecutor = Executor { it.run() },
        uiThreadExecutor = Executor { it.run() },
        imagePipeline = mock<VitoImagePipeline>(),
        globalImageListener = null,
        debugOverlayFactory = factory,
        imagePerfListenerSupplier = null,
        vitoImagePerfListener = BaseVitoImagePerfListener(),
    )
  }
}
