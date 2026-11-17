/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.core;

import android.content.Context;
import android.os.Build;
import com.facebook.cache.common.CacheKey;
import com.facebook.cache.disk.FileCache;
import com.facebook.common.internal.AndroidPredicates;
import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.common.logging.FLog;
import com.facebook.common.memory.PooledByteBuffer;
import com.facebook.common.references.CloseableReference;
import com.facebook.imageformat.DefaultImageFormats;
import com.facebook.imageformat.ImageFormat;
import com.facebook.imageformat.ImageFormatChecker;
import com.facebook.imagepipeline.animated.factory.AnimatedFactory;
import com.facebook.imagepipeline.animated.factory.AnimatedFactoryProvider;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactoryProvider;
import com.facebook.imagepipeline.cache.BitmapMemoryCacheFactory;
import com.facebook.imagepipeline.cache.CountingMemoryCache;
import com.facebook.imagepipeline.cache.EncodedCountingMemoryCacheFactory;
import com.facebook.imagepipeline.cache.EncodedMemoryCacheFactory;
import com.facebook.imagepipeline.cache.InstrumentedMemoryCache;
import com.facebook.imagepipeline.cache.InstrumentedMemoryCacheBitmapMemoryCacheFactory;
import com.facebook.imagepipeline.cache.MemoryCache;
import com.facebook.imagepipeline.decoder.DefaultImageDecoder;
import com.facebook.imagepipeline.decoder.ImageDecoder;
import com.facebook.imagepipeline.drawable.DrawableFactory;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.platform.PlatformDecoder;
import com.facebook.imagepipeline.platform.PlatformDecoderFactory;
import com.facebook.imagepipeline.producers.ExperimentalThreadHandoffProducerQueueImpl;
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueue;
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueueImpl;
import com.facebook.imagepipeline.systrace.FrescoSystrace;
import com.facebook.imagepipeline.transcoder.ImageTranscoder;
import com.facebook.imagepipeline.transcoder.ImageTranscoderFactory;
import com.facebook.imagepipeline.transcoder.MultiImageTranscoderFactory;
import com.facebook.imagepipeline.transcoder.SimpleImageTranscoderFactory;
import com.facebook.imagepipeline.xml.XmlDrawableFactory;
import com.facebook.imagepipeline.xml.XmlFormatDecoder;
import com.facebook.infer.annotation.Nullsafe;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Factory class for the image pipeline.
 *
 * <p>This class constructs the pipeline and its dependencies from other libraries.
 *
 * <p>As the pipeline object can be quite expensive to create, it is strongly recommended that
 * applications create just one instance of this class and of the pipeline.
 */
@NotThreadSafe
@Nullsafe(Nullsafe.Mode.LOCAL)
public class ImagePipelineFactory {

  private static final Class<?> TAG = ImagePipelineFactory.class;

  private static @Nullable ImagePipelineFactory sInstance = null;
  private static @Nullable ImagePipeline sImagePipeline;
  private static @Nullable ImagePipeline sImagePipelineOverride;
  private final ThreadHandoffProducerQueue mThreadHandoffProducerQueue;
  private static boolean sForceSingleInstance;
  private static boolean sResetImagePipelineWhenFactoryChanges;

  /** Gets the instance of {@link ImagePipelineFactory}. */
  public static ImagePipelineFactory getInstance() {
    return Preconditions.checkNotNull(sInstance, "ImagePipelineFactory was not initialized!");
  }

  /** Gets the instance of {@link ImagePipelineFactory}. */
  @Nullable
  public static ImagePipelineFactory getInstanceOrNull() {
    return sInstance;
  }

  /** Returns the registered pipeline, including one supplied without a factory or config. */
  @Nullable
  public static synchronized ImagePipeline getImagePipelineOrNull() {
    if (sImagePipelineOverride != null) {
      return sImagePipelineOverride;
    }
    return sInstance == null ? null : sInstance.getImagePipeline();
  }

  /**
   * Registers a pipeline without constructing a factory and returns the active pipeline. Access the
   * pipeline through {@link #getImagePipelineOrNull()}; this does not initialize a factory.
   * Consumers that require a factory, such as Drawee's default setup, are not supported. Replacing
   * a directly registered pipeline clears its memory caches.
   */
  public static synchronized ImagePipeline initialize(ImagePipeline imagePipeline) {
    Preconditions.checkNotNull(imagePipeline);
    if (sForceSingleInstance && hasImagePipeline()) {
      return Preconditions.checkNotNull(getImagePipelineOrNull());
    }
    if (sImagePipelineOverride != imagePipeline) {
      clearImagePipelineOverride();
    }
    sInstance = null;
    sImagePipeline = null;
    sImagePipelineOverride = imagePipeline;
    return imagePipeline;
  }

  /**
   * Overrides current instance with a new one. Usually used when dealing with multiple
   * ImagePipelineFactories
   *
   * @param newInstance
   */
  public static synchronized void setInstance(ImagePipelineFactory newInstance) {
    clearImagePipelineOverride();
    sInstance = newInstance;
  }

  /** Initializes {@link ImagePipelineFactory} with default config. */
  public static synchronized void initialize(Context context) {
    if (sForceSingleInstance && sInstance != null) {
      return;
    }
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.beginSection("ImagePipelineFactory#initialize");
    }
    initialize(ImagePipelineConfig.newBuilder(context).build());
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.endSection();
    }
  }

  /** Initializes {@link ImagePipelineFactory} with the specified config. */
  public static synchronized void initialize(ImagePipelineConfigInterface imagePipelineConfig) {
    if (sInstance != null) {
      FLog.wtf(
          TAG,
          "ImagePipelineFactory has already been initialized!"
              + " `ImagePipelineFactory.initialize(...)` should only be called once to avoid"
              + " unexpected behavior.");
      if (sForceSingleInstance) {
        return;
      }
    }

    sInstance = new ImagePipelineFactory(imagePipelineConfig);
    clearImagePipelineOverride();
    if (sResetImagePipelineWhenFactoryChanges) {
      sImagePipeline = null;
    }
  }

  public static synchronized void forceSingleInstance() {
    sForceSingleInstance = true;
  }

  public static synchronized void forceSingleInstance(boolean forceSingleInstance) {
    sForceSingleInstance = forceSingleInstance;
  }

  public static synchronized void resetImagePipelineWhenFactoryChanges(
      boolean resetImagePipelineWhenFactoryChanges) {
    sResetImagePipelineWhenFactoryChanges = resetImagePipelineWhenFactoryChanges;
  }

  /** Checks whether a factory is available through {@link #getInstance()}. */
  public static synchronized boolean hasBeenInitialized() {
    return sInstance != null;
  }

  /** Checks whether a pipeline is available through {@link #getImagePipelineOrNull()}. */
  public static synchronized boolean hasImagePipeline() {
    return sInstance != null || sImagePipelineOverride != null;
  }

  /** Shuts {@link ImagePipelineFactory} down. */
  public static synchronized void shutDown() {
    clearImagePipelineOverride();
    if (sInstance != null) {
      sInstance.getBitmapMemoryCache().removeAll(AndroidPredicates.<CacheKey>True());
      sInstance.getEncodedMemoryCache().removeAll(AndroidPredicates.<CacheKey>True());
      sInstance = null;
    }
  }

  private static void clearImagePipelineOverride() {
    if (sImagePipelineOverride != null) {
      sImagePipelineOverride.clearMemoryCaches();
      sImagePipelineOverride = null;
    }
  }

  private final ImagePipelineConfigInterface mConfig;
  private final CloseableReferenceFactory mCloseableReferenceFactory;
  private final Supplier<DiskCachesStore> mDiskCachesStoreSupplier;
  @Nullable private CountingMemoryCache<CacheKey, CloseableImage> mBitmapCountingMemoryCache;
  @Nullable private InstrumentedMemoryCache<CacheKey, CloseableImage> mBitmapMemoryCache;

  @Nullable private CountingMemoryCache<CacheKey, PooledByteBuffer> mEncodedCountingMemoryCache;
  @Nullable private InstrumentedMemoryCache<CacheKey, PooledByteBuffer> mEncodedMemoryCache;
  @Nullable private ImageDecoder mImageDecoder;
  @Nullable private ImageTranscoderFactory mImageTranscoderFactory;
  @Nullable private ProducerFactory mProducerFactory;
  @Nullable private ProducerSequenceFactoryInterface mProducerSequenceFactory;
  @Nullable private PlatformBitmapFactory mPlatformBitmapFactory;
  @Nullable private PlatformDecoder mPlatformDecoder;

  @Nullable private AnimatedFactory mAnimatedFactory;

  public ImagePipelineFactory(ImagePipelineConfigInterface config) {
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.beginSection("ImagePipelineConfig()");
    }
    mConfig = Preconditions.checkNotNull(config);
    mThreadHandoffProducerQueue =
        mConfig.getExperiments().isExperimentalThreadHandoffQueueEnabled()
            ? new ExperimentalThreadHandoffProducerQueueImpl(
                config.getExecutorSupplier().forLightweightBackgroundTasks())
            : new ThreadHandoffProducerQueueImpl(
                config.getExecutorSupplier().forLightweightBackgroundTasks());
    mCloseableReferenceFactory =
        new CloseableReferenceFactory(config.getCloseableReferenceLeakTracker());
    if (FrescoSystrace.isTracing()) {
      FrescoSystrace.endSection();
    }
    mDiskCachesStoreSupplier = mConfig.getDiskCachesStoreSupplier();
    if (mConfig.getExperiments().isBinaryXmlEnabled()) {
      ImageFormatChecker.getInstance().setBinaryXmlEnabled(true);
    }
  }

  @Nullable
  private AnimatedFactory getAnimatedFactory() {
    if (mAnimatedFactory == null) {
      mAnimatedFactory =
          AnimatedFactoryProvider.getAnimatedFactory(
              getPlatformBitmapFactory(),
              mConfig.getExecutorSupplier(),
              getBitmapCountingMemoryCache(),
              mConfig.getExperiments().getDownscaleFrameToDrawableDimensions(),
              mConfig.getExperiments().getUseBalancedAnimationStrategy(),
              mConfig.getExperiments().getAnimationRenderFpsLimit(),
              mConfig.getExperiments().getAnimationStrategyBufferLengthMilliseconds(),
              mConfig.getExecutorServiceForAnimatedImages(),
              false, // enableBufferFrameLoaderFix
              false, // enableSingleFrameRendering
              mConfig.getExperiments().getEnableUnusedFrameLoaderCleanupSync(),
              mConfig.getExperiments().getEnableUnusedFrameLoaderCleanupSyncAndClear());
    }
    return mAnimatedFactory;
  }

  @Nullable
  public DrawableFactory getAnimatedDrawableFactory(@Nullable Context context) {
    AnimatedFactory animatedFactory = this.getAnimatedFactory();
    return animatedFactory == null ? null : animatedFactory.getAnimatedDrawableFactory(context);
  }

  public CountingMemoryCache<CacheKey, CloseableImage> getBitmapCountingMemoryCache() {
    if (mBitmapCountingMemoryCache == null) {
      BitmapMemoryCacheFactory bitmapMemoryCacheFactory = mConfig.getBitmapMemoryCacheFactory();
      if (isNoopWithNonGcSafeFactory(bitmapMemoryCacheFactory)) {
        FLog.wtf(
            TAG,
            "Bitmap refs are REF_TYPE_NOOP but bitmap cache factory %s is not GC-safe; its "
                + "counting cache will never evict under NOOP -> unbounded growth/OOM.",
            bitmapMemoryCacheFactory.getClass().getName());
      }
      mBitmapCountingMemoryCache =
          bitmapMemoryCacheFactory.create(
              mConfig.getBitmapMemoryCacheParamsSupplier(),
              mConfig.getMemoryTrimmableRegistry(),
              mConfig.getBitmapMemoryCacheTrimStrategy(),
              mConfig.getExperiments().getShouldStoreCacheEntrySize(),
              mConfig.getExperiments().getShouldIgnoreCacheSizeMismatch(),
              mConfig.getBitmapMemoryCacheEntryStateObserver());
    }
    return mBitmapCountingMemoryCache;
  }

  /**
   * Returns true when bitmap {@link CloseableReference}s are in {@link
   * CloseableReference#REF_TYPE_NOOP} mode while the configured bitmap cache factory is NOT
   * GC-safe.
   *
   * <p>This is a dangerous combination: a non-GC-safe (counting) cache evicts by waiting for the
   * client reference count to drop to zero, but under NOOP refs that count never reaches zero, so
   * the cache would never evict, leading to unbounded growth / OOM. Extracted as a static helper so
   * the predicate can be unit-tested directly and reused by FB4A config code that needs to enforce
   * the same invariant at cache-factory selection time.
   */
  public static boolean isNoopWithNonGcSafeFactory(
      BitmapMemoryCacheFactory bitmapMemoryCacheFactory) {
    return CloseableReference.getBitmapCloseableRefType() == CloseableReference.REF_TYPE_NOOP
        && !bitmapMemoryCacheFactory.isGcSafe();
  }

  public InstrumentedMemoryCache<CacheKey, CloseableImage> getBitmapMemoryCache() {
    if (mBitmapMemoryCache == null) {
      MemoryCache<CacheKey, CloseableImage> backingCache = getBitmapCountingMemoryCache();
      mBitmapMemoryCache =
          InstrumentedMemoryCacheBitmapMemoryCacheFactory.get(
              backingCache, mConfig.getImageCacheStatsTracker());
    }
    return mBitmapMemoryCache;
  }

  public CountingMemoryCache<CacheKey, PooledByteBuffer> getEncodedCountingMemoryCache() {
    if (mEncodedCountingMemoryCache == null) {
      mEncodedCountingMemoryCache =
          EncodedCountingMemoryCacheFactory.get(
              mConfig.getEncodedMemoryCacheParamsSupplier(),
              mConfig.getMemoryTrimmableRegistry(),
              mConfig.getEncodedMemoryCacheTrimStrategy());
    }
    return mEncodedCountingMemoryCache;
  }

  public InstrumentedMemoryCache<CacheKey, PooledByteBuffer> getEncodedMemoryCache() {
    if (mEncodedMemoryCache == null) {
      MemoryCache<CacheKey, PooledByteBuffer> backingCache =
          mConfig.getEncodedMemoryCacheOverride() != null
              ? mConfig.getEncodedMemoryCacheOverride()
              : getEncodedCountingMemoryCache();
      mEncodedMemoryCache =
          EncodedMemoryCacheFactory.get(backingCache, mConfig.getImageCacheStatsTracker());
    }
    return mEncodedMemoryCache;
  }

  private ImageDecoder getImageDecoder() {
    if (mImageDecoder == null) {
      if (mConfig.getImageDecoder() != null) {
        mImageDecoder = mConfig.getImageDecoder();
      } else {
        ImageDecoder xmlDecoder = getXmlImageDecoder();

        if (mConfig.getImageDecoderConfig() == null) {
          Map<ImageFormat, ImageDecoder> allDecoders = new HashMap<>();
          addAnimatedDecoders(allDecoders);
          mImageDecoder =
              new DefaultImageDecoder(
                  xmlDecoder,
                  getPlatformDecoder(),
                  allDecoders,
                  mConfig.getDefaultIntermediateImageBitmapTransformation());
        } else {
          Map<ImageFormat, ImageDecoder> allDecoders = new HashMap<>();
          addAnimatedDecoders(allDecoders);
          if (mConfig.getImageDecoderConfig().getCustomImageDecoders() != null) {
            allDecoders.putAll(mConfig.getImageDecoderConfig().getCustomImageDecoders());
          }

          mImageDecoder =
              new DefaultImageDecoder(
                  xmlDecoder,
                  getPlatformDecoder(),
                  allDecoders,
                  mConfig.getDefaultIntermediateImageBitmapTransformation());
          // Add custom image formats if needed
          ImageFormatChecker.getInstance()
              .setCustomImageFormatCheckers(
                  mConfig.getImageDecoderConfig().getCustomImageFormats());
        }
      }
    }
    return mImageDecoder;
  }

  public Supplier<DiskCachesStore> getDiskCachesStoreSupplier() {
    return mDiskCachesStoreSupplier;
  }

  public FileCache getMainFileCache() {
    return mDiskCachesStoreSupplier.get().getMainFileCache();
  }

  public FileCache getSmallImageFileCache() {
    return mDiskCachesStoreSupplier.get().getSmallImageFileCache();
  }

  public ImagePipeline getImagePipeline() {
    if (sImagePipeline == null) {
      sImagePipeline = createImagePipeline();
    }
    return sImagePipeline;
  }

  private ImagePipeline createImagePipeline() {
    return new ImagePipeline(
        getProducerSequenceFactory(),
        mConfig.getRequestListeners(),
        mConfig.getRequestListener2s(),
        mConfig.isPrefetchEnabledSupplier(),
        getBitmapMemoryCache(),
        getEncodedMemoryCache(),
        mDiskCachesStoreSupplier,
        mConfig.getCacheKeyFactory(),
        mThreadHandoffProducerQueue,
        mConfig.getExperiments().getSuppressBitmapPrefetchingSupplier(),
        mConfig.getExperiments().isLazyDataSource(),
        mConfig.getCallerContextVerifier(),
        mConfig);
  }

  public PlatformBitmapFactory getPlatformBitmapFactory() {
    if (mPlatformBitmapFactory == null) {
      mPlatformBitmapFactory =
          PlatformBitmapFactoryProvider.buildPlatformBitmapFactory(
              mConfig.getPoolFactory(), getPlatformDecoder(), getCloseableReferenceFactory());
    }
    return mPlatformBitmapFactory;
  }

  public PlatformDecoder getPlatformDecoder() {
    if (mPlatformDecoder == null) {
      mPlatformDecoder =
          PlatformDecoderFactory.buildPlatformDecoder(
              mConfig.getPoolFactory(),
              mConfig.getExperiments().getShouldUseDecodingBufferHelper(),
              mConfig.getExperiments().getPlatformDecoderOptions());
    }
    return mPlatformDecoder;
  }

  private ProducerFactory getProducerFactory() {
    if (mProducerFactory == null) {
      mProducerFactory =
          mConfig
              .getExperiments()
              .getProducerFactoryMethod()
              .createProducerFactory(
                  mConfig.getContext(),
                  mConfig.getPoolFactory().getSmallByteArrayPool(),
                  getImageDecoder(),
                  mConfig.getProgressiveJpegConfig(),
                  mConfig.getDownsampleMode(),
                  mConfig.isResizeAndRotateEnabledForNetwork(),
                  mConfig.getExperiments().isDecodeCancellationEnabled(),
                  mConfig.getExecutorSupplier(),
                  mConfig.getPoolFactory().getPooledByteBufferFactory(mConfig.getMemoryChunkType()),
                  mConfig.getPoolFactory().getPooledByteStreams(),
                  getBitmapMemoryCache(),
                  getEncodedMemoryCache(),
                  mDiskCachesStoreSupplier,
                  mConfig.getCacheKeyFactory(),
                  getPlatformBitmapFactory(),
                  mConfig.getExperiments().getBitmapPrepareToDrawMinSizeBytes(),
                  mConfig.getExperiments().getBitmapPrepareToDrawMaxSizeBytes(),
                  mConfig.getExperiments().getBitmapPrepareToDrawForPrefetch(),
                  mConfig.getExperiments().getMaxBitmapDimension(),
                  getCloseableReferenceFactory(),
                  mConfig.getExperiments().getKeepCancelledFetchAsLowPriority(),
                  mConfig.getExperiments().getTrackedKeysSize(),
                  mConfig);
    }
    return mProducerFactory;
  }

  private ProducerSequenceFactoryInterface getProducerSequenceFactory() {
    ProducerSequenceFactoryInterface configuredFactory = mConfig.getProducerSequenceFactory();
    if (configuredFactory != null) {
      return configuredFactory;
    }
    // before Android N the Bitmap#prepareToDraw method is no-op so do not need this
    final boolean useBitmapPrepareToDraw =
        Build.VERSION.SDK_INT >= 24 // Build.VERSION_CODES.NOUGAT
            && mConfig.getExperiments().getUseBitmapPrepareToDraw();

    if (mProducerSequenceFactory == null) {
      mProducerSequenceFactory =
          new ProducerSequenceFactory(
              mConfig.getContext().getApplicationContext().getContentResolver(),
              getProducerFactory(),
              mConfig.getNetworkFetcher(),
              mConfig.isResizeAndRotateEnabledForNetwork(),
              mThreadHandoffProducerQueue,
              mConfig.getDownsampleMode(),
              useBitmapPrepareToDraw,
              mConfig.getExperiments().isPartialImageCachingEnabled(),
              mConfig.isDiskCacheEnabled(),
              getImageTranscoderFactory(),
              mConfig.getExperiments().isEncodedMemoryCacheProbingEnabled(),
              mConfig.getExperiments().isDiskCacheProbingEnabled(),
              mConfig.getExperiments().getAllowDelay(),
              mConfig.getCustomProducerSequenceFactories(),
              mConfig.getExperiments().getAllowCustomNetworkSequences(),
              mConfig.getExperiments().getThrottlingProducerMaxSimultaneousRequests(),
              mConfig.getExperiments().getLoadThumbnailFromContentResolverFirst(),
              mConfig.getExperiments().getLoadThumbnailFromContentResolverForContentUriOnly(),
              mConfig.getExperiments().getUsePostprocessorDuringDecodedPrefetch(),
              mConfig.getExperiments().getCustomBasicDecodedImageSequenceFactory());
    }
    return mProducerSequenceFactory;
  }

  public CloseableReferenceFactory getCloseableReferenceFactory() {
    return mCloseableReferenceFactory;
  }

  /**
   * Defines the correct {@link ImageTranscoder}. If a custom {@link ImageTranscoder} was define in
   * the config, it will be used whenever possible. Else, if the native code is disabled it uses
   * {@link SimpleImageTranscoderFactory}. Otherwise {@link MultiImageTranscoderFactory} determines
   * the {@link ImageTranscoder} to use based on the image format.
   *
   * @return The {@link ImageTranscoderFactory}
   */
  private ImageTranscoderFactory getImageTranscoderFactory() {
    if (mImageTranscoderFactory == null) {
      if (mConfig.getImageTranscoderFactory() == null
          && mConfig.getImageTranscoderType() == null
          && mConfig.getExperiments().isNativeCodeDisabled()) {
        mImageTranscoderFactory =
            new SimpleImageTranscoderFactory(mConfig.getExperiments().getMaxBitmapDimension());
      } else {
        mImageTranscoderFactory =
            new MultiImageTranscoderFactory(
                mConfig.getExperiments().getMaxBitmapDimension(),
                mConfig.getExperiments().getUseDownsamplingRatioForResizing(),
                mConfig.getImageTranscoderFactory(),
                mConfig.getImageTranscoderType(),
                mConfig.getExperiments().isEnsureTranscoderLibraryLoaded());
      }
    }
    return mImageTranscoderFactory;
  }

  @Nullable
  public String reportData() {
    Objects.ToStringHelper b = Objects.toStringHelper("ImagePipelineFactory");
    if (mBitmapCountingMemoryCache != null) {
      b.add("bitmapCountingMemoryCache", mBitmapCountingMemoryCache.getDebugData());
    }
    if (mEncodedCountingMemoryCache != null) {
      b.add("encodedCountingMemoryCache", mEncodedCountingMemoryCache.getDebugData());
    }
    return b.toString();
  }

  @Nullable
  public ImageDecoder getXmlImageDecoder() {
    if (mConfig.getExperiments().isBinaryXmlEnabled()) {
      return new XmlFormatDecoder(mConfig.getContext().getApplicationContext().getResources());
    } else {
      return null;
    }
  }

  @Nullable
  public DrawableFactory getXmlDrawableFactory() {
    if (mConfig.getExperiments().isBinaryXmlEnabled()) {
      return new XmlDrawableFactory();
    } else {
      return null;
    }
  }

  /**
   * Adds animated decoders for GIF and WebP formats to the provided map if the native libraries are
   * available.
   */
  private void addAnimatedDecoders(Map<ImageFormat, ImageDecoder> decoders) {
    // Register GIF decoder using reflection
    try {
      Class<?> clazz = Class.forName("com.facebook.animated.gif.GifImageDecoder");
      Constructor<?> constructor =
          clazz.getConstructor(
              PlatformBitmapFactory.class,
              java.lang.Boolean.TYPE,
              java.lang.Boolean.TYPE,
              java.lang.Boolean.TYPE);
      ImageDecoder gifDecoder =
          (ImageDecoder)
              constructor.newInstance(
                  getPlatformBitmapFactory(),
                  mConfig.getExperiments().getUseBalancedAnimationStrategy(),
                  mConfig.getExperiments().getDownscaleFrameToDrawableDimensions(),
                  true);
      decoders.put(DefaultImageFormats.GIF, gifDecoder); // Add to unified map of decoders
    } catch (ClassNotFoundException e) {
      // GIF support not available
    } catch (Exception e) {
      FLog.w(TAG, "Failed to instantiate GIF decoder via reflection", e);
    }

    try {
      // Register WebP decoder using reflection
      Class<?> clazz = Class.forName("com.facebook.animated.webp.WebPImageDecoder");
      Constructor<?> constructor =
          clazz.getConstructor(
              PlatformBitmapFactory.class,
              java.lang.Boolean.TYPE,
              java.lang.Boolean.TYPE,
              java.lang.Boolean.TYPE);
      ImageDecoder webpDecoder =
          (ImageDecoder)
              constructor.newInstance(
                  getPlatformBitmapFactory(),
                  mConfig.getExperiments().getUseBalancedAnimationStrategy(),
                  mConfig.getExperiments().getDownscaleFrameToDrawableDimensions(),
                  true);
      decoders.put(
          DefaultImageFormats.WEBP_ANIMATED, webpDecoder); // Add to unified map of decoders
    } catch (ClassNotFoundException e) {
      // WebP support not available
    } catch (Exception e) {
      FLog.w(TAG, "Failed to instantiate WebP decoder via reflection", e);
    }
  }
}
