package com.example.wifiscreenremote

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var addressView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val captureRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "کنترل صفحه گوشی با Wi‑Fi"
            textSize = 25f
            setTextColor(0xFF102A43.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(title, marginParams(bottom = 12))

        val description = TextView(this).apply {
            text = "تصویر گوشی با WebRTC به Chrome فرستاده می‌شود. برای کنترل، روی تصویر در مرورگر لمس یا درگ کنید. گوشی و کامپیوتر باید به یک شبکه Wi‑Fi وصل باشند."
            textSize = 16f
            setTextColor(0xFF334E68.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(description, marginParams(bottom = 20))

        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF1769AA.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(statusView, marginParams(bottom = 14))

        addressView = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFF102A43.toInt())
            gravity = Gravity.CENTER
            text = "آدرس مرورگر پس از شروع نمایش داده می‌شود."
        }
        content.addView(addressView, marginParams(bottom = 14))

        startButton = makeButton("شروع اشتراک‌گذاری") {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
        }
        content.addView(startButton, marginParams(bottom = 10))

        stopButton = makeButton("توقف اشتراک‌گذاری") {
            stopService(Intent(this, StreamService::class.java))
            window.decorView.postDelayed({ refreshStatus() }, 300)
        }
        content.addView(stopButton, marginParams(bottom = 10))

        val accessibilityButton = makeButton("فعال‌سازی کنترل لمس") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        content.addView(accessibilityButton, marginParams(bottom = 10))

        val copyButton = makeButton("کپی آدرس") {
            val link = currentLink()
            if (link != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Screen Remote URL", link))
                statusView.text = "آدرس کپی شد. آن را در Chrome باز کنید."
            } else {
                statusView.text = "ابتدا به Wi‑Fi وصل شوید و اشتراک‌گذاری را شروع کنید."
            }
        }
        content.addView(copyButton, marginParams(bottom = 22))

        val warning = TextView(this).apply {
            text = "هشدار امنیتی: دسترسی بدون رمز است. هر دستگاهی در همان شبکه محلی که آدرس را بداند می‌تواند تصویر را ببیند و در صورت فعال بودن Accessibility لمس ارسال کند. فقط روی Wi‑Fi مطمئن استفاده کنید."
            textSize = 14f
            setTextColor(0xFF9B2C2C.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(warning)

        setContentView(scroll)
        refreshStatus()
    }


    @Deprecated("Uses the platform MediaProjection permission activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return
        if (resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(StreamService.EXTRA_PERMISSION_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            statusView.text = "در حال راه‌اندازی سرور محلی…"
            window.decorView.postDelayed({ refreshStatus() }, 900)
        } else {
            statusView.text = "اجازه ضبط صفحه داده نشد."
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val running = StreamService.isRunning
        statusView.text = if (running) "وضعیت: اشتراک‌گذاری فعال است" else "وضعیت: متوقف"
        startButton.isEnabled = !running
        stopButton.isEnabled = running
        val link = currentLink()
        addressView.text = when {
            !running -> "برای نمایش آدرس، اشتراک‌گذاری را شروع کنید."
            link == null -> "IP شبکه پیدا نشد؛ اتصال Wi‑Fi را بررسی کنید."
            else -> "در Chrome باز کنید:\n$link"
        }
    }

    private fun currentLink(): String? {
        val ip = NetworkUtils.localIpv4Address() ?: return null
        return "http://$ip:${LocalSignalingServer.PORT}/"
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottom) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
