/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request;

import static com.facebook.imagepipeline.request.ImageRequest.CacheChoice;
import static com.facebook.imagepipeline.request.ImageRequest.CachesLocationsMasks;
import static com.facebook.imagepipeline.request.ImageRequest.RequestLevel;

import android.net.Uri;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.util.UriUtil;
import com.facebook.imagepipeline.common.BytesRange;
import com.facebook.imagepipeline.common.ImageDecodeOptions;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.core.DownsampleMode;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.core.ImagePipelineExperiments;
import com.facebook.imagepipeline.listener.RequestListener;
import com.facebook.infer.annotation.FalseOnNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Builder class for {@link ImageRequest}s. */
public class ImageRequestBuilder {

  private static final Set<String> CUSTOM_NETWORK_SCHEMES = new HashSet<>();
  @Nullable private Uri mSourceUri = null;
  private RequestLevel mLowestPermittedRequestLevel = RequestLevel.FULL_FETCH;
  private int mCachesDisabled = 0; // All caches enabled by default
  private @Nullable ResizeOptions mResizeOptions = null;
  private @Nullable RotationOptions mRotationOptions = null;
  private ImageDecodeOptions mImageDecodeOptions = ImageDecodeOptions.defaults();
  private CacheChoice mCacheChoice = CacheChoice.DEFAULT;
  private boolean mProgressiveRenderingEnabled =
      ImagePipelineConfig.getDefaultImageRequestConfig().isProgressiveRenderingEnabled();
  private boolean mSkipEncodedPartialImagesInCachesNotInRequestRange = false;
  private boolean mLocalThumbnailPreviewsEnabled = false;
  private boolean mLoadThumbnailOnly = false;
  private Priority mRequestPriority = Priority.HIGH;
  private @Nullable Postprocessor mPostprocessor = null;
  private @Nullable Boolean mDecodePrefetches = null;
  private @Nullable RequestListener mRequestListener;
  private @Nullable BytesRange mBytesRange = null;
  private @Nullable Boolean mResizingAllowedOverride = null;
  private @Nullable DownsampleMode mDownsampleOverride = null;
  private int mDelayMs;
  private @Nullable String mDiskCacheId = null;
  private @Nullable String mCustomCacheKey = null;
  private Boolean mIsFirstFrameThumbnailEnabled = false;
  private @Nullable Map<ImageRequestExtra<?>, Object> mExtras = null;

  /**
   * Creates a new request builder instance. The setting will be done according to the source type.
   *
   * @param uri the uri to fetch
   * @return a new request builder instance
   */
  public static ImageRequestBuilder newBuilderWithSource(Uri uri) {
    return new ImageRequestBuilder().setSource(uri);
  }

  /**
   * Creates a new request builder instance for a local resource image.
   *
   * <p>Only image resources can be used with the image pipeline (PNG, JPG, GIF). Other resource
   * types such as Strings or XML Drawables make no sense in the context of the image pipeline and
   * so cannot be supported. Attempts to do so will throw an {@link
   * java.lang.IllegalArgumentException} when the pipeline tries to decode the resource.
   *
   * <p>One potentially confusing case is drawable declared in XML (e.g. ShapeDrawable). This is not
   * an image. If you want to display an XML drawable as the main image, then set it as a
   * placeholder and do not set a URI.
   *
   * <p>
   *
   * @param resId local image resource id.
   * @return a new request builder instance.
   */
  public static ImageRequestBuilder newBuilderWithResourceId(int resId) {
    return newBuilderWithSource(UriUtil.getUriForResourceId(resId));
  }

  /**
   * Creates a new request builder instance with the same parameters as the imageRequest passed in.
   *
   * @param imageRequest the ImageRequest from where to copy the parameters to the builder.
   * @return a new request builder instance
   */
  public static ImageRequestBuilder fromRequest(ImageRequest imageRequest) {
    return ImageRequestBuilder.newBuilderWithSource(imageRequest.getSourceUri())
        .setImageDecodeOptions(imageRequest.getImageDecodeOptions())
        .setBytesRange(imageRequest.getBytesRange())
        .setCacheChoice(imageRequest.getCacheChoice())
        .setLocalThumbnailPreviewsEnabled(imageRequest.getLocalThumbnailPreviewsEnabled())
        .setLoadThumbnailOnly(imageRequest.getLoadThumbnailOnlyForAndroidSdkAboveQ())
        .setLowestPermittedRequestLevel(imageRequest.getLowestPermittedRequestLevel())
        .setCachesDisabled(imageRequest.getCachesDisabled())
        .setPostprocessor(imageRequest.getPostprocessor())
        .setProgressiveRenderingEnabled(imageRequest.getProgressiveRenderingEnabled())
        .setRequestPriority(imageRequest.getPriority())
        .setResizeOptions(imageRequest.getResizeOptions())
        .setRequestListener(imageRequest.getRequestListener())
        .setRotationOptions(imageRequest.getRotationOptions())
        .setShouldDecodePrefetches(imageRequest.shouldDecodePrefetches())
        .setDelayMs(imageRequest.getDelayMs())
        .setDiskCacheId(imageRequest.getDiskCacheId())
        .setCustomCacheKey(imageRequest.getCustomCacheKey())
        .setDownsampleOverride(imageRequest.getDownsampleOverride())
        .setResizingAllowedOverride(imageRequest.getResizingAllowedOverride())
        .setIsFirstFrameThumbnailEnabled(imageRequest.isFirstFrameThumbnailEnabled())
        .setSkipEncodedPartialImagesInCachesNotInRequestRange(
            imageRequest.getSkipEncodedPartialImagesInCachesNotInRequestRange())
        .setExtras(imageRequest.getExtras());
  }

  public static void addCustomUriNetworkScheme(String scheme) {
    CUSTOM_NETWORK_SCHEMES.add(scheme);
  }

  private ImageRequestBuilder() {}

  /**
   * Sets the source uri (both network and local uris are supported). Note: this will enable disk
   * caching for network sources, and disable it for local sources.
   *
   * @param uri the uri to fetch the image from
   * @return the updated builder instance
   */
  public ImageRequestBuilder setSource(Uri uri) {
    Preconditions.checkNotNull(uri);

    mSourceUri = uri;
    return this;
  }

  /** Gets the source Uri. */
  public Uri getSourceUri() {
    return mSourceUri;
  }

  /**
   * Sets the lowest level that is permitted to request the image from.
   *
   * @param requestLevel the lowest request level that is allowed
   * @return the updated builder instance
   */
  public ImageRequestBuilder setLowestPermittedRequestLevel(RequestLevel requestLevel) {
    mLowestPermittedRequestLevel = requestLevel;
    return this;
  }

  /** Gets the lowest permitted request level. */
  public RequestLevel getLowestPermittedRequestLevel() {
    return mLowestPermittedRequestLevel;
  }

  /**
   * Sets the caches read and write permissions.
   *
   * @param cachesDisabled the representation caches permissions
   * @return the updated builder instance
   */
  private ImageRequestBuilder setCachesDisabled(int cachesDisabled) {
    this.mCachesDisabled = cachesDisabled;
    if (mCacheChoice != CacheChoice.DYNAMIC) {
      this.mDiskCacheId = null;
    }
    return this;
  }

  /** Gets the caches permissions. */
  public int getCachesDisabled() {
    return mCachesDisabled;
  }

  /**
   * Enables or disables auto-rotate for the image in case image has orientation.
   *
   * @return the updated builder instance
   * @param enabled
   * @deprecated Use #setRotationOptions(RotationOptions)
   */
  @Deprecated
  public ImageRequestBuilder setAutoRotateEnabled(boolean enabled) {
    if (enabled) {
      return setRotationOptions(RotationOptions.autoRotate());
    } else {
      return setRotationOptions(RotationOptions.disableRotation());
    }
  }

  /**
   * Sets resize options in case resize should be performed.
   *
   * @param resizeOptions resize options
   * @return the modified builder instance
   */
  public ImageRequestBuilder setResizeOptions(@Nullable ResizeOptions resizeOptions) {
    this.mResizeOptions = resizeOptions;
    return this;
  }

  /** Gets the resize options if set, null otherwise. */
  public @Nullable ResizeOptions getResizeOptions() {
    return mResizeOptions;
  }

  /**
   * Sets rotation options for the image, whether to rotate by a multiple of 90 degrees, use the
   * EXIF metadata (relevant to JPEGs only) or to not rotate. This also specifies whether the
   * rotation should be left until the bitmap is rendered (as the GPU can do this more efficiently
   * than the effort to change the bitmap object).
   *
   * @param rotationOptions rotation options
   * @return the modified builder instance
   */
  public ImageRequestBuilder setRotationOptions(@Nullable RotationOptions rotationOptions) {
    this.mRotationOptions = rotationOptions;
    return this;
  }

  /** Gets the rotation options if set, null otherwise. */
  public @Nullable RotationOptions getRotationOptions() {
    return mRotationOptions;
  }

  /**
   * Set the range of bytes to request from the network (in the case of an HTTP request). This is
   * only used if {@link ImagePipelineExperiments#isPartialImageCachingEnabled()} is true and your
   * {@link com.facebook.imagepipeline.producers.NetworkFetcher} makes use of it.
   *
   * <p>Even where this is supported, there is no contract that this must be followed. The response
   * may contain the full image data, more than is requested or less, depending on what's already in
   * cache and external factors.
   *
   * @param bytesRange the range of bytes
   * @return the modified builder instance
   */
  public ImageRequestBuilder setBytesRange(@Nullable BytesRange bytesRange) {
    this.mBytesRange = bytesRange;
    return this;
  }

  /** Gets the range of bytes if set, null otherwise. */
  @Nullable
  public BytesRange getBytesRange() {
    return mBytesRange;
  }

  public ImageRequestBuilder setImageDecodeOptions(ImageDecodeOptions imageDecodeOptions) {
    this.mImageDecodeOptions = imageDecodeOptions;
    return this;
  }

  public ImageDecodeOptions getImageDecodeOptions() {
    return mImageDecodeOptions;
  }

  /**
   * Sets the cache option. Pipeline might use different caches and eviction policies for each image
   * type.
   *
   * @param cacheChoice the cache choice to set
   * @return the modified builder instance
   */
  public ImageRequestBuilder setCacheChoice(ImageRequest.CacheChoice cacheChoice) {
    this.mCacheChoice = cacheChoice;
    return this;
  }

  /** Gets the cache choice (profile image or default). */
  public CacheChoice getCacheChoice() {
    return mCacheChoice;
  }

  /**
   * Enables or disables progressive rendering.
   *
   * @param enabled
   * @return the modified builder instance
   */
  public ImageRequestBuilder setProgressiveRenderingEnabled(boolean enabled) {
    mProgressiveRenderingEnabled = enabled;
    return this;
  }

  /**
   * Enables or disables skipping partial cached images in disk cache.
   *
   * @param skipEncodedPartialImagesInCachesNotInRequestRange
   * @return the modified builder instance
   */
  public ImageRequestBuilder setSkipEncodedPartialImagesInCachesNotInRequestRange(
      boolean skipEncodedPartialImagesInCachesNotInRequestRange) {
    mSkipEncodedPartialImagesInCachesNotInRequestRange =
        skipEncodedPartialImagesInCachesNotInRequestRange;
    return this;
  }

  /** Returns whether skipping encoded partial images in caches is enabled. */
  public boolean getSkipEncodedPartialImagesInCachesNotInRequestRange() {
    return mSkipEncodedPartialImagesInCachesNotInRequestRange;
  }

  /** Returns whether progressive loading is enabled. */
  public boolean isProgressiveRenderingEnabled() {
    return mProgressiveRenderingEnabled;
  }

  /**
   * Enables or disables the use of local thumbnails as previews.
   *
   * @param enabled
   * @return the modified builder instance
   */
  public ImageRequestBuilder setLocalThumbnailPreviewsEnabled(boolean enabled) {
    mLocalThumbnailPreviewsEnabled = enabled;
    return this;
  }

  /** Returns whether the use of local thumbnails for previews is enabled. */
  public boolean isLocalThumbnailPreviewsEnabled() {
    return mLocalThumbnailPreviewsEnabled;
  }

  public ImageRequestBuilder setLoadThumbnailOnly(boolean loadThumbnailOnly) {
    this.mLoadThumbnailOnly = loadThumbnailOnly;
    return this;
  }

  /** Returns whether the use of local thumbnails for previews is enabled. */
  public boolean getLoadThumbnailOnly() {
    return mLoadThumbnailOnly;
  }

  /** Disables disk cache for this request, regardless where the image will come from. */
  public ImageRequestBuilder disableDiskCache() {
    int mask = CachesLocationsMasks.DISK_READ | CachesLocationsMasks.DISK_WRITE;
    mCachesDisabled = mCachesDisabled | mask;
    return this;
  }

  @FalseOnNull
  public static boolean isCustomNetworkUri(@Nullable Uri uri) {
    if (CUSTOM_NETWORK_SCHEMES == null || uri == null) {
      return false;
    }
    for (String scheme : CUSTOM_NETWORK_SCHEMES) {
      if (scheme.equals(uri.getScheme())) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether the use of the disk cache is enabled, which is partly dependent on the URI. */
  public boolean isDiskCacheEnabled() {
    int mask = CachesLocationsMasks.DISK_READ | CachesLocationsMasks.DISK_WRITE;
    return ((mCachesDisabled & mask) == 0)
        && (UriUtil.isNetworkUri(mSourceUri) || isCustomNetworkUri(mSourceUri));
  }

  /** Disables memory cache for this request. */
  public ImageRequestBuilder disableMemoryCache() {
    int mask =
        CachesLocationsMasks.BITMAP_READ
            | CachesLocationsMasks.BITMAP_WRITE
            | CachesLocationsMasks.ENCODED_READ
            | CachesLocationsMasks.ENCODED_WRITE;
    mCachesDisabled = mCachesDisabled | mask;
    return this;
  }

  /** Returns whether the use of the memory cache is enabled. */
  public boolean isMemoryCacheEnabled() {
    int mask =
        CachesLocationsMasks.BITMAP_READ
            | CachesLocationsMasks.BITMAP_WRITE
            | CachesLocationsMasks.ENCODED_READ
            | CachesLocationsMasks.ENCODED_WRITE;
    return (mCachesDisabled & mask) == 0;
  }

  /**
   * Set priority for the request.
   *
   * @param requestPriority
   * @return the modified builder instance
   */
  public ImageRequestBuilder setRequestPriority(Priority requestPriority) {
    this.mRequestPriority = requestPriority;
    return this;
  }

  /** Returns the request priority. */
  public Priority getRequestPriority() {
    return mRequestPriority;
  }

  /**
   * Sets the postprocessor.
   *
   * @param postprocessor postprocessor to postprocess the output bitmap with.
   * @return the modified builder instance
   */
  public ImageRequestBuilder setPostprocessor(@Nullable Postprocessor postprocessor) {
    this.mPostprocessor = postprocessor;
    return this;
  }

  /** Gets postprocessor if set, null otherwise. */
  public @Nullable Postprocessor getPostprocessor() {
    return mPostprocessor;
  }

  /**
   * Sets a request listener to use for just this image request
   *
   * @param requestListener a request listener to use in addition to the global ones set in the
   *     {@link com.facebook.imagepipeline.core.ImagePipelineConfig}
   * @return the modified builder instance
   */
  public ImageRequestBuilder setRequestListener(@Nullable RequestListener requestListener) {
    this.mRequestListener = requestListener;
    return this;
  }

  /**
   * @return the additional request listener to use for this image request
   */
  public @Nullable RequestListener getRequestListener() {
    return mRequestListener;
  }

  /**
   * Sets a disk cache id to determine which diskCache to use for this request.
   *
   * @param diskCacheId a disk cache id to determine which diskCache to use for this request.
   * @return the modified builder instance
   */
  public ImageRequestBuilder setDiskCacheId(@Nullable String diskCacheId) {
    this.mDiskCacheId = diskCacheId;
    return this;
  }

  /**
   * @return the disk cache id to determine which diskCache to use for this request.
   */
  public @Nullable String getDiskCacheId() {
    return mDiskCacheId;
  }

  /**
   * Sets a custom cache key for this request. When set, this key will be used instead of deriving
   * the cache key from the source URI.
   *
   * @param customCacheKey a custom cache key to use for caching this image.
   * @return the modified builder instance
   */
  public ImageRequestBuilder setCustomCacheKey(@Nullable String customCacheKey) {
    this.mCustomCacheKey = customCacheKey;
    return this;
  }

  /**
   * @return the custom cache key to use for caching this image.
   */
  public @Nullable String getCustomCacheKey() {
    return mCustomCacheKey;
  }

  /**
   * Builds the Request.
   *
   * @return a valid image request
   */
  public ImageRequest build() {
    validate();
    return new ImageRequest(this);
  }

  public @Nullable Boolean shouldDecodePrefetches() {
    return mDecodePrefetches;
  }

  public ImageRequestBuilder setShouldDecodePrefetches(@Nullable Boolean shouldDecodePrefetches) {
    mDecodePrefetches = shouldDecodePrefetches;
    return this;
  }

  public ImageRequestBuilder setResizingAllowedOverride(@Nullable Boolean resizingAllowedOverride) {
    this.mResizingAllowedOverride = resizingAllowedOverride;
    return this;
  }

  public @Nullable Boolean getResizingAllowedOverride() {
    return mResizingAllowedOverride;
  }

  public ImageRequestBuilder setDownsampleOverride(@Nullable DownsampleMode downsampleOverride) {
    this.mDownsampleOverride = downsampleOverride;
    return this;
  }

  public @Nullable DownsampleMode getDownsampleOverride() {
    return mDownsampleOverride;
  }

  public int getDelayMs() {
    return mDelayMs;
  }

  public ImageRequestBuilder setIsFirstFrameThumbnailEnabled(Boolean isFirstFrameThumbnailEnabled) {
    this.mIsFirstFrameThumbnailEnabled = isFirstFrameThumbnailEnabled;
    return this;
  }

  public Boolean getIsFirstFrameThumbnailEnabled() {
    return mIsFirstFrameThumbnailEnabled;
  }

  /** Add an artificial delay for this image, in milliseconds. */
  public ImageRequestBuilder setDelayMs(int delayMs) {
    this.mDelayMs = delayMs;
    return this;
  }

  /**
   * Sets an app-defined extra on the request. Passing a null value removes the extra.
   *
   * @param key the typed key of the extra
   * @param value the value to set, or null to remove the extra
   * @return the modified builder instance
   */
  public <T> ImageRequestBuilder setExtra(ImageRequestExtra<T> key, @Nullable T value) {
    if (value == null) {
      if (mExtras != null) {
        mExtras.remove(key);
      }
      return this;
    }
    if (mExtras == null) {
      mExtras = new HashMap<>();
    }
    mExtras.put(key, value);
    return this;
  }

  private ImageRequestBuilder setExtras(@Nullable Map<ImageRequestExtra<?>, Object> extras) {
    mExtras = extras == null ? null : new HashMap<>(extras);
    return this;
  }

  /** Gets the extra set for the given key, or null if there is none. */
  @SuppressWarnings("unchecked")
  public @Nullable <T> T getExtra(ImageRequestExtra<T> key) {
    return mExtras == null ? null : (T) mExtras.get(key);
  }

  /** Gets all extras set on this builder, or null if none were set. */
  public @Nullable Map<ImageRequestExtra<?>, Object> getExtras() {
    return mExtras == null ? null : Collections.unmodifiableMap(mExtras);
  }

  /** An exception class for builder methods. */
  public static class BuilderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BuilderException(String message) {
      super("Invalid request builder: " + message);
    }
  }

  /** Performs validation. */
  protected void validate() {
    // make sure that the source uri is set correctly.
    if (mSourceUri == null) {
      throw new BuilderException("Source must be set!");
    }

    // For local resource we require caller to specify statically generated resource id as a path.
    if (UriUtil.isLocalResourceUri(mSourceUri)) {
      if (!mSourceUri.isAbsolute()) {
        throw new BuilderException("Resource URI path must be absolute.");
      }
      if (mSourceUri.getPath().isEmpty()) {
        throw new BuilderException("Resource URI must not be empty");
      }
      try {
        Integer.parseInt(mSourceUri.getPath().substring(1));
      } catch (NumberFormatException ignored) {
        throw new BuilderException("Resource URI path must be a resource id.");
      }
    }

    // For local asset we require caller to specify absolute path of an asset, which will be
    // resolved by AssetManager relative to configured asset folder of an app.
    if (UriUtil.isLocalAssetUri(mSourceUri)) {
      final String assetPath = mSourceUri.getPath();
      if (assetPath == null || !assetPath.startsWith("/")) {
        throw new BuilderException("Asset URI path must be absolute.");
      }
    }
  }
}
