package com.example.wifiscreenremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import kotlin.math.roundToInt

class RemoteControlAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    fun dispatchRemoteGesture(command: JSONObject) {
        mainHandler.post {
            try {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)

                val x1 = (command.optDouble("x1", 0.5) * metrics.widthPixels)
                    .coerceIn(0.0, metrics.widthPixels.toDouble()).roundToInt().toFloat()
                val y1 = (command.optDouble("y1", 0.5) * metrics.heightPixels)
                    .coerceIn(0.0, metrics.heightPixels.toDouble()).roundToInt().toFloat()
                val x2 = (command.optDouble("x2", command.optDouble("x1", 0.5)) * metrics.widthPixels)
                    .coerceIn(0.0, metrics.widthPixels.toDouble()).roundToInt().toFloat()
                val y2 = (command.optDouble("y2", command.optDouble("y1", 0.5)) * metrics.heightPixels)
                    .coerceIn(0.0, metrics.heightPixels.toDouble()).roundToInt().toFloat()
                val duration = command.optLong("duration", 90L).coerceIn(40L, 1200L)

                val path = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x2, y2)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                    .build()
                dispatchGesture(gesture, null, mainHandler)
            } catch (_: Exception) {
                // The screen may rotate or the OEM accessibility implementation may reject a gesture.
            }
        }
    }

    fun performRemoteKey(key: String) {
        mainHandler.post {
            when (key.lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        }
    }

    companion object {
        @Volatile
        var current: RemoteControlAccessibilityService? = null
            private set
    }
}
