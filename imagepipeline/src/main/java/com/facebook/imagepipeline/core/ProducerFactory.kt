/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.core

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import androidx.annotation.RequiresApi
import com.facebook.cache.common.CacheKey
import com.facebook.common.internal.Supplier
import com.facebook.common.internal.Suppliers
import com.facebook.common.memory.ByteArrayPool
import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.memory.PooledByteBufferFactory
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.cache.BoundedLinkedHashMap
import com.facebook.imagepipeline.cache.BoundedLinkedHashSet
import com.facebook.imagepipeline.cache.CacheKeyFactory
import com.facebook.imagepipeline.cache.MemoryCache
import com.facebook.imagepipeline.decoder.ImageDecoder
import com.facebook.imagepipeline.decoder.ProgressiveJpegConfig
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.producers.AddImageTransformMetaDataProducer
import com.facebook.imagepipeline.producers.BitmapMemoryCacheGetProducer
import com.facebook.imagepipeline.producers.BitmapMemoryCacheKeyMultiplexProducer
import com.facebook.imagepipeline.producers.BitmapMemoryCacheProducer
import com.facebook.imagepipeline.producers.BitmapPrepareProducer
import com.facebook.imagepipeline.producers.BitmapProbeProducer
import com.facebook.imagepipeline.producers.BranchOnSeparateImagesProducer
import com.facebook.imagepipeline.producers.DataFetchProducer
import com.facebook.imagepipeline.producers.DecodeProducer
import com.facebook.imagepipeline.producers.DelayProducer
import com.facebook.imagepipeline.producers.DiskCacheReadProducer
import com.facebook.imagepipeline.producers.DiskCacheWriteProducer
import com.facebook.imagepipeline.producers.EncodedCacheKeyMultiplexProducer
import com.facebook.imagepipeline.producers.EncodedMemoryCacheProducer
import com.facebook.imagepipeline.producers.EncodedProbeProducer
import com.facebook.imagepipeline.producers.LocalAssetFetchProducer
import com.facebook.imagepipeline.producers.LocalContentUriFetchProducer
import com.facebook.imagepipeline.producers.LocalContentUriThumbnailFetchProducer
import com.facebook.imagepipeline.producers.LocalExifThumbnailProducer
import com.facebook.imagepipeline.producers.LocalFileFetchProducer
import com.facebook.imagepipeline.producers.LocalResourceFetchProducer
import com.facebook.imagepipeline.producers.LocalThumbnailBitmapSdk29Producer
import com.facebook.imagepipeline.producers.LocalVideoThumbnailProducer
import com.facebook.imagepipeline.producers.NetworkFetchProducer
import com.facebook.imagepipeline.producers.NetworkFetcher
import com.facebook.imagepipeline.producers.PartialDiskCacheProducer
import com.facebook.imagepipeline.producers.PostprocessedBitmapMemoryCacheProducer
import com.facebook.imagepipeline.producers.PostprocessorProducer
import com.facebook.imagepipeline.producers.Producer
import com.facebook.imagepipeline.producers.QualifiedResourceFetchProducer
import com.facebook.imagepipeline.producers.ResizeAndRotateProducer
import com.facebook.imagepipeline.producers.SwallowResultProducer
import com.facebook.imagepipeline.producers.ThreadHandoffProducer
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueue
import com.facebook.imagepipeline.producers.ThrottlingProducer
import com.facebook.imagepipeline.producers.ThumbnailBranchProducer
import com.facebook.imagepipeline.producers.ThumbnailProducer
import com.facebook.imagepipeline.transcoder.ImageTranscoderFactory

open class ProducerFactory(
    context: Context,
    // Decode dependencies
    @JvmField protected val mByteArrayPool: ByteArrayPool,
    @JvmField protected val mImageDecoder: ImageDecoder,
    @JvmField protected val mProgressiveJpegConfig: ProgressiveJpegConfig,
    @JvmField protected val mDownsampleMode: DownsampleMode,
    @JvmField protected val mResizeAndRotateEnabledForNetwork: Boolean,
    @JvmField protected val mDecodeCancellationEnabled: Boolean,
    // Dependencies used by multiple steps
    @JvmField protected val mExecutorSupplier: ExecutorSupplier,
    pooledByteBufferFactory: PooledByteBufferFactory,
    bitmapMemoryCache: MemoryCache<CacheKey, CloseableImage>,
    encodedMemoryCache: MemoryCache<CacheKey, PooledByteBuffer>,
    diskCachesStoreSupplier: Supplier<DiskCachesStore>,
    cacheKeyFactory: CacheKeyFactory,
    platformBitmapFactory: PlatformBitmapFactory,
    bitmapPrepareToDrawMinSizeBytes: Int,
    bitmapPrepareToDrawMaxSizeBytes: Int,
    bitmapPrepareToDrawForPrefetch: Boolean,
    maxBitmapSize: Int,
    closeableReferenceFactory: CloseableReferenceFactory,
    keepCancelledFetchAsLowPriority: Boolean,
    trackedKeysSize: Int,
    config: ImagePipelineConfigInterface,
) {
  // Local dependencies
  protected var mContentResolver: ContentResolver
  protected var mResources: Resources
  protected var mAssetManager: AssetManager

  protected val mPooledByteBufferFactory: PooledByteBufferFactory

  // Cache dependencies
  protected val mDiskCachesStoreSupplier: Supplier<DiskCachesStore>
  @JvmField protected val mEncodedMemoryCache: MemoryCache<CacheKey, PooledByteBuffer>
  @JvmField protected val mBitmapMemoryCache: MemoryCache<CacheKey, CloseableImage>
  @JvmField protected val mCacheKeyFactory: CacheKeyFactory
  protected val mEncodedMemoryCacheHistory: BoundedLinkedHashSet<CacheKey?>
  protected val mDiskCacheHistory: BoundedLinkedHashSet<CacheKey?>
  protected val mEncodedMemoryCacheMetadata: BoundedLinkedHashMap<CacheKey, String>?

  // Postproc dependencies
  protected val mPlatformBitmapFactory: PlatformBitmapFactory

  // BitmapPrepare dependencies
  protected val mBitmapPrepareToDrawMinSizeBytes: Int
  protected val mBitmapPrepareToDrawMaxSizeBytes: Int
  protected var mBitmapPrepareToDrawForPrefetch: Boolean

  // Core factory dependencies
  @JvmField protected val mCloseableReferenceFactory: CloseableReferenceFactory

  @JvmField protected val mMaxBitmapSize: Int

  protected val mKeepCancelledFetchAsLowPriority: Boolean

  private val config: ImagePipelineConfigInterface

  init {
    mContentResolver = context.getApplicationContext().getContentResolver()
    mResources = context.getApplicationContext().getResources()
    mAssetManager = context.getApplicationContext().getAssets()

    mPooledByteBufferFactory = pooledByteBufferFactory

    mBitmapMemoryCache = bitmapMemoryCache
    mEncodedMemoryCache = encodedMemoryCache
    mDiskCachesStoreSupplier = diskCachesStoreSupplier
    mCacheKeyFactory = cacheKeyFactory
    mPlatformBitmapFactory = platformBitmapFactory
    mEncodedMemoryCacheHistory = BoundedLinkedHashSet<CacheKey?>(trackedKeysSize)
    mDiskCacheHistory = BoundedLinkedHashSet<CacheKey?>(trackedKeysSize)
    mEncodedMemoryCacheMetadata =
        if (config.experiments.preserveMetadataInEncodedMemoryCache) {
          BoundedLinkedHashMap(ENCODED_MEMORY_CACHE_METADATA_SIZE)
        } else {
          null
        }

    mBitmapPrepareToDrawMinSizeBytes = bitmapPrepareToDrawMinSizeBytes
    mBitmapPrepareToDrawMaxSizeBytes = bitmapPrepareToDrawMaxSizeBytes
    mBitmapPrepareToDrawForPrefetch = bitmapPrepareToDrawForPrefetch

    mMaxBitmapSize = maxBitmapSize
    mCloseableReferenceFactory = closeableReferenceFactory

    mKeepCancelledFetchAsLowPriority = keepCancelledFetchAsLowPriority

    this.config = config
  }

  @Deprecated("")
  constructor(
      context: Context?,
      byteArrayPool: ByteArrayPool?,
      imageDecoder: ImageDecoder?,
      progressiveJpegConfig: ProgressiveJpegConfig?,
      downsampleEnabled: Boolean,
      resizeAndRotateEnabledForNetwork: Boolean,
      decodeCancellationEnabled: Boolean,
      executorSupplier: ExecutorSupplier?,
      pooledByteBufferFactory: PooledByteBufferFactory?,
      bitmapMemoryCache: MemoryCache<CacheKey, CloseableImage>?,
      encodedMemoryCache: MemoryCache<CacheKey, PooledByteBuffer>?,
      diskCachesStoreSupplier: Supplier<DiskCachesStore>?,
      cacheKeyFactory: CacheKeyFactory?,
      platformBitmapFactory: PlatformBitmapFactory?,
      bitmapPrepareToDrawMinSizeBytes: Int,
      bitmapPrepareToDrawMaxSizeBytes: Int,
      bitmapPrepareToDrawForPrefetch: Boolean,
      maxBitmapSize: Int,
      closeableReferenceFactory: CloseableReferenceFactory?,
      keepCancelledFetchAsLowPriority: Boolean,
      trackedKeysSize: Int,
      config: ImagePipelineConfigInterface?,
  ) : this(
      context!!,
      byteArrayPool!!,
      imageDecoder!!,
      progressiveJpegConfig!!,
      if (downsampleEnabled) DownsampleMode.ALWAYS else DownsampleMode.AUTO,
      resizeAndRotateEnabledForNetwork,
      decodeCancellationEnabled,
      executorSupplier!!,
      pooledByteBufferFactory!!,
      bitmapMemoryCache!!,
      encodedMemoryCache!!,
      diskCachesStoreSupplier!!,
      cacheKeyFactory!!,
      platformBitmapFactory!!,
      bitmapPrepareToDrawMinSizeBytes,
      bitmapPrepareToDrawMaxSizeBytes,
      bitmapPrepareToDrawForPrefetch,
      maxBitmapSize,
      closeableReferenceFactory!!,
      keepCancelledFetchAsLowPriority,
      trackedKeysSize,
      config!!,
  )

  fun newBitmapMemoryCacheGetProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): BitmapMemoryCacheGetProducer {
    return BitmapMemoryCacheGetProducer(mBitmapMemoryCache, mCacheKeyFactory, inputProducer)
  }

  fun newBitmapMemoryCacheKeyMultiplexProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): BitmapMemoryCacheKeyMultiplexProducer =
      newBitmapMemoryCacheKeyMultiplexProducer(inputProducer, mCacheKeyFactory)

  fun newBitmapMemoryCacheKeyMultiplexProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
      cacheKeyFactory: CacheKeyFactory,
  ): BitmapMemoryCacheKeyMultiplexProducer {
    return BitmapMemoryCacheKeyMultiplexProducer(cacheKeyFactory, inputProducer, config)
  }

  fun newBitmapMemoryCacheProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): BitmapMemoryCacheProducer {
    return BitmapMemoryCacheProducer(mBitmapMemoryCache, mCacheKeyFactory, inputProducer)
  }

  fun newDataFetchProducer(): DataFetchProducer {
    return DataFetchProducer(mPooledByteBufferFactory)
  }

  open fun newDecodeProducer(inputProducer: Producer<EncodedImage>): DecodeProducer {
    return DecodeProducer(
        mByteArrayPool,
        mExecutorSupplier.forDecode(),
        mImageDecoder,
        mProgressiveJpegConfig,
        mDownsampleMode,
        mResizeAndRotateEnabledForNetwork,
        mDecodeCancellationEnabled,
        inputProducer,
        mMaxBitmapSize,
        mCloseableReferenceFactory,
        null,
        Suppliers.BOOLEAN_FALSE,
        null,
    )
  }

  fun newDiskCacheReadProducer(inputProducer: Producer<EncodedImage>): DiskCacheReadProducer {
    return DiskCacheReadProducer(mDiskCachesStoreSupplier, mCacheKeyFactory, inputProducer)
  }

  open fun newDiskCacheWriteProducer(
      inputProducer: Producer<EncodedImage>,
  ): Producer<EncodedImage> {
    return DiskCacheWriteProducer(mDiskCachesStoreSupplier, mCacheKeyFactory, inputProducer)
  }

  fun newPartialDiskCacheProducer(inputProducer: Producer<EncodedImage>): PartialDiskCacheProducer {
    return PartialDiskCacheProducer(
        mDiskCachesStoreSupplier,
        mCacheKeyFactory,
        mPooledByteBufferFactory,
        mByteArrayPool,
        inputProducer,
    )
  }

  open fun newEncodedCacheKeyMultiplexProducer(
      inputProducer: Producer<EncodedImage>,
  ): EncodedCacheKeyMultiplexProducer {
    return EncodedCacheKeyMultiplexProducer(
        mCacheKeyFactory,
        mKeepCancelledFetchAsLowPriority,
        inputProducer,
        config,
    )
  }

  fun newBitmapProbeProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): BitmapProbeProducer {
    return BitmapProbeProducer(
        mEncodedMemoryCache,
        mDiskCachesStoreSupplier,
        mCacheKeyFactory,
        mEncodedMemoryCacheHistory,
        mDiskCacheHistory,
        inputProducer,
    )
  }

  fun newEncodedProbeProducer(inputProducer: Producer<EncodedImage>): EncodedProbeProducer {
    return EncodedProbeProducer(
        mDiskCachesStoreSupplier,
        mCacheKeyFactory,
        mEncodedMemoryCacheHistory,
        mDiskCacheHistory,
        inputProducer,
    )
  }

  open fun newEncodedMemoryCacheProducer(
      inputProducer: Producer<EncodedImage>,
  ): Producer<EncodedImage> {
    return EncodedMemoryCacheProducer(
        mEncodedMemoryCache,
        mCacheKeyFactory,
        inputProducer,
        mEncodedMemoryCacheMetadata,
    )
  }

  fun newLocalAssetFetchProducer(): LocalAssetFetchProducer {
    return LocalAssetFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mAssetManager,
    )
  }

  fun newLocalContentUriFetchProducer(): LocalContentUriFetchProducer {
    return LocalContentUriFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mContentResolver,
    )
  }

  fun newLocalContentUriThumbnailFetchProducer(): LocalContentUriThumbnailFetchProducer {
    return LocalContentUriThumbnailFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mContentResolver,
    )
  }

  fun newLocalExifThumbnailProducer(): LocalExifThumbnailProducer {
    return LocalExifThumbnailProducer(
        mExecutorSupplier.forThumbnailProducer(),
        mPooledByteBufferFactory,
        mContentResolver,
    )
  }

  fun newThumbnailBranchProducer(
      thumbnailProducers: Array<ThumbnailProducer<EncodedImage>>,
  ): ThumbnailBranchProducer {
    return ThumbnailBranchProducer(*thumbnailProducers)
  }

  fun newLocalFileFetchProducer(): LocalFileFetchProducer {
    return LocalFileFetchProducer(mExecutorSupplier.forLocalStorageRead(), mPooledByteBufferFactory)
  }

  fun newQualifiedResourceFetchProducer(): QualifiedResourceFetchProducer {
    return QualifiedResourceFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mContentResolver,
    )
  }

  fun newLocalResourceFetchProducer(): LocalResourceFetchProducer {
    return LocalResourceFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mResources,
    )
  }

  fun newLocalVideoThumbnailProducer(): LocalVideoThumbnailProducer {
    return LocalVideoThumbnailProducer(mExecutorSupplier.forLocalStorageRead(), mContentResolver)
  }

  open fun newNetworkFetchProducer(networkFetcher: NetworkFetcher<*>): Producer<EncodedImage> {
    return NetworkFetchProducer(mPooledByteBufferFactory, mByteArrayPool, networkFetcher)
  }

  fun newPostprocessorBitmapMemoryCacheProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): PostprocessedBitmapMemoryCacheProducer {
    return PostprocessedBitmapMemoryCacheProducer(
        mBitmapMemoryCache,
        mCacheKeyFactory,
        inputProducer,
    )
  }

  fun newPostprocessorProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): PostprocessorProducer {
    return PostprocessorProducer(
        inputProducer,
        mPlatformBitmapFactory,
        mExecutorSupplier.forBackgroundTasks(),
    )
  }

  fun newResizeAndRotateProducer(
      inputProducer: Producer<EncodedImage>,
      isResizingEnabled: Boolean,
      imageTranscoderFactory: ImageTranscoderFactory?,
  ): ResizeAndRotateProducer {
    return ResizeAndRotateProducer(
        mExecutorSupplier.forBackgroundTasks(),
        mPooledByteBufferFactory,
        inputProducer,
        isResizingEnabled,
        imageTranscoderFactory!!,
    )
  }

  fun <T> newSwallowResultProducer(inputProducer: Producer<T>): SwallowResultProducer<T?> {
    return SwallowResultProducer<T?>(inputProducer)
  }

  open fun <T> newBackgroundThreadHandoffProducer(
      inputProducer: Producer<T>,
      inputThreadHandoffProducerQueue: ThreadHandoffProducerQueue,
  ): Producer<T> {
    return ThreadHandoffProducer<T>(inputProducer, inputThreadHandoffProducerQueue)
  }

  fun <T> newThrottlingProducer(
      maxSimultaneousRequests: Long,
      inputProducer: Producer<T>,
  ): ThrottlingProducer<T> {
    return ThrottlingProducer<T>(
        maxSimultaneousRequests,
        mExecutorSupplier.forLightweightBackgroundTasks(),
        inputProducer,
    )
  }

  fun newBitmapPrepareProducer(
      inputProducer: Producer<CloseableReference<CloseableImage>>,
  ): BitmapPrepareProducer {
    return BitmapPrepareProducer(
        inputProducer,
        mBitmapPrepareToDrawMinSizeBytes,
        mBitmapPrepareToDrawMaxSizeBytes,
        mBitmapPrepareToDrawForPrefetch,
    )
  }

  fun newDelayProducer(inputProducer: Producer<CloseableReference<CloseableImage>>): DelayProducer {
    return DelayProducer(
        inputProducer,
        mExecutorSupplier.scheduledExecutorServiceForBackgroundTasks(),
    )
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun newLocalThumbnailBitmapSdk29Producer(
      loadThumbnailFromContentResolverFirst: Boolean?,
      loadThumbnailFromContentResolverForContentUriOnly: Boolean?,
  ): LocalThumbnailBitmapSdk29Producer {
    return LocalThumbnailBitmapSdk29Producer(
        mExecutorSupplier.forBackgroundTasks(),
        mContentResolver,
        loadThumbnailFromContentResolverFirst!!,
        loadThumbnailFromContentResolverForContentUriOnly!!,
    )
  }

  companion object {
    /**
     * The encoded memory cache is bounded by bytes (up to 4MB), not entry count, so this is sized
     * to cover the entries that realistically fit at typical encoded image sizes. Undersizing only
     * costs coverage -- an evicted entry means a blank query, never a wrong one.
     */
    private const val ENCODED_MEMORY_CACHE_METADATA_SIZE = 128

    fun newAddImageTransformMetaDataProducer(
        inputProducer: Producer<EncodedImage>,
    ): AddImageTransformMetaDataProducer {
      return AddImageTransformMetaDataProducer(inputProducer)
    }

    fun newBranchOnSeparateImagesProducer(
        inputProducer1: Producer<EncodedImage>,
        inputProducer2: Producer<EncodedImage>,
    ): BranchOnSeparateImagesProducer {
      return BranchOnSeparateImagesProducer(inputProducer1, inputProducer2)
    }
  }
}
