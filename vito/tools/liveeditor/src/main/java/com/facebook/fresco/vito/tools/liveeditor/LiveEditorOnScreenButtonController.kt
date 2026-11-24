/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.tools.liveeditor

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.ColorInt
import com.facebook.common.internal.Supplier
import com.facebook.fresco.vito.core.FrescoDrawableInterface
import com.facebook.fresco.vito.core.impl.debug.StringDebugDataProvider
import com.facebook.fresco.vito.provider.FrescoVitoProvider
import com.facebook.fresco.vito.tools.liveeditor.ImageOptionsSampleValues.Entry
import com.facebook.fresco.vito.tools.liveeditor.LiveEditorUiUtils.Companion.dpToPx
import com.facebook.fresco.vito.tools.liveeditor.LiveEditorUiUtils.Companion.dpToPxF
import kotlin.math.abs

/**
 * Styling configuration for the live editor overlay buttons.
 *
 * Each app (FB, IG, FBLite) must provide their own branded colors and icons when constructing this
 * style.
 *
 * @param textColor Color for button icons/text
 * @param backgroundColor Color for button backgrounds
 * @param editorBackgroundColor Color for the editor panel background
 * @param iconPrevious Drawable resource for the "previous image" button
 * @param iconNext Drawable resource for the "next image" button
 * @param iconEdit Drawable resource for the "edit image" button
 * @param iconCode Drawable resource for the "image options" button
 * @param iconInfo Drawable resource for the "image info" button
 * @param iconClose Drawable resource for the "close" button
 */
data class ButtonStyle(
    @param:ColorInt val textColor: Int,
    @param:ColorInt val backgroundColor: Int,
    @param:ColorInt val editorBackgroundColor: Int,
    val iconPrevious: Int,
    val iconNext: Int,
    val iconEdit: Int,
    val iconCode: Int = android.R.drawable.ic_menu_manage,
    val iconInfo: Int,
    val iconClose: Int = android.R.drawable.ic_menu_close_clear_cancel,
)

class LiveEditorOnScreenButtonController(
    private val isEnabled: Supplier<Boolean>,
    private val buttonStyle: ButtonStyle,
    additionalButtonConfig: ButtonConfig? = null,
    private val customOptions: CustomOptions,
    private val debugDataProviders: List<@JvmSuppressWildcards StringDebugDataProvider> =
        emptyList(),
    private val showOverlayPermissionMessage: ((Context) -> Unit) = {
      Toast.makeText(
              it,
              "In order to use Image Live Editing, you must allow your app to 'Display over other apps' via Android settings",
              Toast.LENGTH_LONG,
          )
          .show()
    },
) {

  companion object {
    // Minimum movement in pixels to consider as a drag gesture (vs a tap)
    private const val DRAG_TOUCH_SLOP = 10
    // Debug tool overlay colors — not a user-facing FDS component
    @SuppressLint("HexColorValueUsage")
    @ColorInt
    private const val DIALOG_BACKGROUND_LIGHT = 0xFFF0F2F5.toInt()
    @SuppressLint("HexColorValueUsage")
    @ColorInt
    private const val DIALOG_BACKGROUND_DARK = 0xFF1E1E2E.toInt()
  }

  /**
   * Secondary constructor for backward compatibility with FBLite Java code. Uses Android system
   * icons as fallbacks since FBLite doesn't provide custom icons.
   */
  constructor(
      isEnabled: Supplier<Boolean>,
      @ColorInt buttonTextColor: Int,
      @ColorInt buttonBackgroundColor: Int,
      @ColorInt editorBackgroundColor: Int,
      additionalButtonConfig: ButtonConfig? = null,
  ) : this(
      isEnabled = isEnabled,
      buttonStyle =
          ButtonStyle(
              textColor = buttonTextColor,
              backgroundColor = buttonBackgroundColor,
              editorBackgroundColor = editorBackgroundColor,
              iconPrevious = android.R.drawable.ic_media_rew,
              iconNext = android.R.drawable.ic_media_ff,
              iconEdit = android.R.drawable.ic_menu_edit,
              iconInfo = android.R.drawable.ic_menu_info_details,
              iconClose = android.R.drawable.ic_menu_close_clear_cancel,
          ),
      additionalButtonConfig = additionalButtonConfig,
      customOptions = CustomOptions(emptyList()),
  )

  class ButtonConfig(
      val title: String,
      val iconResId: Int,
      val action: View.OnClickListener,
  ) {
    /** Secondary constructor for FBLite backward compatibility (uses system icon). */
    constructor(
        title: String,
        action: View.OnClickListener,
    ) : this(
        title = title,
        iconResId = android.R.drawable.ic_menu_add,
        action = action,
    )
  }

  class CustomOptions(val entries: List<Entry<out Any>>)

  // Button order: left nav, middle actions, right nav
  // Navigation buttons are on far left and far right edges
  private val buttons = listOfNotNull(
      ButtonConfig("Previous image", buttonStyle.iconPrevious) {
        imageSelector?.selectPrevious(it.context)
      },
      ButtonConfig("Edit image", buttonStyle.iconEdit) { showLiveEditor(it.context) },
      ButtonConfig("Image options", buttonStyle.iconCode) { showImageOptions(it.context) },
      ButtonConfig("Image info", buttonStyle.iconInfo) { showImageInfo(it.context) },
      additionalButtonConfig,
      ButtonConfig("Next image", buttonStyle.iconNext) {
        imageSelector?.selectNext(it.context)
      },
  )

  val imageTrackerListener: ImageTracker =
      object : ImageTracker() {
        private var isTracking = false

        override fun onImageBind(drawable: FrescoDrawableInterface) {
          ifEnabled {
            super.onImageBind(drawable)
            attachImageSelector(maybeGetContext(drawable))
          }
        }

        override fun onImageUnbind(drawable: FrescoDrawableInterface) {
          ifEnabled {
            super.onImageUnbind(drawable)
            if (drawableCount <= 0) {
              removeImageSelector()
            }
          }
        }

        private fun ifEnabled(body: () -> Unit) {
          if (isEnabled.get()) {
            body()
            isTracking = true
          } else if (isTracking) {
            removeImageSelector()
            super.reset()
            isTracking = false
          }
        }
      }

  var imageSelector: ImageSelector? = null

  private var isAttached: Boolean = false
  private var currentView: View? = null
  private var overlayPermissionStatus: Boolean? = null

  // Draggable overlay state
  private var currentLayoutParams: WindowManager.LayoutParams? = null
  private var lastButtonPositionX: Int? = null
  private var lastButtonPositionY: Int? = null

  // Foreground/background tracking
  private var isAppInForeground = true
  private var lifecycleCallbacksRegistered = false

  // Dialog panel state
  private var isDialogOpen = false
  private var activeDialogSourceIndex: Int? = null
  private var dialogWindowView: View? = null
  private var buttonBarView: ViewGroup? = null
  private var buttonViews: List<ImageButton> = emptyList()
  private var savedSourceIconResId: Int? = null

  private fun saveCurrentButtonPosition() {
    currentLayoutParams?.let { params ->
      lastButtonPositionX = params.x
      lastButtonPositionY = params.y
    }
  }

  private fun registerLifecycleCallbacks(context: Context) {
    if (lifecycleCallbacksRegistered) {
      return
    }
    val app = context.applicationContext as? Application ?: return
    // Start at 1: the current activity is already resumed when we register
    var resumedActivityCount = 1

    app.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
          override fun onActivityResumed(activity: Activity) {
            resumedActivityCount++
            if (!isAppInForeground) {
              isAppInForeground = true
              showOverlayIfNeeded(activity)
            }
          }

          override fun onActivityPaused(activity: Activity) {
            resumedActivityCount--
            if (resumedActivityCount <= 0) {
              resumedActivityCount = 0
              isAppInForeground = false
              hideOverlay()
            }
          }

          override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

          override fun onActivityStarted(activity: Activity) = Unit

          override fun onActivityStopped(activity: Activity) = Unit

          override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

          override fun onActivityDestroyed(activity: Activity) = Unit
        },
    )
    lifecycleCallbacksRegistered = true
  }

  private fun hideOverlay() {
    val view = currentView ?: return
    saveCurrentButtonPosition()
    val windowManager =
        try {
          view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (_: Exception) {
          null
        }
    removeDialogWindow(windowManager)
    try {
      windowManager?.removeView(view)
    } catch (_: IllegalArgumentException) {
      // View might not be attached to window manager
    }
    currentView = null
    currentLayoutParams = null
    isDialogOpen = false
    activeDialogSourceIndex = null
    dialogWindowView = null
    buttonBarView = null
    buttonViews = emptyList()
    savedSourceIconResId = null
  }

  private fun showOverlayIfNeeded(context: Context) {
    if (isAttached && currentView == null && isEnabled.get()) {
      showImageToggleButtons(context)
    }
  }

  fun attachImageSelector(context: Context?) {
    if (isAttached || context == null || !canShowOverlays(context)) {
      // already attached or no context available to get the WindowManager from
      return
    }
    isAttached = true
    registerLifecycleCallbacks(context)
    imageSelector =
        ImageSelector(
            imageTrackerListener,
            FrescoVitoProvider.getImagePipeline(),
            FrescoVitoProvider.getController(),
        )
    showImageToggleButtons(context)
  }

  fun removeImageSelector() {
    if (!isAttached) {
      return
    }
    val current = currentView ?: return

    if (!canShowOverlays(current.context)) {
      return
    }

    saveCurrentButtonPosition()
    val windowManager = current.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    removeDialogWindow(windowManager)
    windowManager.removeView(current)
    currentView = null
    currentLayoutParams = null
    isDialogOpen = false
    activeDialogSourceIndex = null
    dialogWindowView = null
    buttonBarView = null
    buttonViews = emptyList()
    savedSourceIconResId = null
    isAttached = false
  }

  private fun showImageToggleButtons(
      context: Context,
  ) {
    removeDialogWindow(getWindowManager(context))
    isDialogOpen = false
    activeDialogSourceIndex = null
    dialogWindowView = null
    savedSourceIconResId = null
    addWindow(context, createOnScreenButtons(context), isDraggable = true)
  }

  private fun showImageInfo(context: Context) {
    if (isDialogOpen) {
      closeDialogPanel(context)
    }
    val infoView =
        LiveEditorUiUtils(imageSelector?.currentEditor, debugDataProviders)
            .createImageInfoView(context, title = "Image info")

    // Info button is at index 3 in the buttons list
    showDialogPanel(context, infoView, sourceButtonIndex = 3)
  }

  private fun showImageOptions(context: Context) {
    if (isDialogOpen) {
      closeDialogPanel(context)
    }
    val optionsView =
        LiveEditorUiUtils(imageSelector?.currentEditor, debugDataProviders)
            .createImageOptionsView(context, title = "Image options")

    // Code button is at index 2 in the buttons list
    showDialogPanel(context, optionsView, sourceButtonIndex = 2)
  }

  private fun showLiveEditor(context: Context) {
    if (isDialogOpen) {
      closeDialogPanel(context)
    }
    val editorView =
        LiveEditorUiUtils(imageSelector?.currentEditor, debugDataProviders)
            .createView(context, customOptions.entries, title = "Edit image")

    // Edit button is at index 1 in the buttons list
    showDialogPanel(context, editorView, sourceButtonIndex = 1)
  }

  private fun showDialogPanel(
      context: Context,
      dialogView: View,
      sourceButtonIndex: Int,
  ) {
    val layoutParams = currentLayoutParams ?: return
    val view = currentView ?: return
    val windowManager = getWindowManager(context)
    val (screenWidth, screenHeight) = getScreenDimensions(windowManager)
    val buttonBar = buttonBarView ?: return

    // Get system bar insets from the Activity's decorView (reliable source — overlay
    // window metrics may report 0 insets since overlays can draw behind system bars)
    val (statusBarInset, navBarInset) = getSystemBarInsets(context)
    val renderableHeight = screenHeight - statusBarInset - navBarInset

    // Ensure button bar window is in absolute positioning mode.
    // On first show (no saved position), gravity is BOTTOM|END and layoutParams.y
    // is an offset from the bottom — unusable for dialog positioning. Convert now.
    // Use renderableHeight because TYPE_APPLICATION_OVERLAY windows are fit to the
    // renderable area (between status bar and nav bar) by the window manager.
    if (layoutParams.gravity != (Gravity.TOP or Gravity.LEFT)) {
      val padding = 16.dpToPx(context)
      layoutParams.gravity = Gravity.TOP or Gravity.LEFT
      layoutParams.x = screenWidth - view.width - padding
      layoutParams.y = renderableHeight - view.height - padding
      windowManager.updateViewLayout(view, layoutParams)
    }

    val preferredDialogHeight = renderableHeight / 2 - buttonBar.height
    val dialogWidth = screenWidth * 2 / 3

    // The window manager clamps overlay windows to the renderable area, so
    // layoutParams.y may not reflect the actual rendered position. Compute the
    // effective button position to ensure dialog placement matches reality.
    val effectiveButtonY = layoutParams.y.coerceAtMost(renderableHeight - buttonBar.height)
    val spaceAbove = effectiveButtonY
    val spaceBelow = renderableHeight - effectiveButtonY - buttonBar.height
    val dialogAbove = spaceAbove > spaceBelow

    // Position dialog window adjacent to the button bar — the button bar window
    // is never modified, so there is no blink or positional shift
    val dialogSpacing = 4.dpToPx(context)
    val availableSpace = if (dialogAbove) spaceAbove else spaceBelow
    val dialogHeight =
        preferredDialogHeight.coerceAtMost(availableSpace - dialogSpacing).coerceAtLeast(0)
    val dialogY =
        if (dialogAbove) {
          effectiveButtonY - dialogHeight - dialogSpacing
        } else {
          effectiveButtonY + buttonBar.height + dialogSpacing
        }

    @Suppress("DEPRECATION")
    val windowType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    val dialogParams =
        WindowManager.LayoutParams(
                dialogWidth,
                dialogHeight,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
              gravity = Gravity.TOP or Gravity.LEFT
              x = layoutParams.x
              y = dialogY
            }

    dialogView.layoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    val dialogCornerRadius = (36.dpToPx(context) / 2).toFloat()
    val wrapper =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          background =
              GradientDrawable().apply {
                setColor(dialogBackgroundColor(context))
                setCornerRadius(dialogCornerRadius)
              }
          clipToOutline = true
          addView(dialogView)
        }
    windowManager.addView(wrapper, dialogParams)
    dialogWindowView = wrapper

    // Toggle the source button to close icon
    val sourceButton = buttonViews.getOrNull(sourceButtonIndex) ?: return
    savedSourceIconResId = buttons.getOrNull(sourceButtonIndex)?.iconResId
    sourceButton.setImageResource(buttonStyle.iconClose)
    sourceButton.contentDescription = "Close"
    sourceButton.setOnClickListener { closeDialogPanel(context) }

    // Disable navigation buttons only — dialog buttons (Edit, Code, Info) stay enabled
    // so the user can switch between dialogs without closing first
    val dialogButtonIndices = setOf(1, 2, 3)
    buttonViews.forEachIndexed { index, btn ->
      if (index != sourceButtonIndex && index !in dialogButtonIndices) {
        btn.isEnabled = false
        btn.alpha = 0.4f
      }
    }

    isDialogOpen = true
    activeDialogSourceIndex = sourceButtonIndex
  }

  private fun closeDialogPanel(context: Context) {
    val windowManager = getWindowManager(context)
    val sourceIndex = activeDialogSourceIndex ?: return

    // Remove the dialog window — the button bar window is untouched
    removeDialogWindow(windowManager)

    // Restore the source button
    val sourceButton = buttonViews.getOrNull(sourceIndex)
    savedSourceIconResId?.let { sourceButton?.setImageResource(it) }
    sourceButton?.contentDescription = buttons.getOrNull(sourceIndex)?.title
    sourceButton?.setOnClickListener(buttons.getOrNull(sourceIndex)?.action)

    // Re-enable all buttons
    buttonViews.forEach { btn ->
      btn.isEnabled = true
      btn.alpha = 1.0f
    }

    isDialogOpen = false
    activeDialogSourceIndex = null
    dialogWindowView = null
    savedSourceIconResId = null
  }

  private fun removeDialogWindow(windowManager: WindowManager?) {
    val dialogView = dialogWindowView ?: return
    try {
      windowManager?.removeView(dialogView)
    } catch (_: IllegalArgumentException) {
      // View might not be attached to window manager
    }
    dialogWindowView = null
  }

  private fun getWindowManager(context: Context): WindowManager =
      context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

  @ColorInt
  private fun dialogBackgroundColor(context: Context): Int {
    val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
      DIALOG_BACKGROUND_DARK
    } else {
      DIALOG_BACKGROUND_LIGHT
    }
  }

  /** Returns the screen dimensions (width, height) using the modern API on Android R+. */
  private fun getScreenDimensions(windowManager: WindowManager): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = windowManager.currentWindowMetrics.bounds
      Pair(bounds.width(), bounds.height())
    } else {
      val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
      Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
  }

  /**
   * Returns system bar insets (top status bar height, bottom navigation bar height). Uses the
   * Activity's decorView insets rather than WindowManager metrics, because overlay windows
   * (TYPE_APPLICATION_OVERLAY) may report 0 insets since they can draw behind system bars.
   */
  @SuppressLint("DeprecatedMethod")
  @Suppress("DEPRECATION")
  private fun getSystemBarInsets(context: Context): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return Pair(0, 0)
    }
    val rootInsets = (context as? Activity)?.window?.decorView?.rootWindowInsets
    return if (rootInsets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val insets = rootInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
      Pair(insets.top, insets.bottom)
    } else if (rootInsets != null) {
      Pair(rootInsets.stableInsetTop, rootInsets.stableInsetBottom)
    } else {
      Pair(0, 0)
    }
  }

  private fun addWindow(
      context: Context,
      view: View,
      isDraggable: Boolean = false,
  ) {
    if (!isEnabled.get()) {
      return
    }
    val padding = 16.dpToPx(context)
    val windowManager = getWindowManager(context)
    currentView?.apply { windowManager.removeView(this) }

    // Check if we have a saved position to restore for draggable windows
    val savedX = lastButtonPositionX
    val savedY = lastButtonPositionY

    @Suppress("DEPRECATION")
    val windowType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          // TYPE_SYSTEM_ALERT is deprecated but required for API 21-25 (no alternative exists)
          WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    val layoutParams =
        WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
              if (isDraggable && savedX != null && savedY != null) {
                // Use absolute positioning to restore saved position
                gravity = Gravity.TOP or Gravity.LEFT
                x = savedX
                y = savedY
              } else {
                // Use gravity-based positioning for initial placement
                // This automatically adapts to any button layout size
                gravity = Gravity.BOTTOM or Gravity.END
                x = padding
                y = padding
              }
            }
    windowManager.addView(view, layoutParams)
    currentView = view

    if (isDraggable) {
      currentLayoutParams = layoutParams

      // Once the view is laid out, switch to absolute positioning for dragging
      // Only needed when we don't have a saved position
      if (savedX == null || savedY == null) {
        view.post {
          // After layout, convert gravity-based position to absolute position
          // This ensures dragging works correctly from the initial position
          val (screenWidth, screenHeight) = getScreenDimensions(windowManager)
          val (statusBarInset, navBarInset) = getSystemBarInsets(context)
          val renderableH = screenHeight - statusBarInset - navBarInset

          // Position within renderable area (TYPE_APPLICATION_OVERLAY windows are
          // fit between system bars by the window manager)
          layoutParams.gravity = Gravity.TOP or Gravity.LEFT
          layoutParams.x = screenWidth - view.width - padding
          layoutParams.y = renderableH - view.height - padding
          windowManager.updateViewLayout(view, layoutParams)
        }
      }
    }
  }

  /**
   * Custom ViewGroup class to wrap the live editor overlay buttons. Uses the "OverlayHandlerView"
   * naming pattern that Flipper's UI Debugger already recognizes and excludes from inspection,
   * preventing this overlay from blocking the main UI view hierarchy during debugging.
   */
  private open class OverlayHandlerView(context: Context) : LinearLayout(context)

  @SuppressLint("ClickableViewAccessibility")
  private fun createOnScreenButtons(context: Context): View {
    val windowManager = getWindowManager(context)
    val controller = this

    return object : OverlayHandlerView(context) {
      private var initialX = 0
      private var initialY = 0
      private var initialTouchX = 0f
      private var initialTouchY = 0f
      private var isDragging = false

      init {
        orientation = VERTICAL

        // Create the button bar as a horizontal child
        val buttonBar =
            LinearLayout(context).apply {
              orientation = HORIZONTAL
              gravity = Gravity.CENTER_VERTICAL
              background = null
              elevation = 8.dpToPxF(context)
            }

        val buttonWidth = 44.dpToPx(context)
        val buttonHeight = 36.dpToPx(context)
        val buttonSpacing = 2.dpToPx(context)
        val buttonPadding = 8.dpToPx(context)
        val edgeCornerRadius = (buttonHeight / 2).toFloat()
        val innerCornerRadius = 4.dpToPxF(context)

        val buttonCount = buttons.size
        val createdButtons = mutableListOf<ImageButton>()
        buttons.forEachIndexed { index, button ->
          val buttonLayoutParams = LayoutParams(buttonWidth, buttonHeight)

          val leftMargin = if (index == 0) 0 else buttonSpacing
          val rightMargin = if (index == buttonCount - 1) 0 else buttonSpacing
          buttonLayoutParams.setMargins(leftMargin, 0, rightMargin, 0)

          val isFirstButton = index == 0
          val isLastButton = index == buttonCount - 1

          val imageButton = createIconButton(
              context,
              buttonPadding,
              buttonLayoutParams,
              button.iconResId,
              button.title,
              button.action,
              isFirstButton = isFirstButton,
              isLastButton = isLastButton,
              edgeCornerRadius = edgeCornerRadius,
              innerCornerRadius = innerCornerRadius,
          )
          createdButtons.add(imageButton)
          buttonBar.addView(imageButton)
        }
        controller.buttonViews = createdButtons
        controller.buttonBarView = buttonBar

        addView(buttonBar)
      }

      override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (controller.isDialogOpen) {
          return false
        }
        val layoutParams = controller.currentLayoutParams ?: return false

        when (ev.action) {
          MotionEvent.ACTION_DOWN -> {
            initialX = layoutParams.x
            initialY = layoutParams.y
            initialTouchX = ev.rawX
            initialTouchY = ev.rawY
            isDragging = false
            return false // Don't intercept yet, let children handle it
          }
          MotionEvent.ACTION_MOVE -> {
            val deltaX = ev.rawX - initialTouchX
            val deltaY = ev.rawY - initialTouchY

            // Check if we've moved enough to consider this a drag
            if (!isDragging && (abs(deltaX) > DRAG_TOUCH_SLOP || abs(deltaY) > DRAG_TOUCH_SLOP)) {
              isDragging = true
              // Cancel touch on children
              val cancelEvent = MotionEvent.obtain(ev)
              cancelEvent.action = MotionEvent.ACTION_CANCEL
              super.dispatchTouchEvent(cancelEvent)
              cancelEvent.recycle()
              return true // Start intercepting
            }
            return isDragging
          }
          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL -> {
            isDragging = false
            return false
          }
        }
        return false
      }

      override fun onTouchEvent(event: MotionEvent): Boolean {
        if (controller.isDialogOpen) {
          return false
        }
        val layoutParams = controller.currentLayoutParams ?: return false
        val (screenWidth, screenHeight) = controller.getScreenDimensions(windowManager)

        when (event.action) {
          MotionEvent.ACTION_DOWN -> {
            initialX = layoutParams.x
            initialY = layoutParams.y
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            isDragging = false
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val deltaX = event.rawX - initialTouchX
            val deltaY = event.rawY - initialTouchY

            // Check if we've moved enough to consider this a drag
            if (!isDragging && (abs(deltaX) > DRAG_TOUCH_SLOP || abs(deltaY) > DRAG_TOUCH_SLOP)) {
              isDragging = true
            }

            if (isDragging) {
              // Clamp to renderable area (TYPE_APPLICATION_OVERLAY windows are fit
              // between system bars by the window manager)
              val (statusBarInset, navBarInset) = controller.getSystemBarInsets(context)
              val renderableH = screenHeight - statusBarInset - navBarInset
              val newX = (initialX + deltaX.toInt()).coerceIn(0, screenWidth - width)
              val newY = (initialY + deltaY.toInt()).coerceIn(0, renderableH - height)

              layoutParams.x = newX
              layoutParams.y = newY
              windowManager.updateViewLayout(this, layoutParams)
            }
            return true
          }
          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL -> {
            isDragging = false
            return true
          }
        }
        return super.onTouchEvent(event)
      }
    }
  }

  private fun createIconButton(
      context: Context,
      paddingPx: Int,
      layoutParams: ViewGroup.LayoutParams,
      iconResId: Int,
      contentDescription: String,
      action: View.OnClickListener,
      isFirstButton: Boolean = false,
      isLastButton: Boolean = false,
      edgeCornerRadius: Float = 0f,
      innerCornerRadius: Float = 0f,
  ): ImageButton =
      ImageButton(context).apply {
        setImageResource(iconResId)
        setColorFilter(buttonStyle.textColor)
        this.contentDescription = contentDescription
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
        setLayoutParams(layoutParams)
        setOnClickListener(action)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER

        // Create background with appropriate corner radii
        // Corner order: top-left, top-right, bottom-right, bottom-left (each needs x,y radius)
        background =
            GradientDrawable().apply {
              setColor(buttonStyle.backgroundColor)
              shape = GradientDrawable.RECTANGLE

              val topLeftRadius = if (isFirstButton) edgeCornerRadius else innerCornerRadius
              val bottomLeftRadius = if (isFirstButton) edgeCornerRadius else innerCornerRadius
              val topRightRadius = if (isLastButton) edgeCornerRadius else innerCornerRadius
              val bottomRightRadius = if (isLastButton) edgeCornerRadius else innerCornerRadius

              cornerRadii =
                  floatArrayOf(
                      topLeftRadius,
                      topLeftRadius, // top-left
                      topRightRadius,
                      topRightRadius, // top-right
                      bottomRightRadius,
                      bottomRightRadius, // bottom-right
                      bottomLeftRadius,
                      bottomLeftRadius, // bottom-left
                  )
            }
      }

  fun maybeGetContext(drawable: FrescoDrawableInterface): Context? =
      when (drawable) {
        is Drawable -> {
          when (val callback = drawable.callback) {
            is View -> callback.context
            else -> null
          }
        }
        else -> null
      }

  private fun canShowOverlays(context: Context): Boolean {
    overlayPermissionStatus?.let {
      return it
    }
    // Settings.canDrawOverlays requires API 23; on API 21-22, overlay permission is granted at
    // install time
    val canDraw =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          Settings.canDrawOverlays(context)
        } else {
          true
        }
    if (!canDraw) {
      showOverlayPermissionMessage(context)
      overlayPermissionStatus = false
    } else {
      overlayPermissionStatus = true
    }
    return overlayPermissionStatus == true
  }
}
