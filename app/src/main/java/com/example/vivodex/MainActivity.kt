package com.example.vivodex

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Choreographer
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.util.Locale

class MainActivity : AppCompatActivity(), TrackpadView.TrackpadListener {

    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: SharedPreferences

    // Display Page Views
    private lateinit var statusView: TextView
    private lateinit var selectedDisplayView: TextView
    private lateinit var displayDetailsView: TextView
    private lateinit var displayIcon: ImageView
    private lateinit var accessibilityStatusView: TextView
    private lateinit var accessibilityIcon: ImageView
    private lateinit var openAccessibilityButton: MaterialButton
    private lateinit var favoriteAppsView: LinearLayout
    private lateinit var removeFavoriteButton: MaterialButton
    private lateinit var launchAppButton: MaterialButton
    private lateinit var launchOnPhoneButton: MaterialButton
    private lateinit var openKeyboardButton: MaterialButton
    private lateinit var displayPage: View
    private lateinit var settingsPage: View
    private lateinit var awakeStatusView: TextView
    private lateinit var awakeModeGroup: RadioGroup
    private lateinit var awakeGlassIndicator: View
    private lateinit var autoDisableSwitch: MaterialSwitch

    // Touchpad Page Views
    private lateinit var touchpadPage: View
    private lateinit var trackpadView: TrackpadView
    private lateinit var trackpadStatusView: TextView
    private lateinit var speedButton: MaterialButton
    private lateinit var toggleCursorButton: MaterialButton
    private lateinit var blackoutButton: MaterialButton
    private lateinit var moreControlsButton: MaterialButton
    private lateinit var leftClickButton: MaterialButton
    private lateinit var zoomControls: View
    private lateinit var zoomSlider: Slider

    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var navGlassIndicator: View

    // State
    private var selectedDisplayId: Int? = null
    private var selectedApp: ResolveInfo? = null
    private val favoritePackages = linkedSetOf<String>()
    private var cursorX = 0f
    private var cursorY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var pendingCursorDisplayId: Int? = null
    private var cursorUpdateScheduled = false
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f
    private val scrollHandler = Handler(Looper.getMainLooper())
    private var scrollScheduled = false

    private val cursorFrameCallback = Choreographer.FrameCallback {
        cursorUpdateScheduled = false
        pendingCursorDisplayId?.let { displayId ->
            RemoteGestureService.instance?.moveCursor(displayId, cursorX, cursorY)
        }
    }

    private val flushScroll = Runnable {
        scrollScheduled = false
        val deltaX = pendingScrollX
        val deltaY = pendingScrollY
        pendingScrollX = 0f
        pendingScrollY = 0f
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return@Runnable
        RemoteGestureService.instance?.scroll(displayId, cursorX, cursorY, deltaX, deltaY)
    }

    private val speedPresets = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
    private var currentSpeedIndex = 1 // Default 1.5x

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshExternalDisplay()
        override fun onDisplayChanged(displayId: Int) = refreshExternalDisplay()
        override fun onDisplayRemoved(displayId: Int) = refreshExternalDisplay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("vivodex_prefs", MODE_PRIVATE)
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        initViews()
        setupListeners()
        loadSettings()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        renderFavorites()
        showPage(showTouchpad = false)
    }

    override fun onStart() {
        super.onStart()
        RemoteGestureService.instance?.cancelAutoDisable()
        displayManager.registerDisplayListener(displayListener, null)
        refreshExternalDisplay()
        updateAccessibilityStatus()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        Choreographer.getInstance().removeFrameCallback(cursorFrameCallback)
        cursorUpdateScheduled = false
        pendingCursorDisplayId = null
        scrollHandler.removeCallbacks(flushScroll)
        scrollScheduled = false
        pendingScrollX = 0f
        pendingScrollY = 0f
        if (prefs.getBoolean("auto_disable_accessibility", true)) {
            RemoteGestureService.instance?.scheduleAutoDisable()
        }
        super.onStop()
    }

    private fun initViews() {
        // Display Page
        statusView = findViewById(R.id.display_status)
        selectedDisplayView = findViewById(R.id.selected_display)
        displayDetailsView = findViewById(R.id.display_details)
        displayIcon = findViewById(R.id.display_icon)
        accessibilityStatusView = findViewById(R.id.accessibility_status)
        accessibilityIcon = findViewById(R.id.accessibility_icon)
        openAccessibilityButton = findViewById(R.id.open_accessibility_settings)
        favoriteAppsView = findViewById(R.id.favorite_apps)
        removeFavoriteButton = findViewById(R.id.remove_favorite)
        launchAppButton = findViewById(R.id.launch_app)
        launchOnPhoneButton = findViewById(R.id.launch_on_phone)
        openKeyboardButton = findViewById(R.id.open_keyboard)
        displayPage = findViewById(R.id.display_page)
        settingsPage = findViewById(R.id.settings_page)
        awakeStatusView = findViewById(R.id.awake_status)
        awakeModeGroup = findViewById(R.id.awake_mode_group)
        awakeGlassIndicator = findViewById(R.id.awake_glass_indicator)
        autoDisableSwitch = findViewById(R.id.auto_disable_accessibility)

        // Touchpad Page
        touchpadPage = findViewById(R.id.touchpad_page)
        trackpadView = findViewById(R.id.trackpad)
        trackpadStatusView = findViewById(R.id.trackpad_status)
        speedButton = findViewById(R.id.btn_speed)
        toggleCursorButton = findViewById(R.id.toggle_cursor)
        blackoutButton = findViewById(R.id.blackout_phone_screen)
        moreControlsButton = findViewById(R.id.more_controls)
        leftClickButton = findViewById(R.id.btn_mouse_left)
        zoomControls = findViewById(R.id.zoom_controls)
        zoomSlider = findViewById(R.id.zoom_slider)

        bottomNavigation = findViewById(R.id.bottom_navigation)
        navGlassIndicator = findViewById(R.id.nav_glass_indicator)
        trackpadView.listener = this
    }

    private fun setupListeners() {
        bottomNavigation.setOnItemSelectedListener { item ->
            moveNavGlassIndicator(item.itemId)
            when (item.itemId) {
                R.id.nav_display -> {
                    showPage(showTouchpad = false)
                    true
                }
                R.id.nav_touchpad -> {
                    showPage(showTouchpad = true)
                    ensureCursorPosition()
                    true
                }
                R.id.nav_settings -> {
                    showSettings()
                    true
                }
                else -> false
            }
        }
        bottomNavigation.post { moveNavGlassIndicator(bottomNavigation.selectedItemId, animate = false) }

        findViewById<View>(R.id.select_display).setOnClickListener { selectDisplay() }
        findViewById<View>(R.id.select_app).setOnClickListener { selectApp() }
        removeFavoriteButton.setOnClickListener { removeSelectedFavorite() }
        launchAppButton.setOnClickListener { launchSelectedApp() }
        launchOnPhoneButton.setOnClickListener { launchSelectedAppOnPhone() }
        openKeyboardButton.setOnClickListener { openKeyboard() }

        openAccessibilityButton.setOnClickListener { confirmAccessibilitySettings() }

        awakeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            moveAwakeGlassIndicator(checkedId)
            val mode = when (checkedId) {
                R.id.awake_on -> "on"
                R.id.awake_off -> "off"
                else -> "auto"
            }
            prefs.edit().putString("keep_awake_mode", mode).apply()
            updateKeepScreenAwake()
        }
        autoDisableSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("auto_disable_accessibility", enabled).apply()
        }
        findViewById<MaterialButton>(R.id.disable_accessibility_now).setOnClickListener {
            RemoteGestureService.instance?.disableNow()
            showAlert("Accessibility will be turned off")
        }

        // Speed picker
        speedButton.setOnClickListener { cyclePointerSpeed() }

        // Cursor toggle
        toggleCursorButton.setOnClickListener {
            val visible = RemoteGestureService.instance?.toggleCursor() ?: true
            HapticHelper.click(this)
            trackpadStatusView.text = if (visible) "Cursor Visible" else "Cursor Hidden"
        }

        // Blackout screen
        blackoutButton.setOnClickListener {
            val shown = RemoteGestureService.instance?.blackoutPhoneScreen() ?: false
            if (!shown) {
                showAlert("Enable Accessibility Service first")
            }
        }

        moreControlsButton.setOnClickListener {
            val showing = zoomControls.visibility == View.VISIBLE
            zoomControls.visibility = if (showing) View.GONE else View.VISIBLE
            moreControlsButton.text = if (showing) "More" else "Hide"
        }
        zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val scale = slider.value
                slider.value = 1f
                if (kotlin.math.abs(scale - 1f) >= 0.05f) pinchAtCursor(scale)
            }
        })


        // Scroll Gestures Buttons
        findViewById<View>(R.id.scroll_up).setOnClickListener {
            HapticHelper.tick(this)
            scrollAtCursor(deltaY = 300f)
        }
        findViewById<View>(R.id.scroll_down).setOnClickListener {
            HapticHelper.tick(this)
            scrollAtCursor(deltaY = -300f)
        }
        findViewById<View>(R.id.scroll_left).setOnClickListener {
            HapticHelper.tick(this)
            scrollAtCursor(deltaX = -300f)
        }
        findViewById<View>(R.id.scroll_right).setOnClickListener {
            HapticHelper.tick(this)
            scrollAtCursor(deltaX = 300f)
        }

        // Physical Mouse Buttons
        leftClickButton.setOnClickListener {
            HapticHelper.heavyClick(this)
            clickAtCursor()
        }

    }

    private fun moveNavGlassIndicator(itemId: Int, animate: Boolean = true) {
        val item = bottomNavigation.findViewById<View>(itemId) ?: return
        val horizontalInset = resources.getDimensionPixelSize(R.dimen.nav_indicator_horizontal_inset)
        val verticalInset = resources.getDimensionPixelSize(R.dimen.nav_indicator_vertical_inset)
        val params = navGlassIndicator.layoutParams as FrameLayout.LayoutParams
        params.width = item.width - horizontalInset * 2
        params.height = item.height - verticalInset * 2
        params.topMargin = verticalInset
        navGlassIndicator.layoutParams = params

        val targetX = (item.left + horizontalInset).toFloat()
        navGlassIndicator.animate().cancel()
        if (animate) {
            navGlassIndicator.animate()
                .translationX(targetX)
                .setDuration(320)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                .start()
        } else {
            navGlassIndicator.translationX = targetX
        }
    }

    private fun moveAwakeGlassIndicator(itemId: Int, animate: Boolean = true) {
        val item = awakeModeGroup.findViewById<View>(itemId) ?: return
        if (item.width == 0) return
        val params = awakeGlassIndicator.layoutParams as FrameLayout.LayoutParams
        params.width = item.width
        params.height = item.height
        params.topMargin = item.top
        awakeGlassIndicator.layoutParams = params

        awakeGlassIndicator.animate().cancel()
        if (animate) {
            awakeGlassIndicator.animate()
                .translationX(item.left.toFloat())
                .setDuration(320)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f))
                .start()
        } else {
            awakeGlassIndicator.translationX = item.left.toFloat()
        }
    }

    private fun loadSettings() {
        favoritePackages += prefs.getStringSet("packages", emptySet()).orEmpty()
        val savedSpeed = prefs.getFloat("sensitivity", 1.5f)
        currentSpeedIndex = speedPresets.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.1f }.coerceAtLeast(0)
        applyPointerSpeed(speedPresets[currentSpeedIndex])
        awakeModeGroup.check(
            when (prefs.getString("keep_awake_mode", "auto")) {
                "on" -> R.id.awake_on
                "off" -> R.id.awake_off
                else -> R.id.awake_auto
            }
        )
        autoDisableSwitch.isChecked = prefs.getBoolean("auto_disable_accessibility", true)
    }

    private fun cyclePointerSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedPresets.size
        val newSpeed = speedPresets[currentSpeedIndex]
        applyPointerSpeed(newSpeed)
        prefs.edit().putFloat("sensitivity", newSpeed).apply()
        HapticHelper.click(this)
        showAlert("Pointer Speed: ${newSpeed}x")
    }

    private fun applyPointerSpeed(speed: Float) {
        trackpadView.sensitivity = speed
        speedButton.text = "${speed}x"
    }

    private fun showPage(showTouchpad: Boolean) {
        displayPage.visibility = if (showTouchpad) View.GONE else View.VISIBLE
        touchpadPage.visibility = if (showTouchpad) View.VISIBLE else View.GONE
        settingsPage.visibility = View.GONE
    }

    private fun showSettings() {
        displayPage.visibility = View.GONE
        touchpadPage.visibility = View.GONE
        settingsPage.visibility = View.VISIBLE
        awakeModeGroup.doOnPreDraw {
            moveAwakeGlassIndicator(awakeModeGroup.checkedRadioButtonId, animate = false)
        }
        updateKeepScreenAwake()
    }

    private fun refreshExternalDisplay() {
        val display = externalDisplay()
        Log.i("VivoDex", "Displays: ${displayManager.displays.joinToString { "${it.displayId}:${it.name}:${it.state}" }}")

        if (display == null) {
            statusView.text = "No external display connected\nConnect USB-C to Monitor or TV"
            displayIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#71717A"))
            trackpadView.isEnabled = false
            trackpadStatusView.text = "Display Disconnected"
        } else {
            statusView.text = "Connected: ${display.name}\n${display.mode.physicalWidth} x ${display.mode.physicalHeight} @ ${formatRefreshRate(display.refreshRate)} Hz"
            displayIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            trackpadView.isEnabled = true
            trackpadStatusView.text = "Connected: ${display.name}"
        }

        if (availableDisplays().none { it.displayId == selectedDisplayId }) {
            selectedDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        }
        updateSelectedDisplay()
        updateKeepScreenAwake()
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = RemoteGestureService.instance != null
        if (isEnabled) {
            accessibilityStatusView.text = "Accessibility Active (Gestures & Cursor Ready)"
            accessibilityIcon.setImageResource(R.drawable.ic_check_circle)
            accessibilityIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            openAccessibilityButton.visibility = View.GONE
        } else {
            accessibilityStatusView.text = "Accessibility Disabled (Required for Virtual Mouse)"
            accessibilityIcon.setImageResource(R.drawable.ic_warning)
            accessibilityIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#71717A"))
            openAccessibilityButton.visibility = View.VISIBLE
        }
    }

    private fun updateKeepScreenAwake() {
        val mode = prefs.getString("keep_awake_mode", "auto") ?: "auto"
        val active = when (mode) {
            "on" -> true
            "off" -> false
            else -> externalDisplay() != null
        }
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        RemoteGestureService.instance?.setKeepScreenAwake(active)
        awakeStatusView.text = when (mode) {
            "on" -> "Always on until you switch it off"
            "off" -> "Disabled"
            else -> if (active) "Active while an external display is connected" else "Turns on automatically when HDMI connects"
        }
    }

    private fun updateSelectedDisplay() {
        val display = availableDisplays().firstOrNull { it.displayId == selectedDisplayId }
        selectedDisplayView.text = if (display == null) {
            "Target: No target display selected"
        } else {
            "Target: ${displayLabel(display)}"
        }

        displayDetailsView.text = display?.let {
            val current = "${it.mode.physicalWidth} x ${it.mode.physicalHeight} @ ${formatRefreshRate(it.refreshRate)} Hz"
            "Display ID: ${it.displayId} • State: ${if (it.state == Display.STATE_ON) "Active" else "Idle"}\nResolution: $current"
        } ?: ""
    }

    private fun ensureCursorPosition() {
        val display = displayManager.getDisplay(selectedDisplayId ?: externalDisplay()?.displayId ?: return) ?: return
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = display.mode.physicalWidth / 2f
            cursorY = display.mode.physicalHeight / 2f
        }
        scheduleCursorMove(display.displayId)
    }

    // ==================== TRACKPAD CALLBACKS ====================

    override fun onPointerMove(dx: Float, dy: Float) {
        val display = displayManager.getDisplay(selectedDisplayId ?: externalDisplay()?.displayId ?: return) ?: return
        cursorX = (cursorX + dx).coerceIn(0f, display.mode.physicalWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, display.mode.physicalHeight.toFloat())

        scheduleCursorMove(display.displayId)
        trackpadStatusView.text = "Pointer: ${cursorX.toInt()}, ${cursorY.toInt()}"
    }

    override fun onSingleTap() {
        clickAtCursor()
    }

    override fun onDoubleTap() {
        clickAtCursor()
    }

    override fun onTwoFingerTap() {
        longPressAtCursor()
    }

    override fun onScroll(deltaX: Float, deltaY: Float) {
        pendingScrollX += deltaX
        pendingScrollY += deltaY
        if (!scrollScheduled) {
            scrollScheduled = true
            scrollHandler.postDelayed(flushScroll, 50L)
        }
    }

    override fun onDragStart() {
        dragStartX = cursorX
        dragStartY = cursorY
        trackpadStatusView.text = "Dragging..."
    }

    override fun onDragMove(dx: Float, dy: Float) {
        onPointerMove(dx, dy)
    }

    override fun onDragEnd() {
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        RemoteGestureService.instance?.drag(displayId, dragStartX, dragStartY, cursorX, cursorY)
        trackpadStatusView.text = "Pointer: ${cursorX.toInt()}, ${cursorY.toInt()}"
    }

    private fun clickAtCursor() {
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        val sent = RemoteGestureService.instance?.tap(displayId, cursorX, cursorY) ?: false
        if (!sent) showAccessibilityRequiredToast()
    }

    private fun longPressAtCursor() {
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        val sent = RemoteGestureService.instance?.longPress(displayId, cursorX, cursorY) ?: false
        if (!sent) showAccessibilityRequiredToast()
    }

    private fun scrollAtCursor(deltaX: Float = 0f, deltaY: Float = 0f) {
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        val sent = RemoteGestureService.instance?.scroll(displayId, cursorX, cursorY, deltaX, deltaY) ?: false
        if (!sent) showAccessibilityRequiredToast()
    }

    private fun pinchAtCursor(scale: Float) {
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        val sent = RemoteGestureService.instance?.pinch(displayId, cursorX, cursorY, scale) ?: false
        if (sent) {
            trackpadStatusView.text = "Zoom ${String.format(Locale.US, "%.1fx", scale)}"
        } else {
            showAccessibilityRequiredToast()
        }
    }

    private fun showAccessibilityRequiredToast() {
        showAlert("Please enable Accessibility Service for Trackpad control")
    }

    private fun showAlert(message: String, long: Boolean = false) {
        val alert = Snackbar.make(
            findViewById(R.id.main),
            message,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
        ).setAnchorView(bottomNavigation)
            .setTextColor(ContextCompat.getColor(this, R.color.text_glass_primary))
        ViewCompat.setBackgroundTintList(alert.view, null)
        alert.view.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_alert)
        alert.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 3
            setTextColor(Color.WHITE)
        }
        alert.show()
    }

    private fun confirmAccessibilitySettings() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Enable Accessibility Service?")
            .setView(layoutInflater.inflate(R.layout.dialog_accessibility, null))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                openAccessibilitySettings()
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        val serviceKey = ComponentName(this, RemoteGestureService::class.java).flattenToString()
        val fragmentArgs = Bundle().apply {
            putString(SETTINGS_FRAGMENT_ARG_KEY, serviceKey)
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(SETTINGS_FRAGMENT_ARG_KEY, serviceKey)
            putExtra(SETTINGS_FRAGMENT_ARGS, fragmentArgs)
        })
    }

    private fun scheduleCursorMove(displayId: Int) {
        pendingCursorDisplayId = displayId
        if (!cursorUpdateScheduled) {
            cursorUpdateScheduled = true
            Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
        }
    }

    private fun openKeyboard() {
        val service = RemoteGestureService.instance
        if (service == null) {
            showAccessibilityRequiredToast()
            return
        }
        if (!service.hasEditableTarget()) {
            showAlert("Tap a text field on the external app first", long = true)
            return
        }

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = "Type for the external app"
            setHintTextColor(Color.parseColor("#71717A"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_input)
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
        }
        container.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Keyboard: ${service.editableTargetPackage()}")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                if (!service.setEditableText(input.text)) {
                    showAlert("${service.editableTargetPackage()} rejected text input")
                }
            }
            .create()
        val showKeyboardRunnable = Runnable { showKeyboard(input) }
        dialog.setOnShowListener { input.postDelayed(showKeyboardRunnable, 250L) }
        dialog.setOnDismissListener { input.removeCallbacks(showKeyboardRunnable) }
        dialog.show()
    }

    private fun showKeyboard(input: View) {
        input.requestFocus()
        input.post {
            WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
        }
    }

    // ==================== APP LAUNCHER & FAVORITES ====================

    private fun selectDisplay() {
        val displays = availableDisplays()
        val picker = layoutInflater.inflate(R.layout.dialog_display_picker, null)
        val options = picker.findViewById<RadioGroup>(R.id.display_options)
        val displayByOption = mutableMapOf<Int, Display>()

        displays.forEach { display ->
            val option = layoutInflater.inflate(R.layout.item_dialog_display, options, false) as android.widget.RadioButton
            option.id = View.generateViewId()
            option.text = displayLabel(display)
            displayByOption[option.id] = display
            options.addView(option)
            if (display.displayId == selectedDisplayId) options.check(option.id)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Switch Target Display")
            .setView(picker)
            .setNegativeButton("Cancel", null)
            .create()
        options.setOnCheckedChangeListener { _, checkedId ->
            displayByOption[checkedId]?.let { display ->
                selectedDisplayId = display.displayId
                updateSelectedDisplay()
                ensureCursorPosition()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun selectApp() {
        val apps = launchableApps()
        if (apps.isEmpty()) {
            showAlert("No launchable apps found")
            return
        }

        val picker = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val search = picker.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.app_search)
        val list = picker.findViewById<ListView>(R.id.app_list)
        val empty = picker.findViewById<TextView>(R.id.app_list_empty)
        val adapter = object : ArrayAdapter<ResolveInfo>(this, R.layout.item_dialog_app, apps.toMutableList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_dialog_app, parent, false)
                val app = getItem(position)!!
                val iconView = view.findViewById<ImageView>(R.id.app_icon)
                val nameView = view.findViewById<TextView>(R.id.app_name)
                val packageView = view.findViewById<TextView>(R.id.app_package)

                nameView.text = app.loadLabel(packageManager)
                packageView.text = app.activityInfo.packageName
                iconView.setImageDrawable(app.loadIcon(packageManager))
                return view
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add Favorite App")
            .setView(picker)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position) ?: return@setOnItemClickListener
            favoritePackages += app.activityInfo.packageName
            saveFavorites()
            renderFavorites()
            selectFavorite(app)
            dialog.dismiss()
        }
        search.doAfterTextChanged { text ->
            val query = text.toString().trim().lowercase(Locale.getDefault())
            val matches = if (query.isEmpty()) {
                apps
            } else {
                apps.filter { app ->
                    app.loadLabel(packageManager).toString().lowercase(Locale.getDefault()).contains(query) ||
                        app.activityInfo.packageName.lowercase(Locale.getDefault()).contains(query)
                }
            }
            adapter.clear()
            adapter.addAll(matches)
            adapter.notifyDataSetChanged()
        }
        dialog.show()
    }

    private fun renderFavorites() {
        val favorites = launchableApps().filter { it.activityInfo.packageName in favoritePackages }
        favoriteAppsView.removeAllViews()

        val density = resources.displayMetrics.density

        if (favorites.isEmpty()) {
            val emptyNotice = TextView(this).apply {
                text = "No favorite apps added yet. Tap 'Add App' below to select shortcuts."
                textSize = 13f
                setTextColor(Color.parseColor("#888888"))
                setPadding((8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt())
            }
            favoriteAppsView.addView(emptyNotice)
            removeFavoriteButton.visibility = View.GONE
            launchAppButton.isEnabled = false
            launchOnPhoneButton.isEnabled = false
            launchAppButton.text = "Launch App"
            return
        }

        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        val primaryColor = typedValue.data

        theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, typedValue, true)
        val surfaceVariantColor = typedValue.data

        favorites.forEach { app ->
            val isSelected = (selectedApp?.activityInfo?.packageName == app.activityInfo.packageName)

            val appContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    (74 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (10 * density).toInt() }
            }

            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams((60 * density).toInt(), (60 * density).toInt())
                radius = 18 * density
                cardElevation = if (isSelected) (4 * density) else 0f
                strokeWidth = if (isSelected) (2f * density).toInt() else (1 * density).toInt()
                strokeColor = if (isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#26FFFFFF")
                setCardBackgroundColor(if (isSelected) Color.parseColor("#28FFFFFF") else Color.parseColor("#12FFFFFF"))
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    val wasSelected = (selectedApp?.activityInfo?.packageName == app.activityInfo.packageName)
                    selectFavorite(app)
                    HapticHelper.click(this@MainActivity)
                    if (wasSelected) {
                        launchSelectedApp()
                    }
                }
            }

            val iconView = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    (46 * density).toInt(),
                    (46 * density).toInt(),
                    android.view.Gravity.CENTER,
                )
                setImageDrawable(app.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }

            card.addView(iconView)

            val appLabel = TextView(this).apply {
                text = app.loadLabel(packageManager)
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                if (isSelected) {
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                } else {
                    setTextColor(Color.parseColor("#A1A1AA"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (6 * density).toInt() }
            }

            appContainer.addView(card)
            appContainer.addView(appLabel)
            favoriteAppsView.addView(appContainer)
        }

        if (selectedApp?.activityInfo?.packageName !in favoritePackages) selectedApp = null
        if (selectedApp == null) favorites.firstOrNull()?.let(::selectFavorite)
        removeFavoriteButton.visibility = if (selectedApp == null) View.GONE else View.VISIBLE
    }

    private fun selectFavorite(app: ResolveInfo) {
        val prevSelected = selectedApp
        selectedApp = app
        launchAppButton.isEnabled = true
        launchOnPhoneButton.isEnabled = true
        launchAppButton.text = "Launch ${app.loadLabel(packageManager)} on Display"
        removeFavoriteButton.visibility = View.VISIBLE
        if (prevSelected?.activityInfo?.packageName != app.activityInfo.packageName) {
            renderFavorites()
        }
    }

    private fun removeSelectedFavorite() {
        val app = selectedApp ?: return
        favoritePackages -= app.activityInfo.packageName
        saveFavorites()
        selectedApp = null
        launchAppButton.isEnabled = false
        launchOnPhoneButton.isEnabled = false
        launchAppButton.text = "Launch App"
        renderFavorites()
    }

    private fun saveFavorites() {
        prefs.edit().putStringSet("packages", favoritePackages).apply()
    }

    private fun launchableApps(): List<ResolveInfo> = packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

    private fun launchSelectedApp() {
        val app = selectedApp ?: return
        val displayId = selectedDisplayId ?: run {
            showAlert("No target display selected")
            return
        }
        launchApp(app, displayId)
    }

    private fun launchSelectedAppOnPhone() {
        selectedApp?.let { launchApp(it, Display.DEFAULT_DISPLAY) }
    }

    private fun launchApp(app: ResolveInfo, displayId: Int) {
        val appName = app.loadLabel(packageManager)

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.activityInfo.packageName, app.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(displayId)
        }

        try {
            startActivity(intent, options.toBundle())
            showAlert("Launching $appName on ${displayLabel(displayManager.getDisplay(displayId) ?: return)}")
        } catch (_: ActivityNotFoundException) {
            showAlert("The selected app is no longer available", long = true)
        } catch (_: SecurityException) {
            showAlert("This app cannot launch on the selected display", long = true)
        }
    }

    private fun availableDisplays(): List<Display> = displayManager.displays.filter { it.state != Display.STATE_OFF }

    private fun displayLabel(display: Display): String = if (display.displayId == Display.DEFAULT_DISPLAY) {
        "Phone Display (${display.mode.physicalWidth} x ${display.mode.physicalHeight})"
    } else {
        "${display.name} (${display.mode.physicalWidth} x ${display.mode.physicalHeight})"
    }

    private fun formatRefreshRate(rate: Float): String = String.format(Locale.US, "%.1f", rate)

    private fun externalDisplay(): Display? = displayManager.displays.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF
    }

    companion object {
        private const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val SETTINGS_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}
