/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.content.res.Resources
import android.graphics.Color
import android.graphics.Rect
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.datasource.DataSources
import com.facebook.fresco.ui.common.ControllerListener2
import com.facebook.fresco.urimod.Dimensions
import com.facebook.fresco.urimod.FetchStrategy
import com.facebook.fresco.vito.core.BaseVitoImageRequestListener
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.VitoImagePipeline
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.ColorImageSource
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.listener.RequestListener
import java.util.concurrent.Executor
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KFrescoVitoDebugOverlayTest {

  @Test
  fun testController_whenOverlaySet_thenCustomOverlayIsUpdatedForTheDrawable() {
    val updated = mutableListOf<KFrescoVitoDrawable>()
    val controller = createController()
    controller.debugOverlayHandler = VitoDebugOverlay { updated.add(it) }
    val target: KFrescoVitoDrawable = controller.createDrawable()

    controller.fetch(
        target,
        VitoImageRequest(
            RuntimeEnvironment.getApplication().resources,
            ColorImageSource(Color.RED),
            ImageOptions.defaults(),
            finalImageRequest = null,
            finalImageCacheKey = null,
        ),
        callerContext = "test",
        contextChain = null,
        listener = null,
        onFadeListener = null,
        viewportDimensions = null,
        vitoImageRequestListener = null,
    )

    assertThat(updated).containsExactly(target)
  }

  @Test
  fun testController_whenGlobalListenerSetAfterCreation_thenLaterFetchesNotifyIt() {
    val submitted = mutableListOf<Long>()
    val controller = createController()
    val target: KFrescoVitoDrawable = controller.createDrawable()

    controller.globalImageRequestListener =
        object : BaseVitoImageRequestListener() {
          override fun onSubmit(
              id: Long,
              imageRequest: VitoImageRequest,
              callerContext: Any?,
              extras: ControllerListener2.Extras?,
          ) {
            submitted.add(id)
          }
        }
    controller.fetch(
        target,
        VitoImageRequest(
            RuntimeEnvironment.getApplication().resources,
            ColorImageSource(Color.RED),
            ImageOptions.defaults(),
            finalImageRequest = null,
            finalImageCacheKey = null,
        ),
        callerContext = "test",
        contextChain = null,
        listener = null,
        onFadeListener = null,
        viewportDimensions = null,
        vitoImageRequestListener = null,
    )

    assertThat(submitted).containsExactly(target.imageId)
  }

  private fun createController(): KFrescoController {
    val pipeline =
        object : VitoImagePipeline {
          override fun createImageRequest(
              resources: Resources,
              imageSource: ImageSource,
              options: ImageOptions?,
              logWithHighSamplingRate: Boolean,
              viewport: Rect?,
              callerContext: Any?,
              contextChain: ContextChain?,
              fetchStrategy: FetchStrategy?,
          ): VitoImageRequest = throw UnsupportedOperationException()

          override fun getCachedImage(
              imageRequest: VitoImageRequest,
          ): CloseableReference<CloseableImage>? = null

          override fun fetchDecodedImage(
              imageRequest: VitoImageRequest,
              callerContext: Any?,
              requestListener: RequestListener?,
              uiComponentId: Long,
              viewport: Dimensions?,
          ): DataSource<CloseableReference<CloseableImage>> =
              DataSources.immediateFailedDataSource(UnsupportedOperationException())

          override fun isInDiskCacheSync(imageRequest: VitoImageRequest): Boolean = false
        }
    return KFrescoController(
        DefaultFrescoVitoConfig(),
        pipeline,
        Executor { it.run() },
        Executor { it.run() },
    )
  }
}
