/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.tools.liveeditor

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.ColorInt
import com.facebook.drawee.backends.pipeline.info.ImageOrigin
import com.facebook.fresco.vito.core.impl.FrescoDrawable2Impl
import com.facebook.fresco.vito.core.impl.KFrescoVitoDrawable
import com.facebook.fresco.vito.core.impl.debug.DebugOverlayImageOriginColor
import com.facebook.fresco.vito.core.impl.debug.StringDebugDataProvider
import com.facebook.fresco.vito.core.impl.obtainExtras
import com.facebook.fresco.vito.options.ImageOptions
import com.facebook.fresco.vito.source.BitmapImageSource
import com.facebook.fresco.vito.source.ColorImageSource
import com.facebook.fresco.vito.source.DrawableImageSource
import com.facebook.fresco.vito.source.DrawableResImageSource
import com.facebook.fresco.vito.source.EmptyImageSource
import com.facebook.fresco.vito.source.FirstAvailableImageSource
import com.facebook.fresco.vito.source.ImageSource
import com.facebook.fresco.vito.source.IncreasingQualityImageSource
import com.facebook.fresco.vito.source.UriImageSource

class LiveEditorUiUtils(
    var liveEditor: ImageLiveEditor?,
    var debugDataProviders: List<@JvmSuppressWildcards StringDebugDataProvider>? = null,
) {

  fun createView(
      context: Context,
      customEntries: List<ImageOptionsSampleValues.Entry<out Any>> = emptyList(),
      title: String? = null,
  ): View =
      createScrollingList(context, title = title) {
        addView(createWithList(context, ImageSourceSampleValues.entries))
        addView(createWithList(context, ImageOptionsSampleValues.roundingOptions))
        addView(createWithList(context, ImageOptionsSampleValues.borderOptions))
        addView(createWithList(context, ImageOptionsSampleValues.scaleTypes))
        addView(createWithList(context, ImageOptionsSampleValues.colorFilters))
        addView(createWithList(context, ImageOptionsSampleValues.fadingOptions))
        addView(createWithList(context, ImageOptionsSampleValues.autoPlay))
        addView(createWithList(context, ImageOptionsSampleValues.bitmapConfig))
        addView(createWithList(context, ImageOptionsSampleValues.resizeToViewportConfig))
        addView(
            createWithList(context, ImageOptionsSampleValues.localThumbnailPreviewsEnabledConfig),
        )
        addView(createWithList(context, ImageOptionsSampleValues.progressiveRenderingEnabledConfig))
        addView(createWithList(context, ImageOptionsSampleValues.placeholderColors))

        customEntries.forEach { addView(createWithList(context, it)) }
      }

  private fun createScrollingList(
      context: Context,
      title: String? = null,
      block: LinearLayout.() -> Unit,
  ): View =
      ScrollView(context).apply {
        layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(24.dpToPx(context))
        addView(
            LinearLayout(context).apply {
              layoutParams =
                  LinearLayout.LayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                  )
              orientation = LinearLayout.VERTICAL
              val horizontalPad = 4.dpToPx(context)
              val topPad = 12.dpToPx(context)
              setPadding(horizontalPad, topPad, horizontalPad, 0)
              if (title != null) {
                addView(
                    TextView(context).apply {
                      text = title
                      textSize = 18f
                      setTypeface(typeface, android.graphics.Typeface.BOLD)
                      setTextColor(dialogTextColor(context))
                      val titleHorizontalPad = 12.dpToPx(context)
                      val titleBottomPad = 6.dpToPx(context)
                      setPadding(titleHorizontalPad, 0, titleHorizontalPad, titleBottomPad)
                    },
                )
              }
              block(this)
            },
        )
      }

  fun createImageOptionsView(context: Context, title: String? = null): View =
      createScrollingList(context, title = title) {
        val info: List<Pair<String, String>> =
            liveEditor?.let { editor -> extractImageOptionsInfo(editor.getOptions()) }
                ?: emptyList()

        if (info.isEmpty()) {
          addView(
              TextView(context).apply {
                @SuppressLint("SetTextI18n")
                text = "No image options available"
                setTextColor(dialogTextColor(context))
              },
          )
        }
        info.forEach { infoItem ->
          val view = ImageSourceUiUtil(context).createImageInfoView(infoItem, this)
          addView(view)
        }
      }

  /**
   * Extracts the image origin string from the current drawable.
   *
   * For [KFrescoVitoDrawable] (modern Vito path), origin is read from `dataSource.extras` which is
   * populated by the image pipeline. If no data source extras are available (bitmap memory cache
   * shortcut), falls back to `obtainExtras().shortcutExtras`.
   *
   * For [FrescoDrawable2Impl] (legacy Java path), origin is read from the `imageOrigin` int field
   * which is populated by a `RequestListener`. The int is mapped to a string matching the keys in
   * [DebugOverlayImageOriginColor].
   *
   * Returns origin strings matching [DebugOverlayImageOriginColor] keys: "network", "disk",
   * "memory_encoded", "memory_bitmap", "local", or "unknown".
   */
  private fun extractImageOrigin(): String {
    val drawable = liveEditor?.drawable ?: return "unknown"
    if (drawable is KFrescoVitoDrawable) {
      val originExtras = drawable.dataSource?.extras ?: drawable.obtainExtras().shortcutExtras
      return originExtras?.get("origin")?.toString() ?: "unknown"
    }
    if (drawable is FrescoDrawable2Impl) {
      return when (drawable.imageOrigin) {
        ImageOrigin.NETWORK -> "network"
        ImageOrigin.DISK -> "disk"
        ImageOrigin.MEMORY_ENCODED -> "memory_encoded"
        ImageOrigin.MEMORY_BITMAP -> "memory_bitmap"
        ImageOrigin.MEMORY_BITMAP_SHORTCUT -> "memory_bitmap_shortcut"
        ImageOrigin.LOCAL -> "local"
        else -> "unknown"
      }
    }
    return "unknown"
  }

  /**
   * Creates a [TextView] showing the cache source of the current image as a color-coded emoji
   * indicator. Extracts the origin from the drawable's extras using the same mechanism as the
   * Fresco debug overlays.
   *
   * For [KFrescoVitoDrawable], origin lives in `dataSource.extras` (from the image pipeline) or in
   * shortcut extras (for bitmap memory cache hits). For [FrescoDrawable2Impl], origin lives in the
   * `imageOrigin` int field. The `FrescoDrawableInterface.extras` property is not used for origin
   * by either implementation.
   *
   * Example output: "🔴 Network" or "🟢 Bitmap memory cache"
   */
  private fun createCacheSourceIndicator(context: Context): TextView {
    val origin = extractImageOrigin()
    // Normalize "memory_bitmap_shortcut" to "memory_bitmap" for color lookup since
    // DebugOverlayImageOriginColor doesn't have an entry for the shortcut variant.
    val colorKey = if (origin == "memory_bitmap_shortcut") "memory_bitmap" else origin
    val color = DebugOverlayImageOriginColor.getImageOriginColor(colorKey)
    val emoji = colorToEmoji(color)
    val label = originLabel(origin)

    return TextView(context).apply {
      @SuppressLint("SetTextI18n")
      text = "$emoji $label ${ImageSourceUiUtil.COPY_GLYPH}"
      textSize = 16f
      setTypeface(typeface, Typeface.BOLD)
      setTextColor(dialogTextColor(context))
      val pad = 12.dpToPx(context)
      setPadding(pad, 0, pad, pad)
      setOnClickListener { ImageSourceUiUtil.copyToClipboard(context, "Cache source", label) }
    }
  }

  fun createImageInfoView(context: Context, title: String? = null): View =
      createScrollingList(context, title = title) {
        // 0. Cache source indicator (color-coded emoji)
        addView(createCacheSourceIndicator(context))

        // 1. ImageSource info — typed + recursive (source type + per-source URLs).
        var info: List<Pair<String, String>> = sourceInfoRows(liveEditor?.getSource())

        // 2. Debug provider data
        liveEditor?.let { liveEditorNonNull ->
          debugDataProviders?.forEach { debugProvider ->
            val debugData: Pair<String, String> =
                Pair(debugProvider.longName, debugProvider.extractData(liveEditorNonNull.drawable))
            info = info + debugData
          }
        }

        // Render all info
        if (info.isEmpty()) {
          addView(
              TextView(context).apply {
                text = "Source is Empty"
                setTextColor(dialogTextColor(context))
              },
          )
        }
        info.forEach { infoItem ->
          val view = ImageSourceUiUtil(context).createImageInfoView(infoItem, this)
          addView(view)
        }
      }

  private fun <T> createWithList(
      context: Context,
      entry: ImageOptionsSampleValues.Entry<T>,
  ): Spinner {
    return Spinner(context).apply {
      adapter =
          ArrayAdapter(
              context,
              android.R.layout.simple_spinner_dropdown_item,
              listOf(entry.name + ": original") + entry.data.map { entry.name + ": " + it.first },
          )
      onItemSelectedListener =
          object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
              if (position > 0) {
                liveEditor?.editOptions(context) {
                  entry.updateFunction(it, entry.data[position - 1].second)
                }
              }
            }
          }
    }
  }

  private fun <T> createWithList(
      context: Context,
      entry: ImageSourceSampleValues.Entry<T>,
  ): Spinner {
    return Spinner(context).apply {
      adapter =
          ArrayAdapter(
              context,
              android.R.layout.simple_spinner_dropdown_item,
              listOf(entry.name + ": original") + entry.data.map { entry.name + ": " + it.first },
          )
      onItemSelectedListener =
          object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
              if (position == 0) {
                liveEditor?.editSource(context) { liveEditor?.getOriginalSource() ?: it }
              } else {
                liveEditor?.editSource(context) {
                  entry.updateFunction(it, entry.data[position - 1].second)
                }
              }
            }
          }
    }
  }

  private fun extractImageOptionsInfo(options: ImageOptions): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()

    // Parse the toString() output which is maintained alongside ImageOptions class.
    // Format: "ImageOptions{ImageOptions{key=value, key2=value2, ...}}" — the outer wrapper
    // comes from ImageOptions.toString() and the inner one from Guava's ToStringHelper.
    var content = options.toString().removePrefix("ImageOptions{").removeSuffix("}")
    content = content.removePrefix("ImageOptions{").removeSuffix("}")

    // Parse key=value pairs. We split on ", " but need to handle nested objects
    // that may contain commas (e.g., "RoundingOptions{...}").
    parseKeyValuePairs(content).forEach { (key, value) -> result.add(key to value) }

    return result
  }

  private fun parseKeyValuePairs(content: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    var remaining = content.trim()

    while (remaining.isNotEmpty()) {
      // Find the key (everything before '=')
      val equalsIndex = remaining.indexOf('=')
      if (equalsIndex == -1) {
        break
      }

      val key = remaining.substring(0, equalsIndex).trim()
      remaining = remaining.substring(equalsIndex + 1)

      // Find the value - handle nested braces
      val (value, rest) = extractValue(remaining)
      result.add(key to value)
      remaining = rest.trimStart(',', ' ')
    }

    return result
  }

  private fun extractValue(s: String): Pair<String, String> {
    var braceDepth = 0
    var i = 0

    while (i < s.length) {
      val codePoint = s.codePointAt(i)
      when (codePoint) {
        '{'.code -> braceDepth++
        '}'.code -> braceDepth--
        ','.code ->
            if (braceDepth == 0) {
              return s.substring(0, i).trim() to s.substring(i)
            }
      }
      i += Character.charCount(codePoint)
    }

    return s.trim() to ""
  }

  companion object {
    // Debug tool overlay text colors — chosen to contrast the DIALOG_BACKGROUND_* colors in
    // LiveEditorOnScreenButtonController. Kept as literals because the live editor renders
    // into a raw overlay Context that does not carry a Material/AppCompat theme, so system
    // text-color attributes cannot be relied on.
    @SuppressLint("HexColorValueUsage")
    @ColorInt
    private const val DIALOG_TEXT_NIGHT = 0xFFF0F2F5.toInt()
    @SuppressLint("HexColorValueUsage")
    @ColorInt
    private const val DIALOG_TEXT_DAY = 0xFF1E1E2E.toInt()

    @ColorInt
    internal fun dialogTextColor(context: Context): Int {
      val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      return if (nightMode == Configuration.UI_MODE_NIGHT_YES) DIALOG_TEXT_NIGHT
      else DIALOG_TEXT_DAY
    }

    /**
     * Returns a human-readable label for the image origin string.
     *
     * Origin strings come from the Fresco pipeline and match the keys in
     * [DebugOverlayImageOriginColor]. This mapping provides user-friendly labels for the live
     * editor's image info dialog.
     */
    private fun originLabel(origin: String): String =
        when (origin) {
          "network" -> "Network"
          "disk" -> "Disk cache"
          "memory_encoded" -> "Encoded memory cache"
          "memory_bitmap" -> "Bitmap memory cache"
          "memory_bitmap_shortcut" -> "Bitmap memory cache (shortcut)"
          "local" -> "Local"
          else -> "Unknown"
        }

    /**
     * Returns the emoji circle corresponding to the given color from
     * [DebugOverlayImageOriginColor].
     *
     * Uses the same color scheme as the existing Fresco debug overlays: RED = network (slowest),
     * YELLOW = disk/encoded memory, GREEN = bitmap memory/local (fastest), GRAY = unknown.
     */
    // Debug tool — color constants are from DebugOverlayImageOriginColor, not user-facing UI
    @SuppressLint("ColorConstantUsageIssue")
    private fun colorToEmoji(@androidx.annotation.ColorInt color: Int): String =
        when (color) {
          Color.RED -> "\uD83D\uDD34" // 🔴
          Color.YELLOW -> "\uD83D\uDFE1" // 🟡
          Color.GREEN -> "\uD83D\uDFE2" // 🟢
          Color.GRAY -> "⚫" // ⚫
          else -> "⚪" // ⚪
        }

    internal fun Int.dpToPxF(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            context.resources.displayMetrics,
        )

    internal fun Int.dpToPx(context: Context): Int = dpToPxF(context).toInt()

    /**
     * Flattens an [ImageSource] into Info-tab rows: a "Source type" row (top-level
     * [ImageSource.getClassNameString]) followed by one row per constituent source. Composites are
     * unwrapped recursively — [IncreasingQualityImageSource] into low-res/high-res rows,
     * [FirstAvailableImageSource] into indexed rows. Reads typed accessors directly instead of the
     * fragile toString parse (URLs contain `=`/`,` which that parser shredded). Custom or future
     * subtypes not handled above fall back to a [Object.toString] row so nothing is silently
     * dropped during debugging. Empty for null.
     */
    internal fun sourceInfoRows(source: ImageSource?): List<Pair<String, String>> {
      if (source == null) return emptyList()
      return listOf("Source type" to source.getClassNameString()) + sourceUrlRows(source, "")
    }

    private fun sourceUrlRows(source: ImageSource, suffix: String): List<Pair<String, String>> =
        when (source) {
          is IncreasingQualityImageSource ->
              sourceUrlRows(source.lowResSource, "$suffix (low-res)") +
                  sourceUrlRows(source.highResSource, "$suffix (high-res)")
          is FirstAvailableImageSource ->
              source.imageSources.flatMapIndexed { i, inner ->
                sourceUrlRows(inner, "$suffix (${i + 1}/${source.imageSources.size})")
              }
          is UriImageSource -> listOf("Image URL$suffix" to source.imageUri.toString())
          is BitmapImageSource ->
              listOf("Image (bitmap)$suffix" to "${source.bitmap.width}x${source.bitmap.height}")
          is ColorImageSource -> listOf("Image (color)$suffix" to "#%08X".format(source.color))
          is DrawableResImageSource -> listOf("Image (resource)$suffix" to "resId=${source.resId}")
          is DrawableImageSource -> listOf("Image (drawable)$suffix" to source.drawable.toString())
          is EmptyImageSource -> emptyList()
          else -> listOf("Image (source)$suffix" to source.toString())
        }
  }
}
