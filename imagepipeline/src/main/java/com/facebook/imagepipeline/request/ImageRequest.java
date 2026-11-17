/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request;

import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_DATA;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_LOCAL_ASSET;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_LOCAL_CONTENT;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_LOCAL_IMAGE_FILE;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_LOCAL_RESOURCE;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_LOCAL_VIDEO_FILE;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_NETWORK;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_QUALIFIED_RESOURCE;
import static com.facebook.imagepipeline.common.SourceUriType.SOURCE_TYPE_UNKNOWN;

import android.net.Uri;
import android.os.Build;
import androidx.annotation.IntDef;
import com.facebook.cache.common.CacheKey;
import com.facebook.common.internal.Fn;
import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.media.MediaUtils;
import com.facebook.common.util.UriUtil;
import com.facebook.imagepipeline.common.BytesRange;
import com.facebook.imagepipeline.common.ImageDecodeOptions;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.common.SourceUriType;
import com.facebook.imagepipeline.core.DownsampleMode;
import com.facebook.imagepipeline.listener.RequestListener;
import com.facebook.imageutils.BitmapUtil;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.memory.helper.HashCode;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Immutable object encapsulating everything pipeline has to know about requested image to proceed.
 */
@Immutable
@Nullsafe(Nullsafe.Mode.LOCAL)
public class ImageRequest {

  private static boolean sUseCachedHashcodeInEquals;
  private static boolean sCacheHashcode;
  private int mHashcode;

  /** Cache choice */
  private final CacheChoice mCacheChoice;

  /** Source Uri */
  private final Uri mSourceUri;

  private final @SourceUriType int mSourceUriType;

  /** Source File - for local fetches only, lazily initialized */
  @Nullable private File mSourceFile;

  /** If set - the client will receive intermediate results */
  private final boolean mProgressiveRenderingEnabled;

  /**
   * If set - the client will not return the partial images stored in caches that store encoded
   * images and will attempt to make a network request where feasible to fetch the rest of the
   * image.
   */
  private final boolean mSkipEncodedPartialImagesInCachesNotInRequestRange;

  /** If set the client will receive thumbnail previews for local images, before the whole image */
  private final boolean mLocalThumbnailPreviewsEnabled;

  /** If set, only the image thumbnail will be loaded, not the full image */
  private final boolean mLoadThumbnailOnly;

  private final ImageDecodeOptions mImageDecodeOptions;

  /** resize options */
  private final @Nullable ResizeOptions mResizeOptions;

  /** rotation options */
  private final RotationOptions mRotationOptions;

  /** Range of bytes to request from the network */
  private final @Nullable BytesRange mBytesRange;

  /** Priority levels of this request. */
  private final Priority mRequestPriority;

  /** Lowest level that is permitted to fetch an image from */
  private final RequestLevel mLowestPermittedRequestLevel;

  /**
   * int in which each bit represents read or write permission of each cache from bitmap read bit
   * (rightest) to disk write bit
   */
  protected int mCachesDisabled;

  /** Whether the disk cache should be used for this request */
  private final boolean mIsDiskCacheEnabled;

  /** Whether the memory cache should be used for this request */
  private final boolean mIsMemoryCacheEnabled;

  /**
   * Whether to decode prefetched images. true -> Cache both encoded image and bitmap. false ->
   * Cache only encoded image and do not decode until image is needed to be shown. null -> Use
   * pipeline's default
   */
  private final @Nullable Boolean mDecodePrefetches;

  /** Postprocessor to run on the output bitmap. */
  private final @Nullable Postprocessor mPostprocessor;

  /** Request listener to use for this image request */
  private final @Nullable RequestListener mRequestListener;

  /**
   * Controls whether resizing is allowed for this request. true -> allow for this request. false ->
   * disallow for this request. null -> use default pipeline's setting.
   */
  private final @Nullable Boolean mResizingAllowedOverride;

  /** Custom downsample override for this request. null -> use default pipeline's setting. */
  private final @Nullable DownsampleMode mDownsampleOverride;

  private final @Nullable String mDiskCacheId;

  private final @Nullable String mCustomCacheKey;

  private final int mDelayMs;

  /** Whether to extract first frame thumbnail from video. */
  private final Boolean mIsFirstFrameThumbnailEnabled;

  /** App-defined data carried on this request, null if there is none. Fresco does not read it. */
  private final @Nullable Map<ImageRequestExtra<?>, Object> mExtras;

  public static @Nullable ImageRequest fromFile(@Nullable File file) {
    return (file == null) ? null : ImageRequest.fromUri(UriUtil.getUriForFile(file));
  }

  public static @Nullable ImageRequest fromUri(@Nullable Uri uri) {
    return (uri == null) ? null : ImageRequestBuilder.newBuilderWithSource(uri).build();
  }

  public static @Nullable ImageRequest fromUri(@Nullable String uriString) {
    return (uriString == null || uriString.length() == 0) ? null : fromUri(Uri.parse(uriString));
  }

  protected ImageRequest(ImageRequestBuilder builder) {
    mCacheChoice = builder.getCacheChoice();
    mSourceUri = builder.getSourceUri();
    mSourceUriType = getSourceUriType(mSourceUri);

    mProgressiveRenderingEnabled = builder.isProgressiveRenderingEnabled();
    mSkipEncodedPartialImagesInCachesNotInRequestRange =
        builder.getSkipEncodedPartialImagesInCachesNotInRequestRange();
    mLocalThumbnailPreviewsEnabled = builder.isLocalThumbnailPreviewsEnabled();
    mLoadThumbnailOnly = builder.getLoadThumbnailOnly();

    mImageDecodeOptions = builder.getImageDecodeOptions();

    mResizeOptions = builder.getResizeOptions();
    mRotationOptions =
        builder.getRotationOptions() == null
            ? RotationOptions.autoRotate()
            : builder.getRotationOptions();
    mBytesRange = builder.getBytesRange();

    mRequestPriority = builder.getRequestPriority();
    mLowestPermittedRequestLevel = builder.getLowestPermittedRequestLevel();

    mIsDiskCacheEnabled = builder.isDiskCacheEnabled();

    int cachesDisabledFlags = builder.getCachesDisabled();
    if (!mIsDiskCacheEnabled) {
      // If disk cache is disabled we must make sure mCachesDisabled reflects it
      cachesDisabledFlags |= CachesLocationsMasks.DISK_READ | CachesLocationsMasks.DISK_WRITE;
    }
    mCachesDisabled = cachesDisabledFlags;

    mIsMemoryCacheEnabled = builder.isMemoryCacheEnabled();
    mDecodePrefetches = builder.shouldDecodePrefetches();

    mPostprocessor = builder.getPostprocessor();

    mRequestListener = builder.getRequestListener();

    mResizingAllowedOverride = builder.getResizingAllowedOverride();

    mDownsampleOverride = builder.getDownsampleOverride();

    mDelayMs = builder.getDelayMs();

    mDiskCacheId = builder.getDiskCacheId();

    mCustomCacheKey = builder.getCustomCacheKey();

    mIsFirstFrameThumbnailEnabled = builder.getIsFirstFrameThumbnailEnabled();

    Map<ImageRequestExtra<?>, Object> extras = builder.getExtras();
    mExtras = extras == null || extras.isEmpty() ? null : new HashMap<>(extras);
  }

  public CacheChoice getCacheChoice() {
    return mCacheChoice;
  }

  public Uri getSourceUri() {
    return mSourceUri;
  }

  public @SourceUriType int getSourceUriType() {
    return mSourceUriType;
  }

  public int getPreferredWidth() {
    return (mResizeOptions != null) ? mResizeOptions.width : (int) BitmapUtil.MAX_BITMAP_DIMENSION;
  }

  public int getPreferredHeight() {
    return (mResizeOptions != null) ? mResizeOptions.height : (int) BitmapUtil.MAX_BITMAP_DIMENSION;
  }

  public @Nullable ResizeOptions getResizeOptions() {
    return mResizeOptions;
  }

  public RotationOptions getRotationOptions() {
    return mRotationOptions;
  }

  /**
   * @deprecated Use {@link #getRotationOptions()}
   */
  @Deprecated
  public boolean getAutoRotateEnabled() {
    return mRotationOptions.useImageMetadata();
  }

  @Nullable
  public BytesRange getBytesRange() {
    return mBytesRange;
  }

  public ImageDecodeOptions getImageDecodeOptions() {
    return mImageDecodeOptions;
  }

  public boolean getProgressiveRenderingEnabled() {
    return mProgressiveRenderingEnabled;
  }

  public boolean getSkipEncodedPartialImagesInCachesNotInRequestRange() {
    return mSkipEncodedPartialImagesInCachesNotInRequestRange;
  }

  public boolean getLocalThumbnailPreviewsEnabled() {
    return mLocalThumbnailPreviewsEnabled;
  }

  public boolean getLoadThumbnailOnlyForAndroidSdkAboveQ() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mLoadThumbnailOnly;
  }

  public ImageRequest newImageRequestFromImageRequestBuilder(ImageRequestBuilder builder) {
    return builder.build();
  }

  public Priority getPriority() {
    return mRequestPriority;
  }

  public RequestLevel getLowestPermittedRequestLevel() {
    return mLowestPermittedRequestLevel;
  }

  public int getCachesDisabled() {
    return mCachesDisabled;
  }

  public boolean isDiskCacheEnabled() {
    return mIsDiskCacheEnabled;
  }

  /** Returns whether the use of the cache is enabled for read or write according to given mask. */
  public boolean isCacheEnabled(int cacheMask) {
    return (getCachesDisabled() & cacheMask) == 0;
  }

  public boolean isMemoryCacheEnabled() {
    return mIsMemoryCacheEnabled;
  }

  public @Nullable Boolean shouldDecodePrefetches() {
    return mDecodePrefetches;
  }

  public @Nullable Boolean getResizingAllowedOverride() {
    return mResizingAllowedOverride;
  }

  public @Nullable DownsampleMode getDownsampleOverride() {
    return mDownsampleOverride;
  }

  public int getDelayMs() {
    return mDelayMs;
  }

  public synchronized File getSourceFile() {
    if (mSourceFile == null) {
      Preconditions.checkNotNull(mSourceUri.getPath());
      mSourceFile = new File(mSourceUri.getPath());
    }
    return mSourceFile;
  }

  public @Nullable Postprocessor getPostprocessor() {
    return mPostprocessor;
  }

  public @Nullable RequestListener getRequestListener() {
    return mRequestListener;
  }

  public @Nullable String getDiskCacheId() {
    return mDiskCacheId;
  }

  public @Nullable String getCustomCacheKey() {
    return mCustomCacheKey;
  }

  public Boolean isFirstFrameThumbnailEnabled() {
    return mIsFirstFrameThumbnailEnabled;
  }

  /** Returns the extra set for the given key, or null if there is none. */
  @SuppressWarnings("unchecked")
  public @Nullable <T> T getExtra(ImageRequestExtra<T> key) {
    return mExtras == null ? null : (T) mExtras.get(key);
  }

  /** Returns all extras carried on this request, or null if there are none. */
  public @Nullable Map<ImageRequestExtra<?>, Object> getExtras() {
    return mExtras == null ? null : Collections.unmodifiableMap(mExtras);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (!(other instanceof ImageRequest)) {
      return false;
    }
    ImageRequest request = (ImageRequest) other;
    if (sUseCachedHashcodeInEquals) {
      int a = mHashcode;
      int b = request.mHashcode;
      if (a != 0 && b != 0 && a != b) {
        return false;
      }
    }
    if (mLocalThumbnailPreviewsEnabled != request.mLocalThumbnailPreviewsEnabled) return false;
    if (mIsDiskCacheEnabled != request.mIsDiskCacheEnabled) return false;
    if (mIsMemoryCacheEnabled != request.mIsMemoryCacheEnabled) return false;
    if (!Objects.equal(mSourceUri, request.mSourceUri)
        || !Objects.equal(mCacheChoice, request.mCacheChoice)
        || !Objects.equal(mDiskCacheId, request.mDiskCacheId)
        || !Objects.equal(mCustomCacheKey, request.mCustomCacheKey)
        || !Objects.equal(mSourceFile, request.mSourceFile)
        || !Objects.equal(mBytesRange, request.mBytesRange)
        || !Objects.equal(mImageDecodeOptions, request.mImageDecodeOptions)
        || !Objects.equal(mResizeOptions, request.mResizeOptions)
        || !Objects.equal(mRequestPriority, request.mRequestPriority)
        || !Objects.equal(mLowestPermittedRequestLevel, request.mLowestPermittedRequestLevel)
        || !Objects.equal(mCachesDisabled, request.mCachesDisabled)
        || !Objects.equal(mDecodePrefetches, request.mDecodePrefetches)
        || !Objects.equal(mResizingAllowedOverride, request.mResizingAllowedOverride)
        || !Objects.equal(mDownsampleOverride, request.mDownsampleOverride)
        || !Objects.equal(mRotationOptions, request.mRotationOptions)
        || mLoadThumbnailOnly != request.mLoadThumbnailOnly
        || mIsFirstFrameThumbnailEnabled != request.mIsFirstFrameThumbnailEnabled) {
      return false;
    }
    final CacheKey thisPostprocessorKey =
        mPostprocessor != null ? mPostprocessor.getPostprocessorCacheKey() : null;
    final CacheKey thatPostprocessorKey =
        request.mPostprocessor != null ? request.mPostprocessor.getPostprocessorCacheKey() : null;
    if (!Objects.equal(thisPostprocessorKey, thatPostprocessorKey)) return false;
    if (!equalityExtrasEqual(mExtras, request.mExtras)) return false;
    return mDelayMs == request.mDelayMs;
  }

  private static boolean equalityExtrasEqual(
      @Nullable Map<ImageRequestExtra<?>, Object> a,
      @Nullable Map<ImageRequestExtra<?>, Object> b) {
    if (a == b) {
      return true;
    }
    return containsEqualityExtrasOf(a, b) && containsEqualityExtrasOf(b, a);
  }

  private static boolean containsEqualityExtrasOf(
      @Nullable Map<ImageRequestExtra<?>, Object> container,
      @Nullable Map<ImageRequestExtra<?>, Object> extras) {
    if (extras == null) {
      return true;
    }
    for (Map.Entry<ImageRequestExtra<?>, Object> entry : extras.entrySet()) {
      if (entry.getKey().getAffectsEquality()
          && (container == null
              || !Objects.equal(entry.getValue(), container.get(entry.getKey())))) {
        return false;
      }
    }
    return true;
  }

  private static int equalityExtrasHashCode(@Nullable Map<ImageRequestExtra<?>, Object> extras) {
    if (extras == null) {
      return 0;
    }
    int hash = 0;
    for (Map.Entry<ImageRequestExtra<?>, Object> entry : extras.entrySet()) {
      if (entry.getKey().getAffectsEquality()) {
        hash += entry.getKey().hashCode() ^ entry.getValue().hashCode();
      }
    }
    return hash;
  }

  @Override
  public int hashCode() {
    final boolean cacheHashcode = sCacheHashcode;
    int result = 0;
    if (cacheHashcode) {
      result = mHashcode;
    }
    if (result == 0) {
      final CacheKey postprocessorCacheKey =
          mPostprocessor != null ? mPostprocessor.getPostprocessorCacheKey() : null;
      result = HashCode.extend(0, mCacheChoice);
      result = HashCode.extend(result, mSourceUri);
      result = HashCode.extend(result, mLocalThumbnailPreviewsEnabled);
      result = HashCode.extend(result, mBytesRange);
      result = HashCode.extend(result, mRequestPriority);
      result = HashCode.extend(result, mLowestPermittedRequestLevel);
      result = HashCode.extend(result, mCachesDisabled);
      result = HashCode.extend(result, mIsDiskCacheEnabled);
      result = HashCode.extend(result, mIsMemoryCacheEnabled);
      result = HashCode.extend(result, mImageDecodeOptions);
      result = HashCode.extend(result, mDecodePrefetches);
      result = HashCode.extend(result, mResizeOptions);
      result = HashCode.extend(result, mRotationOptions);
      result = HashCode.extend(result, postprocessorCacheKey);
      result = HashCode.extend(result, mResizingAllowedOverride);
      result = HashCode.extend(result, mDownsampleOverride);
      result = HashCode.extend(result, mCustomCacheKey);
      result = HashCode.extend(result, mDelayMs);
      result = HashCode.extend(result, mLoadThumbnailOnly);
      result = HashCode.extend(result, mIsFirstFrameThumbnailEnabled);
      // ^ I *think* this is safe despite autoboxing...?
      int extrasHashCode = equalityExtrasHashCode(mExtras);
      if (extrasHashCode != 0) {
        result = HashCode.extend(result, extrasHashCode);
      }
      if (cacheHashcode) {
        mHashcode = result;
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("uri", mSourceUri)
        .add("cacheChoice", mCacheChoice)
        .add("decodeOptions", mImageDecodeOptions)
        .add("postprocessor", mPostprocessor)
        .add("priority", mRequestPriority)
        .add("resizeOptions", mResizeOptions)
        .add("rotationOptions", mRotationOptions)
        .add("bytesRange", mBytesRange)
        .add("resizingAllowedOverride", mResizingAllowedOverride)
        .add("downsampleOverride", mDownsampleOverride)
        .add("progressiveRenderingEnabled", mProgressiveRenderingEnabled)
        .add("localThumbnailPreviewsEnabled", mLocalThumbnailPreviewsEnabled)
        .add("loadThumbnailOnly", mLoadThumbnailOnly)
        .add("lowestPermittedRequestLevel", mLowestPermittedRequestLevel)
        .add("cachesDisabled", mCachesDisabled)
        .add("isDiskCacheEnabled", mIsDiskCacheEnabled)
        .add("isMemoryCacheEnabled", mIsMemoryCacheEnabled)
        .add("decodePrefetches", mDecodePrefetches)
        .add("customCacheKey", mCustomCacheKey)
        .add("delayMs", mDelayMs)
        .add("isFirstFrameThumbnailEnabled", mIsFirstFrameThumbnailEnabled)
        .add("extras", mExtras)
        .toString();
  }

  /** An enum describing the cache choice. */
  public enum CacheChoice {

    /* Indicates that this image should go in the small disk cache, if one is being used */
    SMALL,

    /* Default */
    DEFAULT,

    /* Indicates that the image should go in the consumer provided cache, represent by the ImageRequest’s cacheId */
    DYNAMIC
  }

  /**
   * Level down to we are willing to go in order to find an image. E.g., we might only want to go
   * down to bitmap memory cache, and not check the disk cache or do a full fetch.
   */
  public enum RequestLevel {
    /* Fetch (from the network or local storage) */
    FULL_FETCH(1),

    /* Disk caching */
    DISK_CACHE(2),

    /* Encoded memory caching */
    ENCODED_MEMORY_CACHE(3),

    /* Bitmap caching */
    BITMAP_MEMORY_CACHE(4);

    private int mValue;

    private RequestLevel(int value) {
      mValue = value;
    }

    public int getValue() {
      return mValue;
    }

    public static RequestLevel getMax(RequestLevel requestLevel1, RequestLevel requestLevel2) {
      return requestLevel1.getValue() > requestLevel2.getValue() ? requestLevel1 : requestLevel2;
    }
  }

  /**
   * Caches bit locations in cachesDisabled from bitmap read bit (rightest bit, 00000001) to disk
   * write bit (00100000). Uses for creating mask when performing bitwise operation with
   * cachesDisabled in order to turn on (disable cache) or turn off (enable cache) the right bit.
   */
  @IntDef({
    CachesLocationsMasks.BITMAP_READ,
    CachesLocationsMasks.BITMAP_WRITE,
    CachesLocationsMasks.ENCODED_READ,
    CachesLocationsMasks.ENCODED_WRITE,
    CachesLocationsMasks.DISK_READ,
    CachesLocationsMasks.DISK_WRITE
  })
  public @interface CachesLocationsMasks {
    /* bitmap cache read bit location- 00000001  */
    final /* bitmap cache read bit location- 00000001  */ int BITMAP_READ = 1;

    /* bitmap cache write bit location- 00000010  */
    final /* bitmap cache write bit location- 00000010  */ int BITMAP_WRITE = 2;

    /* encoded cache read bit location- 00000100  */
    final /* encoded cache read bit location- 00000100  */ int ENCODED_READ = 4;

    /* encoded cache write bit location- 00001000  */
    final /* encoded cache write bit location- 00001000  */ int ENCODED_WRITE = 8;

    /* disk cache read bit location- 00010000  */
    final /* disk cache read bit location- 00010000  */ int DISK_READ = 16;

    /* disk cache write bit location- 00100000  */
    final /* disk cache write bit location- 00100000  */ int DISK_WRITE = 32;
  }

  /**
   * This is a utility method which returns the type of Uri
   *
   * @param uri The Uri to test
   * @return The type of the given Uri if available or SOURCE_TYPE_UNKNOWN if not
   */
  private static @SourceUriType int getSourceUriType(final @Nullable Uri uri) {
    if (uri == null) {
      return SOURCE_TYPE_UNKNOWN;
    }
    if (UriUtil.isNetworkUri(uri)) {
      return SOURCE_TYPE_NETWORK;
    } else if (uri.getPath() != null && UriUtil.isLocalFileUri(uri)) {
      if (MediaUtils.isVideo(MediaUtils.extractMime(uri.getPath()))) {
        return SOURCE_TYPE_LOCAL_VIDEO_FILE;
      } else {
        return SOURCE_TYPE_LOCAL_IMAGE_FILE;
      }
    } else if (UriUtil.isLocalContentUri(uri)) {
      return SOURCE_TYPE_LOCAL_CONTENT;
    } else if (UriUtil.isLocalAssetUri(uri)) {
      return SOURCE_TYPE_LOCAL_ASSET;
    } else if (UriUtil.isLocalResourceUri(uri)) {
      return SOURCE_TYPE_LOCAL_RESOURCE;
    } else if (UriUtil.isDataUri(uri)) {
      return SOURCE_TYPE_DATA;
    } else if (UriUtil.isQualifiedResourceUri(uri)) {
      return SOURCE_TYPE_QUALIFIED_RESOURCE;
    } else {
      return SOURCE_TYPE_UNKNOWN;
    }
  }

  public static final Fn<ImageRequest, Uri> REQUEST_TO_URI_FN =
      new Fn<ImageRequest, Uri>() {
        @Override
        public @Nullable Uri apply(@Nullable ImageRequest arg) {
          return arg != null ? arg.getSourceUri() : null;
        }
      };

  public static void setUseCachedHashcodeInEquals(boolean useCachedHashcodeInEquals) {
    sUseCachedHashcodeInEquals = useCachedHashcodeInEquals;
  }

  public static void setCacheHashcode(boolean cacheHashcode) {
    sCacheHashcode = cacheHashcode;
  }
}
