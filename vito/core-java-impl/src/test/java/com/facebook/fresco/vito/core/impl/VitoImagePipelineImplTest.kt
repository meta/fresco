/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core.impl

import android.graphics.Rect
import android.net.Uri
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.references.CloseableReference
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.fresco.urimod.ClassicFetchStrategy
import com.facebook.fresco.urimod.Dimensions
import com.facebook.fresco.urimod.FetchStrategy
import com.facebook.fresco.urimod.NoPrefetchInOnPrepareStrategy
import com.facebook.fresco.urimod.SmartFetchStrategy
import com.facebook.fresco.urimod.UriModifier
import com.facebook.fresco.urimod.UriModifierInterface
import com.facebook.fresco.urimod.UriModifierInterface.ModificationResult
import com.facebook.fresco.vito.core.FrescoVitoConfig
import com.facebook.fresco.vito.core.ImagePipelineUtils
import com.facebook.fresco.vito.core.VitoImageRequest
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.SingleImageSourceImpl
import com.facebook.fresco.vito.source.SmartFetchOptIn
import com.facebook.fresco.vito.source.SmartImageSource
import com.facebook.imagepipeline.core.ImagePipeline
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.request.ImageRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VitoImagePipelineImplTest {

  private val imagePipeline: ImagePipeline = mock()
  private val imagePipelineUtils: ImagePipelineUtils = mock()
  private val config: FrescoVitoConfig = mock()
  private val callerContext: Any = Object()
  private val contextChain: ContextChain = mock()

  private lateinit var vitoImagePipeline: VitoImagePipelineImpl

  private val testUri = Uri.parse("https://scontent.xx.fbcdn.net/image.jpg?cstp=1")
  private val imageRequest: ImageRequest = mock()

  @Before
  fun setup() {
    vitoImagePipeline = VitoImagePipelineImpl(imagePipeline, imagePipelineUtils, config)
    whenever(imageRequest.sourceUri).thenReturn(testUri)
  }

  private fun createRequest(
      imageSource: com.facebook.fresco.vito.source.ImageSource,
  ): VitoImageRequest {
    val resources = mock<android.content.res.Resources>()
    return VitoImageRequest(
        resources,
        imageSource,
        com.facebook.fresco.vito.options.ImageOptions.defaults(),
        finalImageRequest = imageRequest,
        finalImageCacheKey = null,
    )
  }

  // --- Null request tests ---

  @Test
  fun getCachedImage_bitmapCacheShortcutDisabled_returnsNull() {
    whenever(config.disableBitmapCacheShortcut()).thenReturn(true)

    val result = vitoImagePipeline.getCachedImage(createRequest(SingleImageSourceImpl(testUri)))

    assertThat(result).isNull()
  }

  @Test
  fun getCachedImage_bitmapCacheShortcutEnabled_returnsPipelineCacheHit() {
    whenever(config.disableBitmapCacheShortcut()).thenReturn(false)
    val cachedImageReference = CloseableReference.of(mock<CloseableImage>())
    whenever(imagePipeline.getCachedImage(anyOrNull())).thenReturn(cachedImageReference)

    try {
      val result = vitoImagePipeline.getCachedImage(createRequest(SingleImageSourceImpl(testUri)))

      assertThat(result).isSameAs(cachedImageReference)
    } finally {
      CloseableReference.closeSafely(cachedImageReference)
    }
  }

  @Test
  fun determineFetchStrategy_nullRequest_dynamicSizeEnabled_returnsSmartDefault() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(true)

    val result = vitoImagePipeline.determineFetchStrategy(null, callerContext, contextChain)

    assertThat(result).isEqualTo(SmartFetchStrategy.DEFAULT)
  }

  @Test
  fun determineFetchStrategy_nullRequest_dynamicSizeDisabled_returnsNoPrefetch() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(false)

    val result = vitoImagePipeline.determineFetchStrategy(null, callerContext, contextChain)

    assertThat(result).isEqualTo(NoPrefetchInOnPrepareStrategy)
  }

  // --- App disabled test ---

  @Test
  fun determineFetchStrategy_appDisabled_returnsAppDisabled() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(false)

    val request = createRequest(SmartImageSource(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.APP_DISABLED)
  }

  // --- URI ineligible test ---

  @Test
  fun determineFetchStrategy_uriIneligible_returnsUriIneligible() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsUriEligible(any())).thenReturn(false)

    val request = createRequest(SmartImageSource(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.URI_INELIGIBLE)
  }

  // --- Product disabled (MC-based, no opt-in) ---

  @Test
  fun determineFetchStrategy_noOptIn_productDisabled_returnsProductDisabled() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeCheckIfProductIsEnabled()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsProductEnabled(any(), anyOrNull())).thenReturn(false)

    val request = createRequest(SmartImageSource(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.PRODUCT_DISABLED)
  }

  // --- SmartFetchOptIn bypasses product check ---

  @Test
  fun determineFetchStrategy_optInNoFallback_bypassesProductCheck_returnsFallbackDisabled() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeCheckIfProductIsEnabled()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsProductEnabled(any(), anyOrNull())).thenReturn(false)
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
  }

  @Test
  fun determineFetchStrategy_optInWithFallback_bypassesProductCheck_continuesFlow() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeCheckIfProductIsEnabled()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsProductEnabled(any(), anyOrNull())).thenReturn(false)
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeDisableWhenAppIsStarting()).thenReturn(false)
    whenever(config.experimentalDynamicSizeBloksDisableDiskCacheCheck()).thenReturn(false)
    whenever(config.isCallerContextBloks(any())).thenReturn(false)
    whenever(config.experimentalDynamicSizeDiskCacheCheckTimeoutMs()).thenReturn(0L)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_WITH_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    // With fallback enabled + not in disk cache → SmartFetchStrategy.DEFAULT
    assertThat(result).isNotEqualTo(ClassicFetchStrategy.PRODUCT_DISABLED)
    assertThat(result).isNotEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
  }

  // --- Fallback check with opt-in ---

  @Test
  fun determineFetchStrategy_optInNoFallback_ignoresMcFallbackSetting() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    // MC says fallback IS enabled, but opt-in overrides to no fallback
    whenever(config.experimentalDynamicSizeIsFallbackEnabled(any(), anyOrNull())).thenReturn(true)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
  }

  @Test
  fun determineFetchStrategy_optInWithFallback_ignoresMcFallbackSetting() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    // MC says fallback is NOT enabled, but opt-in overrides to with fallback
    whenever(config.experimentalDynamicSizeIsFallbackEnabled(any(), anyOrNull())).thenReturn(false)
    whenever(config.experimentalDynamicSizeDisableWhenAppIsStarting()).thenReturn(false)
    whenever(config.experimentalDynamicSizeBloksDisableDiskCacheCheck()).thenReturn(false)
    whenever(config.isCallerContextBloks(any())).thenReturn(false)
    whenever(config.experimentalDynamicSizeDiskCacheCheckTimeoutMs()).thenReturn(0L)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_WITH_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isNotEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
  }

  // --- No opt-in uses MC fallback setting ---

  @Test
  fun determineFetchStrategy_noOptIn_mcFallbackDisabled_returnsFallbackDisabled() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsFallbackEnabled(any(), anyOrNull())).thenReturn(false)

    val request = createRequest(SmartImageSource(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
  }

  // --- Non-SmartImageSource uses MC path ---

  @Test
  fun determineFetchStrategy_nonSmartImageSource_productDisabled_returnsProductDisabled() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeCheckIfProductIsEnabled()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsProductEnabled(any(), anyOrNull())).thenReturn(false)

    val request = createRequest(SingleImageSourceImpl(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.PRODUCT_DISABLED)
  }

  // --- Safety checks still apply with opt-in ---

  @Test
  fun determineFetchStrategy_optIn_appDisabled_stillReturnsAppDisabled() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(false)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.APP_DISABLED)
  }

  @Test
  fun determineFetchStrategy_optIn_uriIneligible_stillReturnsUriIneligible() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsUriEligible(any())).thenReturn(false)

    val request = createRequest(
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK),
    )
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    assertThat(result).isEqualTo(ClassicFetchStrategy.URI_INELIGIBLE)
  }

  // --- Default backward compat (null opt-in) ---

  @Test
  fun determineFetchStrategy_nullOptIn_productEnabled_fallbackEnabled_mainThread_returnsMainThread() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsFallbackEnabled(any(), anyOrNull())).thenReturn(true)
    whenever(config.experimentalDynamicSizeOnPrepareMainThreadVito2()).thenReturn(true)

    val request = createRequest(SmartImageSource(testUri))
    val result = vitoImagePipeline.determineFetchStrategy(request, callerContext, contextChain)

    // Robolectric runs on main thread, so main thread check kicks in
    assertThat(result).isEqualTo(SmartFetchStrategy.MAIN_THREAD)
  }

  // --- createImageRequestForViewport ---

  @Test
  fun createImageRequestForViewport_optInNoFallback_sizesUriForViewportWithFallbackDisabled() {
    enableSmartFetchDefaults()
    whenever(config.experimentalDynamicSizeWithCacheFallbackVito2()).thenReturn(true)
    val sizedUri = Uri.parse("https://scontent.xx.fbcdn.net/image.jpg?cstp=1&ctp=s1080x1080")
    val uriModifier: UriModifierInterface = mock()
    whenever(uriModifier.modifyUri(any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
        .thenReturn(ModificationResult.Modified.ModifiedToAllowlistedSize(sizedUri, 1080))
    val defaultUriModifier = UriModifier.INSTANCE
    UriModifier.INSTANCE = uriModifier
    try {
      val request =
          vitoImagePipeline.createImageRequestForViewport(
              mock(),
              SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK),
              ImageOptions.defaults(),
              Rect(0, 0, 1080, 748),
              callerContext,
              contextChain,
          )

      assertThat(request.getExtra<FetchStrategy>(HasExtraData.KEY_SF_FETCH_STRATEGY))
          .isEqualTo(SmartFetchStrategy.FALLBACK_DISABLED)
      assertThat(request.viewport).isEqualTo(Dimensions(1080, 748))
      val imageSource = request.imageSource as SmartImageSource
      assertThat(imageSource.imageUri).isEqualTo(sizedUri)
      assertThat(imageSource.smartFetchOptIn).isEqualTo(SmartFetchOptIn.ENABLED_NO_FALLBACK)
      verify(uriModifier)
          .modifyUri(
              any(),
              eq(Dimensions(1080, 748)),
              anyOrNull(),
              eq(callerContext),
              eq(contextChain),
          )
    } finally {
      UriModifier.INSTANCE = defaultUriModifier
    }
  }

  @Test
  fun createImageRequestForViewport_appDisabled_keepsImageSourceWithClassicStrategy() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(false)
    val imageSource =
        SmartImageSource(testUri, smartFetchOptIn = SmartFetchOptIn.ENABLED_NO_FALLBACK)

    val request =
        vitoImagePipeline.createImageRequestForViewport(
            mock(),
            imageSource,
            ImageOptions.defaults(),
            Rect(0, 0, 1080, 748),
            callerContext,
            contextChain,
        )

    assertThat(request.getExtra<FetchStrategy>(HasExtraData.KEY_SF_FETCH_STRATEGY))
        .isEqualTo(ClassicFetchStrategy.APP_DISABLED)
    assertThat(request.imageSource).isSameAs(imageSource)
  }

  private fun enableSmartFetchDefaults() {
    whenever(config.experimentalDynamicSizeVito2()).thenReturn(true)
    whenever(config.experimentalDynamicSizeIsUriEligible(anyOrNull())).thenReturn(true)
    whenever(config.experimentalDynamicSizeCheckIfProductIsEnabled()).thenReturn(false)
  }
}
