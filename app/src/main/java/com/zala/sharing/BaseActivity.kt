package com.zala.sharing

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

val ORANGE = 0xFFFF6D00.toInt()
val GRAY = 0xFF9AA3B2.toInt()
val GREEN = 0xFF7CFC9A.toInt()

open class BaseActivity : Activity() {
    private var wifiLock: WifiManager.WifiLock? = null

    val isTv: Boolean by lazy {
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun deviceName(): String = (if (isTv) "TV " else "Phone ") + Build.MODEL
    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun bg(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    fun btn(t: String, size: Float = 18f, a: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = size
        setTextColor(Color.WHITE)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), bg(ORANGE))
        sl.addState(intArrayOf(android.R.attr.state_pressed), bg(ORANGE))
        sl.addState(intArrayOf(), bg(0xFF2A2F3A.toInt()))
        background = sl
        isFocusable = true
        setOnClickListener { a() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun newRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
    }

    fun show(root: LinearLayout) {
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zala").also { it.acquire() }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try { wifiLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    fun hasReadPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    fun askPerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.READ_MEDIA_VIDEO)
        else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    fun play(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
    }

    fun openItem(item: Item) {
        try {
            when {
                isVideo(item.name) -> play(item.uri)
                item.name.endsWith(".apk", true) -> startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(item.uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                else -> startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(item.uri, item.mime.ifEmpty { "*/*" })
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        } catch (e: Exception) {
            toast("Cannot open: ${e.message}")
        }
    }

    fun showReceived() {
        val list = listReceived(this)
        if (list.isEmpty()) {
            toast("Nothing received yet")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Received files")
            .setItems(list.map { "${it.name}  (${fmtSize(it.size)})" }.toTypedArray()) { _, i -> openItem(list[i]) }
            .setNegativeButton("Close", null)
            .show()
    }
}
