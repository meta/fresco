/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.samples.showcase

import android.app.Application
import android.content.res.Resources
import androidx.preference.PreferenceManager
import com.facebook.common.executors.UiThreadImmediateExecutorService
import com.facebook.common.internal.Suppliers
import com.facebook.common.logging.FLog
import com.facebook.common.memory.manager.NoOpDebugMemoryManager
import com.facebook.drawee.backends.pipeline.DraweeConfig
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.flipper.android.AndroidFlipperClient
import com.facebook.flipper.android.utils.FlipperUtils
import com.facebook.flipper.perflogger.NoOpFlipperPerfLogger
import com.facebook.flipper.plugins.fresco.FrescoFlipperPlugin
import com.facebook.flipper.plugins.fresco.FrescoFlipperRequestListener
import com.facebook.flipper.plugins.inspector.DescriptorMapping
import com.facebook.flipper.plugins.inspector.InspectorFlipperPlugin
import com.facebook.fresco.samples.showcase.misc.DebugOverlaySupplierSingleton
import com.facebook.fresco.samples.showcase.misc.ImageUriProvider
import com.facebook.fresco.samples.showcase.misc.LogcatRequestListener2
import com.facebook.fresco.samples.showcase.settings.SettingsFragment.KEY_VITO_KOTLIN
import com.facebook.fresco.ui.common.ImageLoadStatus
import com.facebook.fresco.ui.common.ImagePerfData
import com.facebook.fresco.ui.common.ImagePerfDataListener
import com.facebook.fresco.ui.common.VisibilityState
import com.facebook.fresco.vito.core.DefaultFrescoVitoConfig
import com.facebook.fresco.vito.core.FrescoVitoConfig
import com.facebook.fresco.vito.core.impl.DebugOverlayHandler
import com.facebook.fresco.vito.init.FrescoVito
import com.facebook.fresco.vito.provider.impl.NoOpCallerContextVerifier
import com.facebook.fresco.vito.provider.impl.kotlin.KFrescoVitoProvider
import com.facebook.fresco.vito.tools.liveeditor.ImageSelector
import com.facebook.fresco.vito.tools.liveeditor.ImageTracker
import com.facebook.imagepipeline.backends.okhttp3.OkHttpImagePipelineConfigFactory
import com.facebook.imagepipeline.core.ImagePipelineConfig
import com.facebook.imagepipeline.core.ImagePipelineFactory
import com.facebook.imagepipeline.debug.FlipperCacheKeyFactory
import com.facebook.imagepipeline.debug.FlipperImageTracker
import com.facebook.imagepipeline.decoder.SimpleProgressiveJpegConfig
import com.facebook.imagepipeline.listener.ForwardingRequestListener
import com.facebook.imagepipeline.listener.RequestListener
import com.facebook.imagepipeline.listener.RequestLoggingListener
import com.facebook.soloader.SoLoader
import com.facebook.stetho.okhttp3.StethoInterceptor
import okhttp3.OkHttpClient

/** Showcase Application implementation where we set up Fresco */
class ShowcaseApplication : Application() {

  private var frescoFlipperPlugin: FrescoFlipperPlugin? = null

  override fun onCreate() {
    super.onCreate()
    FLog.minimumLoggingLevel = FLog.VERBOSE
    val forwardingRequestListener = ForwardingRequestListener()
    val requestListeners =
        HashSet<RequestListener>().apply {
          add(forwardingRequestListener)
          add(RequestLoggingListener())
        }

    val requestListener2s = setOf(LogcatRequestListener2())

    val okHttpClient = OkHttpClient.Builder().addNetworkInterceptor(StethoInterceptor()).build()

    val imagePipelineConfigBuilder =
        OkHttpImagePipelineConfigFactory.newBuilder(this, okHttpClient)
            .setRequestListeners(requestListeners)
            .setRequestListener2s(requestListener2s)
            .setProgressiveJpegConfig(SimpleProgressiveJpegConfig())
            .setImageDecoderConfig(CustomImageFormatConfigurator.createImageDecoderConfig(this))

    if (shouldEnableFlipper()) {
      imagePipelineConfigBuilder.setCacheKeyFactory(FlipperCacheKeyFactory(sFlipperImageTracker))
    }

    imagePipelineConfigBuilder
        .experiment()
        .setBinaryXmlEnabled(true)
        .setBitmapPrepareToDraw(true, 0, Integer.MAX_VALUE, true)
        .setDownsampleIfLargeBitmap(true)

    val imagePipelineConfig = imagePipelineConfigBuilder.build()
    ImagePipelineConfig.defaultImageRequestConfig.isProgressiveRenderingEnabled = true

    val draweeConfigBuilder = DraweeConfig.newBuilder()
    CustomImageFormatConfigurator.addCustomDrawableFactories(this, draweeConfigBuilder)

    draweeConfigBuilder.setDebugOverlayEnabledSupplier(
        DebugOverlaySupplierSingleton.getInstance(applicationContext),
    )

    if (shouldEnableFlipper()) {
      draweeConfigBuilder.setImagePerfDataListener(
          object : ImagePerfDataListener {
            override fun onImageLoadStatusUpdated(
                imagePerfData: ImagePerfData,
                imageLoadStatus: ImageLoadStatus,
            ) {
              frescoFlipperPlugin
                  ?.flipperImageTracker
                  ?.onImageLoadStatusUpdated(imagePerfData, imageLoadStatus)
              frescoFlipperPlugin?.onImageLoadStatusUpdated(imagePerfData, imageLoadStatus)
            }

            override fun onImageVisibilityUpdated(
                imagePerfData: ImagePerfData,
                visibilityState: VisibilityState,
            ) {
              // nop
            }
          },
      )
    }

    Fresco.initialize(this, imagePipelineConfig, draweeConfigBuilder.build())
    initVito(resources)
    ShowcaseProvider.initIfNeeded(applicationContext)
    val context = this
    DumpappInitializer.initialize(context)

    if (shouldEnableFlipper()) {
      SoLoader.init(this, 0)

      frescoFlipperPlugin =
          FrescoFlipperPlugin(
              sFlipperImageTracker,
              Fresco.getImagePipelineFactory().platformBitmapFactory,
              null,
              NoOpDebugMemoryManager(),
              NoOpFlipperPerfLogger(),
              null,
              null,
          )
      forwardingRequestListener.addRequestListener(
          FrescoFlipperRequestListener(frescoFlipperPlugin!!.flipperImageTracker),
      )
      AndroidFlipperClient.getInstance(context).apply {
        addPlugin(InspectorFlipperPlugin(context, DescriptorMapping.withDefaults()))
        addPlugin(frescoFlipperPlugin)
        start()
      }
    }
  }

  private fun initVito(
      resources: Resources,
      vitoConfig: FrescoVitoConfig = DefaultFrescoVitoConfig(),
  ) {
    if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(KEY_VITO_KOTLIN, false)) {
      FrescoVito.initialize(
          KFrescoVitoProvider(
              vitoConfig,
              ImagePipelineFactory.getInstance().imagePipeline,
              FrescoVito.createImagePipelineUtils(Suppliers.BOOLEAN_TRUE),
              UiThreadImmediateExecutorService.getInstance(),
              ImagePipelineFactory.getInstance()
                  .imagePipeline
                  .config
                  .executorSupplier
                  .forLightweightBackgroundTasks(),
              NoOpCallerContextVerifier,
              DebugOverlayHandler(DebugOverlaySupplierSingleton.getInstance(applicationContext)),
          ),
      )
    } else {
      FrescoVito.initialize(
          vitoConfig = vitoConfig,
          debugOverlayEnabledSupplier =
              DebugOverlaySupplierSingleton.getInstance(applicationContext),
          vitoImagePerfListener = imageTracker,
      )
    }
  }

  private fun shouldEnableFlipper(): Boolean {
    return BuildConfig.DEBUG && FlipperUtils.shouldEnableFlipper(this)
  }

  companion object {
    private val sFlipperImageTracker = FlipperImageTracker()
    val imageTracker: ImageTracker
      get() = ShowcaseProvider.imageTracker

    val imageUriProvider: ImageUriProvider
      get() = ShowcaseProvider.imageUriProvider

    val imageSelector: ImageSelector
      get() = ShowcaseProvider.imageSelector
  }
}
