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
import java.util.concurrent.Executor

interface FrescoVitoConfig {
  val prefetchConfig: PrefetchConfig

  fun submitFetchOnBgThread(): Boolean

  fun useBind(): Boolean

  fun useMount(): Boolean

  fun useUnmount(): Boolean

  fun useUnbind(): Boolean

  fun useDetached(): Boolean

  fun onUnbindReleaseStrategy(): ReleaseStrategy

  fun onUnmountReleaseStrategy(): ReleaseStrategy

  fun onDetachedReleaseStrategy(): ReleaseStrategy

  fun releaseDelayMs(): Long

  fun useBindOnly(): Boolean

  fun useNewReleaseCallback(): Boolean

  fun preservePendingReleaseOnImageUpdate(): Boolean = false

  fun useNativeRounding(): Supplier<Boolean>?

  fun layoutPrefetchingEnabled(callerContext: Any?): Boolean

  fun useSmartPropertyDiffing(): Boolean

  fun onlyStopAnimationWhenAutoPlayEnabled(): Boolean

  fun fastPathForEmptyRequests(): Boolean

  /**
   * When on, controllers honor [EmptyImageSource.isExpectedEmpty] and route "expected empty"
   * requests through the `onEmptyEvent` success-with-no-image callback path instead of firing
   * `onFailure` with `"No image request was specified!"`. Default off preserves legacy failure
   * semantics for all callers.
   */
  fun handleExpectedEmptyImageSource(): Boolean = false

  fun enableWindowWideColorGamut(): Boolean

  fun handleImageResultInBackground(): Boolean

  fun useIntermediateImagesAsPlaceholder(): Boolean

  fun fallbackToDefaultImageOptions(): Boolean

  fun experimentalDynamicSizeVito2(): Boolean

  fun experimentalDynamicSizeBloksDisableDiskCacheCheck(): Boolean

  fun experimentalDynamicSizeWithCacheFallbackVito2(): Boolean

  fun experimentalDynamicSizeOnPrepareMainThreadVito2(): Boolean

  fun experimentalDynamicSizeDiskCacheCheckTimeoutMs(): Long

  fun experimentalDynamicSizeUseSfOnDiskCacheTimeout(): Boolean

  fun isAppStarting(): Boolean

  fun experimentalDynamicSizeDisableWhenAppIsStarting(): Boolean

  fun experimentalDynamicSizeCheckIfProductIsEnabled(): Boolean

  fun experimentalDynamicSizeIsProductEnabled(
      callerContext: Any?,
      contextChain: ContextChain?,
  ): Boolean

  fun experimentalDynamicSizeIsFallbackEnabled(
      callerContext: Any?,
      contextChain: ContextChain?,
  ): Boolean

  fun isCallerContextBloks(callerContext: Any?): Boolean

  fun experimentalResetVitoImageRequestListener(): Boolean

  fun experimentalResetLocalVitoImageRequestListener(): Boolean

  fun experimentalResetLocalImagePerfStateListener(): Boolean

  fun experimentalResetControllerListener2(): Boolean

  fun experimentalDynamicSizeIsUriEligible(uri: Uri?): Boolean

  fun enablePrepareToDrawOnFetch(): Boolean

  fun experimentalOptimizeAlphaHandling(): Boolean

  fun enableRetriggerListenersIfImageAlreadySet(): Boolean

  fun fixOnBindRetriggerListenersClobber(): Boolean = false

  fun disableBitmapCacheShortcut(): Boolean = false

  /**
   * When on, an image is released immediately when its view becomes non-visible (e.g. scrolled off
   * screen) instead of on the next frame. Releasing sooner drops the drawable's strong reference to
   * the underlying bitmap earlier, making it eligible for GC while scrolling rather than only after
   * the view detaches. Trade-off: an item that scrolls off and quickly back triggers a re-fetch
   * (usually a memory-cache hit). Default off; gated behind an A/B flag.
   */
  fun releaseImageOnVisibilityGoneImmediately(): Boolean = false

  /** Prevents draw passes from using an image after its owning reference is closed. */
  fun fixClearActualImageLayerOnClose(): Boolean = false

  /**
   * Assigns the Kotlin drawable's fetch data source through a synchronized, imageId-guarded setter
   * (FrescoController2Impl parity) instead of an unguarded background-thread write, so a stale
   * fetch cannot overwrite and cancel a newer in-flight fetch.
   */
  fun fixKotlinDataSourceRace(): Boolean = false

  /**
   * Forwards `onFadeListener` to the Kotlin drawable (FrescoController2Impl parity) instead of
   * dropping it, firing started/finished around actual-image fades and shown-immediately for
   * direct, cached, and error images.
   */
  fun fixKotlinOnFadeListener(): Boolean = false

  /**
   * Implements `getActualImageBounds` on the Kotlin drawable (FrescoController2Impl parity) instead
   * of throwing `UnsupportedOperationException`, mapping the actual-image model rect through the
   * layer transform.
   */
  fun fixKotlinActualImageBounds(): Boolean = false

  /** Clears the placeholder layer when the actual image is set with a fade duration of 0. */
  fun fixResetPlaceholderOnZeroFade(): Boolean = false

  /**
   * Starts a Compose painter's fetch when the painter is created instead of when it is remembered.
   */
  fun fetchInVitoPainterInit(): Boolean = false

  fun enableComposeImagePathFixes(): Boolean = false

  /**
   * Executor used to create a Compose painter's image request away from the main thread. A null
   * executor preserves synchronous request creation.
   *
   * This only applies when [fetchInVitoPainterInit] is enabled. Drawable attachment, controller
   * fetch, and Compose state updates still run on the main thread.
   */
  fun composeImageRequestExecutor(): Executor? = null

  /**
   * Releases a Compose painter's drawable via [FrescoController2.releaseDelayed] instead of
   * [FrescoController2.releaseImmediately] when the painter is forgotten. The delayed path
   * completes through `releaseNextFrame`, so it requires both
   * `ImageReleaseScheduler.enableReleaseDelayed` and `ImageReleaseScheduler.enableReleaseNextFrame`
   * — with either off the release is dropped and the drawable is never reset.
   */
  fun useReleaseDelayedInCompose(): Boolean = false

  /**
   * Skips writing the Compose painter's `drawableIntrinsicSize` snapshot state when the drawable's
   * intrinsic size has not actually changed. For a `KFrescoVitoDrawable` it never changes — nothing
   * calls `setIntrinsicSize`, so it stays `Size.Unspecified` — which makes every write on
   * `invalidateDrawable` redundant.
   */
  fun skipRedundantIntrinsicSizeWrites(): Boolean = false

  /**
   * Enables layout-based prefetch telemetry: captures the origin and duration of the prefetch that
   * populated the cache, and annotates them onto the image load event.
   */
  fun enablePrefetchTelemetry(): Boolean = false
}
