package dev.timekeeper.app.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.timekeeper.app.data.Appearance
import dev.timekeeper.app.data.TimekeeperStore
import dev.timekeeper.core.Rule

/** Eagerly built, opaque curtain: no icons, font discovery, or countdown work on the launch path. */
class BlockOverlay(
    service: AccessibilityService,
    private val store: TimekeeperStore,
    goHome: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val resources = service.resources
    private var showing = false
    val isShowing get() = showing
    private val title = TextView(service).apply {
        textSize = 22f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
    }
    private val close = TextView(service).apply {
        text = "Close"
        textSize = 17f
        gravity = Gravity.CENTER
        minimumHeight = (52 * density).toInt()
        isClickable = true
        isFocusable = true
        setOnClickListener { goHome() }
    }
    private val curtain = FrameLayout(service).apply {
        isClickable = true
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(close, LinearLayout.LayoutParams(-1, -2).apply { topMargin = padding })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    private val parameters = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.FILL
        title = "Timekeeper app limit"
        setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        setFitInsetsIgnoringVisibility(true)
    }

    fun show(rule: Rule): Boolean {
        val dark = when (store.current.preferences.appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        curtain.setBackgroundColor(if (dark) Color.BLACK else Color.rgb(244, 244, 244))
        title.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        title.text = "${rule.label} paused"
        close.setTextColor(if (dark) Color.rgb(112, 168, 255) else Color.rgb(3, 96, 233))
        if (!showing) {
            try { manager.addView(curtain, parameters); showing = true }
            catch (_: RuntimeException) { return false }
        }
        return true
    }

    fun hide() {
        if (!showing) return
        try { manager.removeViewImmediate(curtain) } catch (_: RuntimeException) { }
        showing = false
    }
}
