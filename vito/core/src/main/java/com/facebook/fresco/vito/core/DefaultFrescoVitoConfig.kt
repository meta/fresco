/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.core

import android.net.Uri
import com.facebook.common.callercontext.ContextChain
import com.facebook.common.internal.Supplier
import com.facebook.common.internal.Suppliers
import kotlin.Long

open class DefaultFrescoVitoConfig
@JvmOverloads
constructor(override val prefetchConfig: PrefetchConfig = DefaultPrefetchConfig()) :
    FrescoVitoConfig {

  override fun submitFetchOnBgThread(): Boolean = true

  override fun useBind(): Boolean = true

  override fun useMount(): Boolean = true

  override fun useUnbind(): Boolean = true

  override fun useDetached(): Boolean = false

  override fun onUnbindReleaseStrategy(): ReleaseStrategy = ReleaseStrategy.DELAYED

  override fun onUnmountReleaseStrategy(): ReleaseStrategy = ReleaseStrategy.NEXT_FRAME

  override fun onDetachedReleaseStrategy(): ReleaseStrategy = ReleaseStrategy.NEXT_FRAME

  override fun releaseDelayMs(): Long = 16 * 5L // Roughly 5 frames.

  override fun useUnmount(): Boolean = true

  override fun useBindOnly(): Boolean = false

  override fun useNewReleaseCallback(): Boolean = false

  override fun useNativeRounding(): Supplier<Boolean>? = Suppliers.BOOLEAN_TRUE

  override fun layoutPrefetchingEnabled(callerContext: Any?): Boolean = false

  override fun useSmartPropertyDiffing(): Boolean = false

  override fun onlyStopAnimationWhenAutoPlayEnabled(): Boolean = true

  override fun fastPathForEmptyRequests(): Boolean = false

  override fun handleExpectedEmptyImageSource(): Boolean = false

  override fun enableWindowWideColorGamut(): Boolean = false

  override fun handleImageResultInBackground(): Boolean = false

  override fun useIntermediateImagesAsPlaceholder(): Boolean = false

  override fun fallbackToDefaultImageOptions(): Boolean = false

  override fun experimentalDynamicSizeVito2(): Boolean = false

  override fun experimentalDynamicSizeWithCacheFallbackVito2(): Boolean = false

  override fun experimentalDynamicSizeOnPrepareMainThreadVito2(): Boolean = false

  override fun experimentalDynamicSizeDiskCacheCheckTimeoutMs(): Long = 0

  override fun experimentalDynamicSizeUseSfOnDiskCacheTimeout(): Boolean = false

  override fun isAppStarting(): Boolean = false

  override fun experimentalDynamicSizeDisableWhenAppIsStarting(): Boolean = false

  override fun experimentalDynamicSizeCheckIfProductIsEnabled(): Boolean = false

  override fun experimentalDynamicSizeIsProductEnabled(
      callerContext: Any?,
      contextChain: ContextChain?,
  ): Boolean = true

  override fun experimentalDynamicSizeIsFallbackEnabled(
      callerContext: Any?,
      contextChain: ContextChain?,
  ): Boolean = true

  override fun experimentalDynamicSizeBloksDisableDiskCacheCheck(): Boolean = false

  override fun experimentalDynamicSizeIsUriEligible(uri: Uri?): Boolean = true

  override fun experimentalResetVitoImageRequestListener(): Boolean = false

  override fun experimentalResetLocalVitoImageRequestListener(): Boolean = false

  override fun experimentalResetLocalImagePerfStateListener(): Boolean = false

  override fun experimentalResetControllerListener2(): Boolean = false

  override fun isCallerContextBloks(callerContext: Any?): Boolean = false

  override fun enablePrepareToDrawOnFetch(): Boolean = false

  override fun experimentalOptimizeAlphaHandling(): Boolean = false

  override fun enableRetriggerListenersIfImageAlreadySet(): Boolean = false

  override fun fixOnBindRetriggerListenersClobber(): Boolean = false

  override fun disableBitmapCacheShortcut(): Boolean = false

  override fun releaseImageOnVisibilityGoneImmediately(): Boolean = false

  override fun fixClearActualImageLayerOnClose(): Boolean = false

  override fun fixKotlinDataSourceRace(): Boolean = false

  override fun fixKotlinOnFadeListener(): Boolean = false

  override fun fixKotlinActualImageBounds(): Boolean = false

  override fun fixResetPlaceholderOnZeroFade(): Boolean = false

  override fun useReleaseDelayedInCompose(): Boolean = false

  override fun skipRedundantIntrinsicSizeWrites(): Boolean = false

  override fun enablePrefetchTelemetry(): Boolean = false

  open class DefaultPrefetchConfig() : PrefetchConfig {
    override fun prefetchInOnPrepare(): Boolean = true

    override fun prefetchInOnBoundsDefinedForDynamicSize(): Boolean = false

    override fun prefetchTargetOnPrepare(): PrefetchTarget = PrefetchTarget.MEMORY_DECODED

    override fun prefetchTargetOnBoundsDefined(): PrefetchTarget = PrefetchTarget.MEMORY_DECODED

    override fun closePrefetchDataSourceOnBindorOnMount(): Boolean = true

    override fun closePrefetchDataSourceOnUnbind(): Boolean = true

    override fun closePrefetchDataSourceOnUnmount(): Boolean = true

    override fun closePrefetchDataSourceOnDetached(): Boolean = false
  }
}
