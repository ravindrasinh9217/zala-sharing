package com.zala.sharing

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class ReceiveActivity : BaseActivity() {
    private lateinit var server: ZalaServer
    private lateinit var pinTv: TextView
    private lateinit var ipTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var openBtn: Button
    private lateinit var autoBtn: Button
    private var lastName = ""
    private var lastUri: Uri? = null
    private var autoPlay = false
    private val ui = Handler(Looper.getMainLooper())

    private val listener = object : Listener {
        override fun onProgress(dir: String, name: String, done: Long, total: Long, bps: Double) {
            runOnUiThread {
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                statusTv.text = "RECEIVING  $name\n$pct%   |   ${fmtSize(done)} / ${fmtSize(total)}   |   ${fmtSpeed(bps)}"
            }
        }

        override fun onDone(dir: String, name: String, ok: Boolean, msg: String, uri: Uri?) {
            runOnUiThread {
                if (ok) {
                    bar.progress = 1000
                    statusTv.text = "Received: $name   (100%)"
                    lastName = name
                    lastUri = uri
                    openBtn.text = (if (isVideo(name)) "Play: " else "Open: ") + name
                    openBtn.visibility = View.VISIBLE
                    openBtn.requestFocus()
                    if (autoPlay && uri != null && isVideo(name)) play(uri)
                } else {
                    statusTv.text = "$name: $msg"
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            pinTv.text = spaced(server.pin)
            val ip = netInfo()?.ip
            ipTv.text = if (ip != null) "Device: ${deviceName()}   |   IP: $ip" else "Not connected to Wi-Fi/Hotspot yet"
            ui.postDelayed(this, 1000)
        }
    }

    private fun spaced(p: String) = p.substring(0, 3) + " " + p.substring(3)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keepAwake()
        autoPlay = isTv
        val r = newRoot()
        r.addView(label("Receive", 30f, ORANGE, true))
        r.addView(label("On the other device, tap SEND, choose this device and type this code:", 16f, GRAY))
        pinTv = label("", 56f, android.graphics.Color.WHITE, true).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(16), 0, dp(8))
        }
        r.addView(pinTv)
        ipTv = label("", 15f, GRAY)
        r.addView(ipTv)
        r.addView(label("\nKeep this screen open while receiving.", 15f, GRAY))
        statusTv = label("Waiting for files...", 18f, android.graphics.Color.WHITE).apply { setPadding(0, dp(16), 0, 0) }
        r.addView(statusTv)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(8) }
        }
        r.addView(bar)
        openBtn = btn("") {
            val u = lastUri
            if (u != null) openItem(Item(lastName, 0, u, ""))
        }.apply { visibility = View.GONE }
        r.addView(openBtn)
        autoBtn = btn("") { autoPlay = !autoPlay; updateAuto() }
        updateAuto()
        r.addView(autoBtn)
        r.addView(btn("Received files") { showReceived() })
        show(r)

        server = ZalaServer(applicationContext, deviceName(), listener)
        server.start()
        ui.post(tick)
    }

    private fun updateAuto() {
        autoBtn.text = "Play videos automatically: " + if (autoPlay) "ON" else "OFF"
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        server.stop()
        super.onDestroy()
    }
}
