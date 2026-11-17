/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.cache.disk

import android.os.Environment
import androidx.annotation.StringDef
import androidx.annotation.VisibleForTesting
import com.facebook.binaryresource.BinaryResource
import com.facebook.binaryresource.FileBinaryResource
import com.facebook.binaryresource.FileBinaryResource.Companion.create
import com.facebook.binaryresource.FileBinaryResource.Companion.createOrNull
import com.facebook.cache.common.CacheErrorLogger
import com.facebook.cache.common.CacheErrorLogger.CacheErrorCategory
import com.facebook.cache.common.WriterCallback
import com.facebook.cache.disk.DiskStorage.DiskDumpInfo
import com.facebook.cache.disk.DiskStorage.DiskDumpInfoEntry
import com.facebook.common.file.FileTree
import com.facebook.common.file.FileTreeVisitor
import com.facebook.common.file.FileUtils
import com.facebook.common.file.FileUtils.ParentDirNotFoundException
import com.facebook.common.file.FileUtils.RenameException
import com.facebook.common.internal.CountingOutputStream
import com.facebook.common.internal.Preconditions
import com.facebook.common.time.Clock
import com.facebook.common.time.SystemClock
import com.facebook.infer.annotation.Nullsafe
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The default disk storage implementation. Subsumes both 'simple' and 'sharded' implementations via
 * a new SubdirectorySupplier.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
class DefaultDiskStorage(rootDirectory: File, version: Int, cacheErrorLogger: CacheErrorLogger) :
    DiskStorage {
  /** The base directory used for the cache */
  private val mRootDirectory: File

  /** True if cache is external */
  private val mIsExternal: Boolean

  /**
   * All the sharding occurs inside a version-directory. That allows for easy version upgrade. When
   * we find a base directory with no version-directory in it, it means that it's a different
   * version and we should delete the whole directory (including itself) for both reasons: 1) clear
   * all unusable files 2) avoid Samsung RFS problem that was hit with old implementations of
   * DiskStorage which used a single directory for all the files.
   */
  private val versionDirectory: File

  private val mCacheErrorLogger: CacheErrorLogger
  private val clock: Clock

  /**
   * Instantiates a ShardedDiskStorage that will use the directory to save a map between keys and
   * files. The version is very important if clients change the format saved in those files.
   * ShardedDiskStorage will assure that files saved with different version will be never used and
   * eventually removed.
   *
   * @param rootDirectory root directory to create all content under
   * @param version version of the format used in the files. If passed a different version files
   *   saved with the previous value will not be read and will be purged eventually.
   * @param cacheErrorLogger logger for various events
   */
  init {
    checkNotNull(rootDirectory)

    mRootDirectory = rootDirectory
    mIsExternal = isExternal(rootDirectory, cacheErrorLogger)
    // versionDirectory's name identifies:
    // - the cache structure's version (sharded)
    // - the content's version (version value)
    // if structure changes, prefix will change... if content changes version will be different
    // the ideal would be asking mSharding its name, but it's created receiving the directory
    versionDirectory = File(mRootDirectory, getVersionSubdirectoryName(version))
    mCacheErrorLogger = cacheErrorLogger
    recreateDirectoryIfVersionChanges()
    clock = SystemClock.get()
  }

  override fun isEnabled(): Boolean {
    return true
  }

  override fun isExternal(): Boolean {
    return mIsExternal
  }

  override fun getStorageName(): String {
    val directoryName = mRootDirectory.getAbsolutePath()
    return ("_" +
        directoryName.substring(directoryName.lastIndexOf('/') + 1, directoryName.length) +
        "_" +
        directoryName.hashCode())
  }

  /**
   * Checks if we have to recreate rootDirectory. This is needed because old versions of this
   * storage created too much different files in the same dir, and Samsung's RFS has a bug that
   * after the 13.000th creation fails. So if cache is not already in expected version let's destroy
   * everything (if not in expected version... there's nothing to reuse here anyway).
   */
  private fun recreateDirectoryIfVersionChanges() {
    var recreateBase = false
    if (!mRootDirectory.exists()) {
      recreateBase = true
    } else if (!versionDirectory.exists()) {
      recreateBase = true
      FileTree.deleteRecursively(mRootDirectory)
    }

    if (recreateBase) {
      try {
        FileUtils.mkdirs(versionDirectory)
      } catch (e: FileUtils.CreateDirectoryException) {
        // not the end of the world, when saving files we will try to create missing parent dirs
        mCacheErrorLogger.logError(
            CacheErrorCategory.WRITE_CREATE_DIR,
            TAG,
            "version directory could not be created: " + versionDirectory,
            null,
        )
      }
    }
  }

  private class IncompleteFileException(expected: Long, actual: Long) :
      IOException("File was not written completely. Expected: " + expected + ", found: " + actual)

  /** Calculates which should be the CONTENT file for the given key */
  @VisibleForTesting
  fun getContentFileFor(resourceId: String?): File {
    return File(getFilename(resourceId)!!)
  }

  /**
   * Gets the directory to use to store the given key
   *
   * @param resourceId the id of the file we're going to store
   * @return the directory to store the file in
   */
  private fun getSubdirectoryPath(resourceId: String): String {
    val subdirectory = abs((resourceId.hashCode() % SHARDING_BUCKET_COUNT).toDouble()).toString()
    return versionDirectory.toString() + File.separator + subdirectory
  }

  /**
   * Gets the directory to use to store the given key
   *
   * @param resourceId the id of the file we're going to store
   * @return the directory to store the file in
   */
  private fun getSubdirectory(resourceId: String?): File {
    return File(getSubdirectoryPath(resourceId!!))
  }

  /**
   * Implementation of [FileTreeVisitor] to iterate over all the sharded files and collect those
   * valid content files. It's used in entriesIterator method.
   */
  private inner class EntriesCollector : FileTreeVisitor {
    private val result: MutableList<DiskStorage.Entry> = ArrayList<DiskStorage.Entry>()

    override fun preVisitDirectory(directory: File?) = Unit

    override fun visitFile(file: File?) {
      val info = getShardFileInfo(file)
      if (info != null && info.type === FileType.Companion.CONTENT) {
        result.add(DefaultDiskStorage.EntryImpl(info.resourceId, file))
      }
    }

    override fun postVisitDirectory(directory: File?) = Unit

    val entries: MutableList<DiskStorage.Entry>
      /** Returns an immutable list of the entries. */
      get() = Collections.unmodifiableList<DiskStorage.Entry>(result)
  }

  /**
   * This implements a [FileTreeVisitor] to iterate over all the files in mDirectory and delete any
   * unexpected file or directory. It also gets rid of any empty directory in the shard. As a
   * shortcut it checks that things are inside (current) versionDirectory. If it's not then it's
   * directly deleted. If it's inside then it checks if it's a recognized file and if it's in the
   * correct shard according to its name (checkShard method). If it's unexpected file is deleted.
   */
  private inner class PurgingVisitor : FileTreeVisitor {
    private var insideBaseDirectory = false

    override fun preVisitDirectory(directory: File?) {
      if (!insideBaseDirectory && directory == versionDirectory) {
        // if we enter version-directory turn flag on
        insideBaseDirectory = true
      }
    }

    override fun visitFile(file: File?) {
      if (!insideBaseDirectory || !isExpectedFile(file)) {
        file!!.delete()
      }
    }

    override fun postVisitDirectory(directory: File?) {
      if (mRootDirectory != directory) { // if it's root directory we must not touch it
        if (!insideBaseDirectory) {
          // if not in version-directory then it's unexpected!
          directory!!.delete()
        }
      }
      if (insideBaseDirectory && directory == versionDirectory) {
        // if we just finished visiting version-directory turn flag off
        insideBaseDirectory = false
      }
    }

    fun isExpectedFile(file: File?): Boolean {
      val info = getShardFileInfo(file)
      if (info == null) {
        return false
      }
      if (info.type === FileType.Companion.TEMP) {
        return isRecentFile(file!!)
      }
      Preconditions.checkState(info.type === FileType.Companion.CONTENT)
      return true
    }

    /** @return true if and only if the file is not old enough to be considered an old temp file */
    fun isRecentFile(file: File): Boolean {
      return file.lastModified() > (clock.now() - TEMP_FILE_LIFETIME_MS)
    }
  }

  override fun purgeUnexpectedResources() {
    FileTree.walkFileTree(mRootDirectory, PurgingVisitor())
  }

  /**
   * Creates the directory (and its parents, if necessary). In case of an exception, log an error
   * message with the relevant parameters
   *
   * @param directory the directory to create
   * @param message message to use
   * @throws IOException
   */
  @Throws(IOException::class)
  private fun mkdirs(directory: File?, message: String?) {
    try {
      FileUtils.mkdirs(directory)
    } catch (cde: FileUtils.CreateDirectoryException) {
      mCacheErrorLogger.logError(CacheErrorCategory.WRITE_CREATE_DIR, TAG, message!!, cde)
      throw cde
    }
  }

  @Throws(IOException::class)
  override fun insert(resourceId: String, debugInfo: Any): DiskStorage.Inserter {
    // ensure that the parent directory exists
    val info = DefaultDiskStorage.FileInfo(FileType.Companion.TEMP, resourceId)
    val parent = getSubdirectory(info.resourceId)
    if (!parent.exists()) {
      mkdirs(parent, "insert")
    }

    try {
      val file = info.createTempFile(parent)
      return InserterImpl(resourceId, file)
    } catch (ioe: IOException) {
      mCacheErrorLogger.logError(CacheErrorCategory.WRITE_CREATE_TEMPFILE, TAG, "insert", ioe)
      throw ioe
    }
  }

  override fun getResource(resourceId: String, debugInfo: Any): BinaryResource? {
    val file = getContentFileFor(resourceId)
    if (file.exists()) {
      file.setLastModified(clock.now())
      return createOrNull(file)
    }
    return null
  }

  private fun getFilename(resourceId: String?): String? {
    val fileInfo = DefaultDiskStorage.FileInfo(FileType.Companion.CONTENT, resourceId!!)
    val path = getSubdirectoryPath(fileInfo.resourceId)
    return fileInfo.toPath(path)
  }

  override fun contains(resourceId: String, debugInfo: Any): Boolean {
    return query(resourceId, false)
  }

  override fun touch(resourceId: String, debugInfo: Any): Boolean {
    return query(resourceId, true)
  }

  private fun query(resourceId: String?, touch: Boolean): Boolean {
    val contentFile = getContentFileFor(resourceId)
    val exists = contentFile.exists()
    if (touch && exists) {
      contentFile.setLastModified(clock.now())
    }
    return exists
  }

  override fun remove(entry: DiskStorage.Entry): Long {
    // it should be one entry return by us :)
    val entryImpl = entry as EntryImpl
    val resource = entryImpl.getResource()
    return doRemove(resource.file)
  }

  override fun remove(resourceId: String): Long {
    return doRemove(getContentFileFor(resourceId))
  }

  private fun doRemove(contentFile: File): Long {
    if (!contentFile.exists()) {
      return 0
    }

    val fileSize = contentFile.length()
    if (contentFile.delete()) {
      return fileSize
    }

    return -1
  }

  override fun clearAll() {
    FileTree.deleteContents(mRootDirectory)
  }

  @Throws(IOException::class)
  override fun getDumpInfo(): DiskDumpInfo {
    val entries = getEntries()

    val dumpInfo = DiskDumpInfo()
    for (entry in entries) {
      val infoEntry = dumpCacheEntry(entry)
      val type = infoEntry.type
      val typeCount = dumpInfo.typeCounts.get(type)
      if (typeCount == null) {
        dumpInfo.typeCounts.put(type, 1)
      } else {
        dumpInfo.typeCounts.put(type, typeCount + 1)
      }
      dumpInfo.entries.add(infoEntry)
    }
    return dumpInfo
  }

  @Throws(IOException::class)
  private fun dumpCacheEntry(entry: DiskStorage.Entry?): DiskDumpInfoEntry {
    val entryImpl = entry as EntryImpl
    var firstBits = ""
    val bytes = entryImpl.getResource().read()
    val type = typeOfBytes(bytes)
    if (type == "undefined" && bytes.size >= 4) {
      firstBits =
          String.format(
              null as Locale?,
              "0x%02X 0x%02X 0x%02X 0x%02X",
              bytes[0],
              bytes[1],
              bytes[2],
              bytes[3],
          )
    }
    val path = entryImpl.getResource().file.getPath()
    return DiskDumpInfoEntry(
        entryImpl.getId(),
        path,
        type,
        entryImpl.getSize().toFloat(),
        firstBits,
    )
  }

  private fun typeOfBytes(bytes: ByteArray): String {
    if (bytes.size >= 2) {
      if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        return "jpg"
      } else if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
        return "png"
      } else if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()) {
        return "webp"
      } else if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte()) {
        return "gif"
      }
    }
    return "undefined"
  }

  @Throws(IOException::class)
  /**
   * Returns a list of entries.
   *
   * This list is immutable.
   */
  override fun getEntries(): MutableList<DiskStorage.Entry> {
    val collector = EntriesCollector()
    FileTree.walkFileTree(versionDirectory, collector)
    return collector.entries
  }

  /** Implementation of Entry listed by entriesIterator. */
  @VisibleForTesting
  class EntryImpl(id: String?, cachedFile: File?) : DiskStorage.Entry {
    private val id: String
    private val resource: FileBinaryResource
    private var size: Long
    private var timestamp: Long

    init {
      checkNotNull(cachedFile)
      this.id = checkNotNull(id)
      this.resource = FileBinaryResource.create(cachedFile)
      this.size = -1
      this.timestamp = -1
    }

    override fun getId(): String {
      return id
    }

    override fun getTimestamp(): Long {
      if (timestamp < 0) {
        val cachedFile = resource.file
        timestamp = cachedFile.lastModified()
      }
      return timestamp
    }

    override fun getResource(): FileBinaryResource {
      return resource
    }

    override fun getSize(): Long {
      if (size < 0) {
        size = resource.size()
      }
      return size
    }
  }

  /**
   * Checks that the file is placed in the correct shard according to its filename (and hence the
   * represented key). If it's correct its FileInfo is returned.
   *
   * @param file the file to check
   * @return the corresponding FileInfo object if shard is correct, null otherwise
   */
  private fun getShardFileInfo(file: File?): FileInfo? {
    val info: FileInfo? = FileInfo.Companion.fromFile(file!!)
    if (info == null) {
      return null // file with incorrect name/extension
    }
    val expectedDirectory = getSubdirectory(info.resourceId)
    val isCorrect = expectedDirectory == file.getParentFile()
    return if (isCorrect) info else null
  }

  /**
   * Categories for the different internal files a ShardedDiskStorage maintains. CONTENT: the file
   * that has the content TEMP: temporal files, used to write the content until they are switched to
   * CONTENT files
   */
  @StringDef(FileType.CONTENT, FileType.TEMP)
  annotation class FileType {
    companion object {
      const val CONTENT: String = CONTENT_FILE_EXTENSION
      const val TEMP: String = TEMP_FILE_EXTENSION
    }
  }

  /**
   * Holds information about the different files this storage uses (content, tmp). All file name
   * parsing should be done through here. Temp files creation is also handled here, to encapsulate
   * naming.
   */
  private class FileInfo(@field:FileType @param:FileType val type: String, val resourceId: String) {
    override fun toString(): String {
      return type + "(" + resourceId + ")"
    }

    fun toPath(parentPath: String?): String? {
      return parentPath + File.separator + resourceId + type
    }

    @Throws(IOException::class)
    fun createTempFile(parent: File?): File {
      val f = File.createTempFile(resourceId + ".", TEMP_FILE_EXTENSION, parent)
      return f
    }

    companion object {
      fun fromFile(file: File): FileInfo? {
        val name = file.getName()
        val pos = name.lastIndexOf('.')
        if (pos <= 0) {
          return null // no name part
        }
        val ext = name.substring(pos)
        @FileType val type: String? = getFileTypefromExtension(ext)
        if (type == null) {
          return null // unknown!
        }
        var resourceId = name.substring(0, pos)
        if (type == FileType.Companion.TEMP) {
          val numPos = resourceId.lastIndexOf('.')
          if (numPos <= 0) {
            return null // no resourceId.number
          }
          resourceId = resourceId.substring(0, numPos)
        }

        return FileInfo(type, resourceId)
      }
    }
  }

  @VisibleForTesting
  inner class InserterImpl(
      private val mResourceId: String, /* package protected*/
      @field:VisibleForTesting val mTemporaryFile: File,
  ) : DiskStorage.Inserter {
    @Throws(IOException::class)
    override fun writeData(callback: WriterCallback, debugInfo: Any) {
      val fileStream: FileOutputStream?
      try {
        fileStream = FileOutputStream(mTemporaryFile)
      } catch (fne: FileNotFoundException) {
        mCacheErrorLogger.logError(
            CacheErrorCategory.WRITE_UPDATE_FILE_NOT_FOUND,
            TAG,
            "updateResource",
            fne,
        )
        throw fne
      }

      val length: Long
      try {
        val countingStream = CountingOutputStream(fileStream)
        callback.write(countingStream)
        // just in case underlying stream's close method doesn't flush:
        // we flush it manually and inside the try/catch
        countingStream.flush()
        length = countingStream.getCount()
      } finally {
        // if it fails to close (or write the last piece) we really want to know
        // Normally we would want this to be quiet because a closing exception would hide one
        // inside the try, but now we really want to know if something fails at flush or close
        fileStream.close()
      }
      // this code should never throw, but if filesystem doesn't fail on a failing/uncomplete close
      // we want to know and manually fail
      if (mTemporaryFile.length() != length) {
        throw IncompleteFileException(length, mTemporaryFile.length())
      }
    }

    @Throws(IOException::class)
    override fun commit(debugInfo: Any): BinaryResource {
      return commit(debugInfo, clock.now())
    }

    @Throws(IOException::class)
    override fun commit(debugInfo: Any, time: Long): BinaryResource {
      // the temp resource must be ours!
      val targetFile = getContentFileFor(mResourceId)

      try {
        FileUtils.rename(mTemporaryFile, targetFile)
      } catch (re: RenameException) {
        val category: CacheErrorCategory?
        val cause = re.cause
        if (cause == null) {
          category = CacheErrorCategory.WRITE_RENAME_FILE_OTHER
        } else if (cause is ParentDirNotFoundException) {
          category = CacheErrorCategory.WRITE_RENAME_FILE_TEMPFILE_PARENT_NOT_FOUND
        } else if (cause is FileNotFoundException) {
          category = CacheErrorCategory.WRITE_RENAME_FILE_TEMPFILE_NOT_FOUND
        } else {
          category = CacheErrorCategory.WRITE_RENAME_FILE_OTHER
        }
        mCacheErrorLogger.logError(category, TAG, "commit", re)
        throw re
      }
      if (targetFile.exists()) {
        targetFile.setLastModified(time)
      }
      return create(targetFile)
    }

    override fun cleanUp(): Boolean {
      return !mTemporaryFile.exists() || mTemporaryFile.delete()
    }
  }

  companion object {
    private val TAG: Class<*> = DefaultDiskStorage::class.java

    private const val CONTENT_FILE_EXTENSION = ".cnt"
    private const val TEMP_FILE_EXTENSION = ".tmp"

    private const val DEFAULT_DISK_STORAGE_VERSION_PREFIX = "v2"

    /*
     * We use sharding to avoid Samsung's RFS problem, and to avoid having one big directory
     * containing thousands of files.
     * This number of directories is large enough based on the following reasoning:
     * - high usage: 150 photos per day
     * - such usage will hit Samsung's 6,500 photos cap in 43 days
     * - 100 buckets will extend that period to 4,300 days which is 11.78 years
     */
    private const val SHARDING_BUCKET_COUNT = 100

    /** We will allow purging of any temp files older than this. */
    val TEMP_FILE_LIFETIME_MS: Long = TimeUnit.MINUTES.toMillis(30)

    private fun isExternal(directory: File?, cacheErrorLogger: CacheErrorLogger?): Boolean {
      var state = false
      var appCacheDirPath: String? = null

      try {
        // Whitelisted use of external storage Android changes in Target SDK 29 and above as it
        // only used for getting the canonical path
        val extStoragePath = Environment.getExternalStorageDirectory()
        if (extStoragePath != null) {
          val cacheDirPath = extStoragePath.toString()
          try {
            appCacheDirPath = directory!!.getCanonicalPath()
            if (appCacheDirPath.contains(cacheDirPath)) {
              state = true
            }
          } catch (e: IOException) {
            cacheErrorLogger!!.logError(
                CacheErrorCategory.OTHER,
                TAG,
                "failed to read folder to check if external: " + appCacheDirPath,
                e,
            )
          }
        }
      } catch (e: Exception) {
        cacheErrorLogger!!.logError(
            CacheErrorCategory.OTHER,
            TAG,
            "failed to get the external storage directory!",
            e,
        )
      }
      return state
    }

    @VisibleForTesting
    fun getVersionSubdirectoryName(version: Int): String {
      return String.format(
          null as Locale?,
          "%s.ols%d.%d",
          DEFAULT_DISK_STORAGE_VERSION_PREFIX,
          SHARDING_BUCKET_COUNT,
          version,
      )
    }

    @FileType
    private fun getFileTypefromExtension(extension: String?): String? {
      if (CONTENT_FILE_EXTENSION == extension) {
        return FileType.Companion.CONTENT
      } else if (TEMP_FILE_EXTENSION == extension) {
        return FileType.Companion.TEMP
      }
      return null
    }
  }
}
