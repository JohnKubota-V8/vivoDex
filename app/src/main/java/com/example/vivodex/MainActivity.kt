package com.example.vivodex

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private lateinit var displayPage: View

    // Touchpad Page Views
    private lateinit var touchpadPage: View
    private lateinit var trackpadView: TrackpadView
    private lateinit var trackpadStatusView: TextView
    private lateinit var speedButton: MaterialButton
    private lateinit var toggleCursorButton: MaterialButton
    private lateinit var blackoutButton: MaterialButton
    private lateinit var leftClickButton: MaterialButton
    private lateinit var rightClickButton: MaterialButton

    private lateinit var bottomNavigation: BottomNavigationView

    // State
    private var selectedDisplayId: Int? = null
    private var selectedApp: ResolveInfo? = null
    private val favoritePackages = linkedSetOf<String>()
    private var cursorX = 0f
    private var cursorY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f

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
        displayManager.registerDisplayListener(displayListener, null)
        refreshExternalDisplay()
        updateAccessibilityStatus()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
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
        displayPage = findViewById(R.id.display_page)

        // Touchpad Page
        touchpadPage = findViewById(R.id.touchpad_page)
        trackpadView = findViewById(R.id.trackpad)
        trackpadStatusView = findViewById(R.id.trackpad_status)
        speedButton = findViewById(R.id.btn_speed)
        toggleCursorButton = findViewById(R.id.toggle_cursor)
        blackoutButton = findViewById(R.id.blackout_phone_screen)
        leftClickButton = findViewById(R.id.btn_mouse_left)
        rightClickButton = findViewById(R.id.btn_mouse_right)

        bottomNavigation = findViewById(R.id.bottom_navigation)
        trackpadView.listener = this
    }

    private fun setupListeners() {
        bottomNavigation.setOnItemSelectedListener { item ->
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
                else -> false
            }
        }

        findViewById<View>(R.id.select_display).setOnClickListener { selectDisplay() }
        findViewById<View>(R.id.select_app).setOnClickListener { selectApp() }
        removeFavoriteButton.setOnClickListener { removeSelectedFavorite() }
        launchAppButton.setOnClickListener { launchSelectedApp() }

        openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            }
        }


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

        rightClickButton.setOnClickListener {
            HapticHelper.heavyClick(this)
            longPressAtCursor()
        }
    }

    private fun loadSettings() {
        favoritePackages += prefs.getStringSet("packages", emptySet()).orEmpty()
        val savedSpeed = prefs.getFloat("sensitivity", 1.5f)
        currentSpeedIndex = speedPresets.indexOfFirst { kotlin.math.abs(it - savedSpeed) < 0.1f }.coerceAtLeast(0)
        applyPointerSpeed(speedPresets[currentSpeedIndex])
    }

    private fun cyclePointerSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedPresets.size
        val newSpeed = speedPresets[currentSpeedIndex]
        applyPointerSpeed(newSpeed)
        prefs.edit().putFloat("sensitivity", newSpeed).apply()
        HapticHelper.click(this)
        Toast.makeText(this, "Pointer Speed: ${newSpeed}x", Toast.LENGTH_SHORT).show()
    }

    private fun applyPointerSpeed(speed: Float) {
        trackpadView.sensitivity = speed
        speedButton.text = "${speed}x"
    }

    private fun showPage(showTouchpad: Boolean) {
        displayPage.visibility = if (showTouchpad) View.GONE else View.VISIBLE
        touchpadPage.visibility = if (showTouchpad) View.VISIBLE else View.GONE
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
        RemoteGestureService.instance?.moveCursor(display.displayId, cursorX, cursorY)
    }

    // ==================== TRACKPAD CALLBACKS ====================

    override fun onPointerMove(dx: Float, dy: Float) {
        val display = displayManager.getDisplay(selectedDisplayId ?: externalDisplay()?.displayId ?: return) ?: return
        cursorX = (cursorX + dx).coerceIn(0f, display.mode.physicalWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, display.mode.physicalHeight.toFloat())

        RemoteGestureService.instance?.moveCursor(display.displayId, cursorX, cursorY)
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
        val displayId = selectedDisplayId ?: externalDisplay()?.displayId ?: return
        RemoteGestureService.instance?.scroll(displayId, cursorX, cursorY, deltaX, deltaY)
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

    private fun showAccessibilityRequiredToast() {
        Toast.makeText(this, "Please enable Accessibility Service for Trackpad control", Toast.LENGTH_SHORT).show()
    }

    // ==================== APP LAUNCHER & FAVORITES ====================

    private fun selectDisplay() {
        val displays = availableDisplays()
        val labels = displays.map(::displayLabel).toTypedArray()
        val checkedItem = displays.indexOfFirst { it.displayId == selectedDisplayId }

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Target Display")
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                selectedDisplayId = displays[which].displayId
                updateSelectedDisplay()
                ensureCursorPosition()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun selectApp() {
        val apps = launchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = object : ArrayAdapter<ResolveInfo>(
            this,
            android.R.layout.activity_list_item,
            android.R.id.text1,
            apps,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val app = getItem(position)!!
                view.findViewById<TextView>(android.R.id.text1).text = app.loadLabel(packageManager)
                view.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.loadIcon(packageManager))
                return view
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Favorite App")
            .setAdapter(adapter) { _, which ->
                val app = apps[which]
                favoritePackages += app.activityInfo.packageName
                saveFavorites()
                renderFavorites()
                selectFavorite(app)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
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
        val displayId = selectedDisplayId ?: return
        val appName = app.loadLabel(packageManager)

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.activityInfo.packageName, app.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val options = ActivityOptions.makeBasic().apply {
            setLaunchDisplayId(displayId)
        }

        try {
            startActivity(intent, options.toBundle())
            Toast.makeText(this, "Launching $appName on Display #$displayId", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "The selected app is no longer available", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "This app cannot launch on the selected display", Toast.LENGTH_LONG).show()
        }
    }

    private fun availableDisplays(): List<Display> = displayManager.displays.toList()

    private fun displayLabel(display: Display): String = if (display.displayId == Display.DEFAULT_DISPLAY) {
        "Phone Display (${display.mode.physicalWidth} x ${display.mode.physicalHeight})"
    } else {
        "${display.name} (${display.mode.physicalWidth} x ${display.mode.physicalHeight})"
    }

    private fun formatRefreshRate(rate: Float): String = String.format(Locale.US, "%.1f", rate)

    private fun externalDisplay(): Display? = displayManager.displays.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF
    }
}
