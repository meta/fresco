/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.samples.comparison

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.facebook.common.logging.FLog
import com.facebook.common.logging.FLog.minimumLoggingLevel
import com.facebook.samples.comparison.adapters.AQueryAdapter
import com.facebook.samples.comparison.adapters.FrescoAdapter
import com.facebook.samples.comparison.adapters.GlideAdapter
import com.facebook.samples.comparison.adapters.ImageListAdapter
import com.facebook.samples.comparison.adapters.PicassoAdapter
import com.facebook.samples.comparison.adapters.UilAdapter
import com.facebook.samples.comparison.adapters.VolleyAdapter
import com.facebook.samples.comparison.configs.imagepipeline.ImagePipelineConfigFactory
import com.facebook.samples.comparison.instrumentation.PerfListener
import com.facebook.samples.comparison.urlsfetcher.ImageFormat
import com.facebook.samples.comparison.urlsfetcher.ImageSize
import com.facebook.samples.comparison.urlsfetcher.ImageUrlsFetcher
import com.facebook.samples.comparison.urlsfetcher.ImageUrlsRequestBuilder
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {
  private var handler: Handler? = null
  private var statsClockTickRunnable: Runnable? = null

  private var statsDisplay: TextView? = null
  private var loaderSelect: Spinner? = null
  private var sourceSelect: Spinner? = null

  private var hasStoragePermissions = false
  private var requestedLocalSource = false
  private var allowAnimations = false
  private var currentLoaderAdapterIndex = 0
  private var currentSourceAdapterIndex = 0

  private var currentAdapter: ImageListAdapter? = null
  private var recyclerView: RecyclerView? = null

  private var perfListener: PerfListener? = null

  private var imageUrls: MutableList<String?> = ArrayList<String?>()

  private var urlsLocal = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    recyclerView = findViewById<View?>(R.id.image_grid) as RecyclerView
    recyclerView!!.setLayoutManager(GridLayoutManager(this, COLS_NUMBER))

    minimumLoggingLevel = FLog.WARN
    Drawables.init(getResources())

    perfListener = PerfListener()
    allowAnimations = true
    currentLoaderAdapterIndex = 0
    currentSourceAdapterIndex = 0
    if (savedInstanceState != null) {
      allowAnimations = savedInstanceState.getBoolean(EXTRA_ALLOW_ANIMATIONS)
      currentLoaderAdapterIndex = savedInstanceState.getInt(EXTRA_CURRENT_ADAPTER_INDEX)
      currentSourceAdapterIndex = savedInstanceState.getInt(EXTRA_CURRENT_SOURCE_ADAPTER_INDEX)
    }

    handler = Handler(Looper.getMainLooper())
    statsClockTickRunnable =
        object : Runnable {
          override fun run() {
            updateStats()
            scheduleNextStatsClockTick()
          }
        }

    currentAdapter = null

    statsDisplay = findViewById<View?>(R.id.stats_display) as TextView
    loaderSelect = findViewById<View?>(R.id.loader_select) as Spinner
    loaderSelect!!.setOnItemSelectedListener(
        object : AdapterView.OnItemSelectedListener {
          override fun onItemSelected(
              parent: AdapterView<*>?,
              view: View?,
              position: Int,
              id: Long,
          ) {
            setLoaderAdapter(position)
          }

          override fun onNothingSelected(parent: AdapterView<*>?) {}
        },
    )
    loaderSelect!!.setSelection(currentLoaderAdapterIndex)

    sourceSelect = findViewById<View?>(R.id.source_select) as Spinner
    sourceSelect!!.setOnItemSelectedListener(
        object : AdapterView.OnItemSelectedListener {
          override fun onItemSelected(
              parent: AdapterView<*>?,
              view: View?,
              position: Int,
              id: Long,
          ) {
            setSourceAdapter(position)
          }

          override fun onNothingSelected(parent: AdapterView<*>?) {}
        },
    )
    sourceSelect!!.setSelection(currentSourceAdapterIndex)
    val storagePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Manifest.permission.READ_MEDIA_IMAGES
        } else {
          Manifest.permission.READ_EXTERNAL_STORAGE
        }
    hasStoragePermissions =
        (ContextCompat.checkSelfPermission(this, storagePermission) ==
            PackageManager.PERMISSION_GRANTED)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(EXTRA_ALLOW_ANIMATIONS, allowAnimations)
    outState.putInt(EXTRA_CURRENT_ADAPTER_INDEX, currentLoaderAdapterIndex)
    outState.putInt(EXTRA_CURRENT_SOURCE_ADAPTER_INDEX, currentSourceAdapterIndex)
  }

  override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    getMenuInflater().inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.allow_animations).setChecked(allowAnimations)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    val id = item.getItemId()

    if (id == R.id.allow_animations) {
      setAllowAnimations(!item.isChecked())
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onStart() {
    super.onStart()
    updateStats()
    scheduleNextStatsClockTick()
  }

  override fun onStop() {
    super.onStop()
    cancelNextStatsClockTick()
  }

  @VisibleForTesting
  fun setAllowAnimations(allowAnimations: Boolean) {
    this.allowAnimations = allowAnimations
    supportInvalidateOptionsMenu()
    updateAdapter(null)
    loadUrls()
  }

  private fun requestStoragePermissions() {
    if (hasStoragePermissions) {
      return
    }

    val storagePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Manifest.permission.READ_MEDIA_IMAGES
        } else {
          Manifest.permission.READ_EXTERNAL_STORAGE
        }
    ActivityCompat.requestPermissions(
        this,
        arrayOf<String>(storagePermission),
        PERMISSION_REQUEST_CODE,
    )
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<String>,
      grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    val storagePermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          Manifest.permission.READ_MEDIA_IMAGES
        } else {
          Manifest.permission.READ_EXTERNAL_STORAGE
        }
    for (i in permissions.indices) {
      if (permissions[i] == storagePermission) {
        hasStoragePermissions = grantResults[i] == PackageManager.PERMISSION_GRANTED
        if (hasStoragePermissions && requestedLocalSource) {
          requestedLocalSource = false
          setSourceAdapter(LOCAL_INDEX)
        } else if (!hasStoragePermissions && requestedLocalSource) {
          // If the user chose to deny the permission, update the source selection to none for
          // visual consistency.
          sourceSelect!!.setSelection(NONE_INDEX)
        }
      }
    }
  }

  private fun resetAdapter() {
    if (currentAdapter != null) {
      currentAdapter!!.shutDown()
      currentAdapter = null
      System.gc()
    }
  }

  private fun setLoaderAdapter(index: Int) {
    FLog.v(TAG, "onImageLoaderSelect: %d", index)
    resetAdapter()
    currentLoaderAdapterIndex = index
    perfListener = PerfListener()
    when (index) {
      FRESCO_INDEX,
      FRESCO_OKHTTP_INDEX ->
          currentAdapter =
              FrescoAdapter(
                  this,
                  perfListener,
                  if (index == FRESCO_INDEX) ImagePipelineConfigFactory.getImagePipelineConfig(this)
                  else ImagePipelineConfigFactory.getOkHttpImagePipelineConfig(this),
              )
      GLIDE_INDEX -> currentAdapter = GlideAdapter(this, perfListener)
      PICASSO_INDEX -> currentAdapter = PicassoAdapter(this, perfListener)
      UIL_INDEX -> currentAdapter = UilAdapter(this, perfListener)
      VOLLEY_INDEX -> currentAdapter = VolleyAdapter(this, perfListener)
      AQUERY_INDEX -> currentAdapter = AQueryAdapter(this, perfListener)
      else -> {
        currentAdapter = null
        return
      }
    }
    recyclerView!!.setAdapter(currentAdapter)

    updateAdapter(imageUrls)

    updateStats()
  }

  private fun setSourceAdapter(index: Int) {
    FLog.v(TAG, "onImageSourceSelect: %d", index)

    currentSourceAdapterIndex = index
    when (index) {
      NETWORK_INDEX -> urlsLocal = false
      LOCAL_INDEX -> urlsLocal = true
      else -> {
        resetAdapter()
        imageUrls.clear()
        return
      }
    }

    loadUrls()
    setLoaderAdapter(currentLoaderAdapterIndex)
  }

  private fun loadUrls() {
    if (urlsLocal) {
      if (!hasStoragePermissions) {
        requestedLocalSource = true
        requestStoragePermissions()
        return
      }

      loadLocalUrls()
    } else {
      requestedLocalSource = false
      loadNetworkUrls()
    }
  }

  private fun scheduleNextStatsClockTick() {
    handler!!.postDelayed(statsClockTickRunnable!!, STATS_CLOCK_INTERVAL_MS)
  }

  private fun cancelNextStatsClockTick() {
    handler!!.removeCallbacks(statsClockTickRunnable!!)
  }

  private fun chooseImageSize(): ImageSize {
    val layoutParams = recyclerView!!.getLayoutParams()
    if (layoutParams == null) {
      return ImageSize.LARGE_THUMBNAIL
    }
    val size: Int = calcDesiredSize(this, layoutParams.width, layoutParams.height)
    if (size <= 90) {
      return ImageSize.SMALL_SQUARE
    } else if (size <= 160) {
      return ImageSize.SMALL_THUMBNAIL
    } else if (size <= 320) {
      return ImageSize.MEDIUM_THUMBNAIL
    } else if (size <= 640) {
      return ImageSize.LARGE_THUMBNAIL
    } else if (size <= 1024) {
      return ImageSize.HUGE_THUMBNAIL
    } else {
      return ImageSize.ORIGINAL_IMAGE
    }
  }

  private fun loadNetworkUrls() {
    val url = "https://api.imgur.com/3/gallery/hot/viral/0.json"
    val staticSize = chooseImageSize()
    val builder =
        ImageUrlsRequestBuilder(url)
            .addImageFormat(ImageFormat.JPEG, staticSize)
            .addImageFormat(ImageFormat.PNG, staticSize)
    if (allowAnimations) {
      builder.addImageFormat(ImageFormat.GIF, ImageSize.ORIGINAL_IMAGE)
    }
    ImageUrlsFetcher.getImageUrls(
        builder.build(),
        object : ImageUrlsFetcher.Callback {
          override fun onFinish(result: MutableList<String?>) {
            // If the user changes to local images before the call comes back, then this should
            // be ignored
            if (!urlsLocal) {
              imageUrls = result
              updateAdapter(imageUrls)
            }
          }
        },
    )
  }

  private fun loadLocalUrls() {
    val externalContentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf<String?>(MediaStore.Images.Media._ID)
    var cursor: Cursor? = null
    try {
      cursor = getContentResolver().query(externalContentUri, projection, null, null, null)

      imageUrls.clear()

      val columnIndex = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

      var imageId: String?
      var imageUri: Uri
      while (cursor.moveToNext()) {
        imageId = cursor.getString(columnIndex)
        imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
        imageUrls.add(imageUri.toString())
      }
    } finally {
      if (cursor != null) {
        cursor.close()
      }
    }
  }

  private fun updateAdapter(urls: MutableList<String?>?) {
    if (currentAdapter != null) {
      currentAdapter!!.clear()
      if (urls != null) {
        for (url in urls) {
          currentAdapter!!.addUrl(url)
        }
      }
      currentAdapter!!.notifyDataSetChanged()
    }
  }

  private fun updateStats() {
    val runtime = Runtime.getRuntime()
    val heapMemory = runtime.totalMemory() - runtime.freeMemory()
    val sb: StringBuilder = StringBuilder(DEFAULT_MESSAGE_SIZE)
    // When changing format of output below, make sure to sync "run_comparison.py" as well
    sb.append("Heap: ")
    appendSize(sb, heapMemory)
    sb.append(" Java ")
    appendSize(sb, Debug.getNativeHeapSize())
    sb.append(" native\n")
    appendTime(sb, "Avg wait time: ", perfListener!!.getAverageWaitTime(), "\n")
    appendNumber(sb, "Requests: ", perfListener!!.getOutstandingRequests(), " outsdng ")
    appendNumber(sb, "", perfListener!!.getCancelledRequests(), " cncld\n")
    val message = sb.toString()
    statsDisplay!!.setText(message)
    FLog.i(TAG, message)
  }

  val displayHeight: Int
    /** Determines display's height. */
    get() {
      val display = getWindowManager().getDefaultDisplay()
      val size = Point()
      display.getSize(size)
      return size.y
    }

  companion object {
    private const val TAG = "FrescoSample"

    private const val PERMISSION_REQUEST_CODE = 42

    // These need to be in sync with {@link R.array.image_loaders}
    const val FRESCO_INDEX: Int = 1
    const val FRESCO_OKHTTP_INDEX: Int = 2
    const val GLIDE_INDEX: Int = 3
    const val PICASSO_INDEX: Int = 4
    const val UIL_INDEX: Int = 5
    const val VOLLEY_INDEX: Int = 6
    const val AQUERY_INDEX: Int = 7

    // These need to be in sync with {@link R.array.image_sources}
    const val NONE_INDEX: Int = 0
    const val NETWORK_INDEX: Int = 1
    const val LOCAL_INDEX: Int = 2

    private const val COLS_NUMBER = 3

    private const val STATS_CLOCK_INTERVAL_MS: Long = 1000
    private const val DEFAULT_MESSAGE_SIZE = 1024
    private val BYTES_IN_MEGABYTE = 1024 * 1024

    private const val EXTRA_ALLOW_ANIMATIONS = "allow_animations"
    private const val EXTRA_CURRENT_ADAPTER_INDEX = "current_adapter_index"
    private const val EXTRA_CURRENT_SOURCE_ADAPTER_INDEX = "current_source_adapter_index"

    @JvmStatic
    fun calcDesiredSize(context: Context, parentWidth: Int, parentHeight: Int): Int {
      val orientation = context.getResources().getConfiguration().orientation
      val desiredSize =
          if (orientation == Configuration.ORIENTATION_LANDSCAPE) parentHeight / 2
          else parentHeight / 3
      return min(desiredSize.toDouble(), parentWidth.toDouble()).toInt()
    }

    private fun appendSize(sb: StringBuilder, bytes: Long) {
      val value = String.format(Locale.getDefault(), "%.1f MB", bytes.toFloat() / BYTES_IN_MEGABYTE)
      sb.append(value)
    }

    private fun appendTime(sb: StringBuilder, prefix: String?, timeMs: Long, suffix: String?) {
      appendValue(sb, prefix, timeMs.toString() + " ms", suffix)
    }

    private fun appendNumber(sb: StringBuilder, prefix: String?, number: Long, suffix: String?) {
      appendValue(sb, prefix, number.toString() + "", suffix)
    }

    private fun appendValue(sb: StringBuilder, prefix: String?, value: String?, suffix: String?) {
      sb.append(prefix).append(value).append(suffix)
    }
  }
}
