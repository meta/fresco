/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.cache

import bolts.Task
import com.facebook.cache.common.CacheKey
import com.facebook.cache.disk.FileCache
import com.facebook.common.logging.FLog
import com.facebook.common.memory.PooledByteBuffer
import com.facebook.common.memory.PooledByteBufferFactory
import com.facebook.common.memory.PooledByteStreams
import com.facebook.common.references.CloseableReference
import com.facebook.fresco.middleware.HasExtraData
import com.facebook.imagepipeline.image.EncodedImage
import com.facebook.imagepipeline.instrumentation.FrescoInstrumenter
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BufferedDiskCache provides get and put operations to take care of scheduling disk-cache
 * read/writes.
 */
class BufferedDiskCache(
    private val fileCache: FileCache,
    private val pooledByteBufferFactory: PooledByteBufferFactory,
    private val pooledByteStreams: PooledByteStreams,
    private val readExecutor: Executor,
    private val writeExecutor: Executor,
    private val imageCacheStatsTracker: ImageCacheStatsTracker,
    private val preserveMetadata: Boolean,
    private val preserveMetadataDuringStartup: Boolean,
    private val isAppStarting: (() -> Boolean)?,
) {

  private val stagingArea: StagingArea = StagingArea.getInstance()

  /**
   * Returns true if the key is in the in-memory key index.
   *
   * Not guaranteed to be correct. The cache may yet have this key even if this returns false. But
   * if it returns true, it definitely has it.
   *
   * Avoids a disk read.
   */
  fun containsSync(key: CacheKey): Boolean =
      stagingArea.containsKey(key) || fileCache.hasKeySync(key)

  /**
   * Performs a key-value look up in the disk cache. If no value is found in the staging area, then
   * disk cache checks are scheduled on a background thread. Any error manifests itself as a cache
   * miss, i.e. the returned Task resolves to false.
   *
   * @param key
   * @return Task that resolves to true if an element is found, or false otherwise
   */
  fun contains(key: CacheKey): Task<Boolean> =
      if (containsSync(key)) {
        Task.forResult(true)
      } else {
        containsAsync(key)
      }

  private fun containsAsync(key: CacheKey): Task<Boolean> {
    return try {
      val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_containsAsync")
      Task.call(
          Callable {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              return@Callable checkInStagingAreaAndFileCache(key)
            } catch (th: Throwable) {
              FrescoInstrumenter.markFailure(token, th)
              throw th
            } finally {
              FrescoInstrumenter.onEndWork(currentToken)
            }
          },
          readExecutor,
      )
    } catch (exception: Exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache read for %s", key.uriString)
      Task.forError(exception)
    }
  }

  /**
   * Performs disk cache check synchronously.
   *
   * @param key
   * @return true if the key is found in disk cache else false
   */
  fun diskCheckSync(key: CacheKey): Boolean =
      if (containsSync(key)) {
        true
      } else {
        checkInStagingAreaAndFileCache(key)
      }

  /**
   * Performs key-value look up in disk cache. If value is not found in disk cache staging area then
   * disk cache read is scheduled on background thread. Any error manifests itself as cache miss,
   * i.e. the returned task resolves to null.
   *
   * @param key
   * @return Task that resolves to cached element or null if one cannot be retrieved; returned task
   *   never rethrows any exception
   */
  operator fun get(key: CacheKey, isCancelled: AtomicBoolean): Task<EncodedImage> =
      traceSection("BufferedDiskCache#get") {
        val pinnedImage = stagingArea[key]
        pinnedImage?.let { foundPinnedImage(key, it) } ?: getAsync(key, isCancelled)
      }

  /**
   * Performs key-value look up in disk cache. If value is not found in disk cache staging area then
   * disk cache probing is scheduled on background thread.
   *
   * @param key
   */
  fun probe(key: CacheKey): Task<Void> {
    checkNotNull(key)
    return try {
      val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_probe")
      Task.call(
          {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              fileCache.probe(key)
            } finally {
              FrescoInstrumenter.onEndWork(currentToken)
            }
            null
          },
          writeExecutor,
      )
    } catch (exception: Exception) {
      FLog.w(TAG, exception, "Failed to schedule disk-cache probe for %s", key.uriString)
      Task.forError(exception)
    }
  }

  fun addKeyForAsyncProbing(key: CacheKey) {
    fileCache.probe(key)
  }

  /**
   * Performs key-value loop up in staging area and file cache. Any error manifests itself as a
   * miss, i.e. returns false.
   *
   * @param key
   * @return true if the image is found in staging area or File cache, false if not found
   */
  private fun checkInStagingAreaAndFileCache(key: CacheKey): Boolean {
    val result = stagingArea[key]
    return if (result != null) {
      result.close()
      FLog.v(TAG, "Found image for %s in staging area", key.uriString)
      imageCacheStatsTracker.onStagingAreaHit(key)
      true
    } else {
      FLog.v(TAG, "Did not find image for %s in staging area", key.uriString)
      imageCacheStatsTracker.onStagingAreaMiss(key)
      try {
        fileCache.hasKey(key)
      } catch (exception: Exception) {
        false
      }
    }
  }

  private fun getAsync(key: CacheKey, isCancelled: AtomicBoolean): Task<EncodedImage> {
    return try {
      val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_getAsync")
      Task.call(
          Callable {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              if (isCancelled.get()) {
                throw CancellationException()
              }
              var result = stagingArea[key]
              if (result != null) {
                FLog.v(TAG, "Found image for %s in staging area", key.uriString)
                imageCacheStatsTracker.onStagingAreaHit(key)
              } else {
                FLog.v(TAG, "Did not find image for %s in staging area", key.uriString)
                imageCacheStatsTracker.onStagingAreaMiss(key)
                result =
                    try {
                      val diskResult = readFromDiskCache(key) ?: return@Callable null
                      val ref = CloseableReference.of(diskResult.buffer)
                      try {
                        val image = EncodedImage(ref)
                        maybeRestoreMetadata(key, image)
                        if (diskResult.originSubcategory != null) {
                          image.putExtra(
                              HasExtraData.KEY_ORIGIN_SUBCATEGORY,
                              diskResult.originSubcategory,
                          )
                        }
                        image
                      } finally {
                        CloseableReference.closeSafely(ref)
                      }
                    } catch (exception: Exception) {
                      return@Callable null
                    }
              }
              if (Thread.interrupted()) {
                FLog.v(TAG, "Host thread was interrupted, decreasing reference count")
                result.close()
                throw InterruptedException()
              } else {
                return@Callable result
              }
            } catch (th: Throwable) {
              FrescoInstrumenter.markFailure(token, th)
              throw th
            } finally {
              FrescoInstrumenter.onEndWork(currentToken)
            }
          },
          readExecutor,
      )
    } catch (exception: Exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache read for %s", key.uriString)
      Task.forError(exception)
    }
  }

  /**
   * Associates encodedImage with given key in disk cache. Disk write is performed on background
   * thread, so the caller of this method is not blocked
   */
  fun put(key: CacheKey, encodedImage: EncodedImage): Unit =
      traceSection("BufferedDiskCache#put") {
        check(EncodedImage.isValid(encodedImage))

        // Store encodedImage in staging area
        stagingArea.put(key, encodedImage)

        // Write to disk cache. This will be executed on background thread, so increment the ref
        // count. When this write completes (with success/failure), then we will bump down the
        // ref count again.
        val finalEncodedImage = EncodedImage.cloneOrNull(encodedImage)
        try {
          val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_putAsync")
          writeExecutor.execute {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              writeToDiskCache(key, finalEncodedImage)
            } catch (th: Throwable) {
              FrescoInstrumenter.markFailure(token, th)
              throw th
            } finally {
              stagingArea.remove(key, finalEncodedImage!!)
              EncodedImage.closeSafely(finalEncodedImage)
              FrescoInstrumenter.onEndWork(currentToken)
            }
          }
        } catch (exception: Exception) {
          // We failed to enqueue cache write. Log failure and decrement ref count
          // TODO: 3697790
          FLog.w(TAG, exception, "Failed to schedule disk-cache write for %s", key.uriString)
          stagingArea.remove(key, encodedImage)
          EncodedImage.closeSafely(finalEncodedImage)
        }
      }

  /** Removes the item from the disk cache and the staging area. */
  fun remove(key: CacheKey): Task<Void> {
    stagingArea.remove(key)
    return try {
      val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_remove")
      Task.call(
          {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              stagingArea.remove(key)
              fileCache.remove(key)
            } catch (th: Throwable) {
              FrescoInstrumenter.markFailure(token, th)
              throw th
            } finally {
              FrescoInstrumenter.onEndWork(currentToken)
            }
            null
          },
          writeExecutor,
      )
    } catch (exception: Exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache remove for %s", key.uriString)
      Task.forError(exception)
    }
  }

  /** Clears the disk cache and the staging area. */
  fun clearAll(): Task<Void> {
    stagingArea.clearAll()
    val token = FrescoInstrumenter.onBeforeSubmitWork("BufferedDiskCache_clearAll")
    return try {
      Task.call(
          Callable {
            val currentToken = FrescoInstrumenter.onBeginWork(token, null)
            try {
              stagingArea.clearAll()
              fileCache.clearAll()
              return@Callable null
            } catch (th: Throwable) {
              FrescoInstrumenter.markFailure(token, th)
              throw th
            } finally {
              FrescoInstrumenter.onEndWork(currentToken)
            }
          },
          writeExecutor,
      )
    } catch (exception: Exception) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, exception, "Failed to schedule disk-cache clear")
      Task.forError(exception)
    }
  }

  val size: Long
    get() = fileCache.getSize()

  private fun foundPinnedImage(key: CacheKey, pinnedImage: EncodedImage): Task<EncodedImage> {
    FLog.v(TAG, "Found image for %s in staging area", key.uriString)
    imageCacheStatsTracker.onStagingAreaHit(key)
    return Task.forResult(pinnedImage)
  }

  /** Result of a disk cache read, carrying both the buffer and optional metadata. */
  private class DiskCacheReadResult(
      val buffer: PooledByteBuffer,
      val originSubcategory: String? = null,
  )

  /** Performs disk cache read. In case of any exception null is returned. */
  @Throws(IOException::class)
  private fun readFromDiskCache(key: CacheKey): DiskCacheReadResult? {
    return try {
      FLog.v(TAG, "Disk cache read for %s", key.uriString)
      val diskCacheResource = fileCache.getResource(key)
      if (diskCacheResource == null) {
        FLog.v(TAG, "Disk cache miss for %s", key.uriString)
        imageCacheStatsTracker.onDiskCacheMiss(key)
        return null
      } else {
        FLog.v(TAG, "Found entry in disk cache for %s", key.uriString)
        imageCacheStatsTracker.onDiskCacheHit(key)
      }
      val originSub = diskCacheResource.getExtra(HasExtraData.KEY_ORIGIN_SUBCATEGORY)
      val `is` = diskCacheResource.openStream()
      val byteBuffer =
          try {
            pooledByteBufferFactory.newByteBuffer(`is`, diskCacheResource.size().toInt())
          } finally {
            `is`.close()
          }
      FLog.v(TAG, "Successful read from disk cache for %s", key.uriString)
      DiskCacheReadResult(byteBuffer, originSub)
    } catch (ioe: IOException) {
      // TODO: 3697790 log failures
      // TODO: 5258772 - uncomment line below
      // mFileCache.remove(key);
      FLog.w(TAG, ioe, "Exception reading from cache for %s", key.uriString)
      imageCacheStatsTracker.onDiskCacheGetFail(key)
      throw ioe
    }
  }

  /**
   * Writes to disk cache
   *
   * @throws IOException
   */
  private fun writeToDiskCache(key: CacheKey, encodedImage: EncodedImage?) {
    FLog.v(TAG, "About to write to disk-cache for key %s", key.uriString)
    try {
      fileCache.insert(key) { os ->
        val inputStream = encodedImage!!.inputStream
        checkNotNull(inputStream)
        pooledByteStreams.copy(inputStream, os)
        maybeSaveMetadata(key, encodedImage)
      }
      imageCacheStatsTracker.onDiskCachePut(key)
      FLog.v(TAG, "Successful disk-cache write for key %s", key.uriString)
    } catch (ioe: IOException) {
      // Log failure
      // TODO: 3697790
      FLog.w(TAG, ioe, "Failed to write to disk-cache for key %s", key.uriString)
    }
  }

  private fun maybeSaveMetadata(key: CacheKey, image: EncodedImage) {
    if (!preserveMetadataDuringStartup && isAppStarting?.invoke() == true) {
      return
    }
    if (preserveMetadata) {
      image.getExtra<String>(HasExtraData.KEY_SF_QUERY)?.let { fileCache.setMetadata(key, it) }
    }
  }

  private fun maybeRestoreMetadata(key: CacheKey, image: EncodedImage) {
    if (!preserveMetadataDuringStartup && isAppStarting?.invoke() == true) {
      return
    }
    if (preserveMetadata) {
      fileCache.getMetadata(key)?.let { data -> image.putExtra(HasExtraData.KEY_SF_QUERY, data) }
    }
  }

  companion object {
    private val TAG: Class<*> = BufferedDiskCache::class.java
  }
}
