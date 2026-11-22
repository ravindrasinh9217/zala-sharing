package com.zala.sharing

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class SendActivity : BaseActivity() {
    private val found = ConcurrentHashMap<String, Pair<String, Long>>()
    private var target: String? = null
    private var targetName = ""
    private var pin = ""
    @Volatile private var sending = false
    @Volatile private var scanning = false
    @Volatile private var queueInfo = ""
    private var lastSig = ""
    private var ticks = 0
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var infoTv: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var connTv: TextView
    private lateinit var actionsBox: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var bar: ProgressBar

    private val listener = object : Listener {
        override fun onProgress(dir: String, name: String, done: Long, total: Long, bps: Double) {
            runOnUiThread {
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                statusTv.text = "SENDING  $name$queueInfo\n$pct%   |   ${fmtSize(done)} / ${fmtSize(total)}   |   ${fmtSpeed(bps)}"
            }
        }

        override fun onDone(dir: String, name: String, ok: Boolean, msg: String, uri: Uri?) {
            runOnUiThread {
                if (ok) {
                    bar.progress = 1000
                    statusTv.text = "Sent: $name   (100%)"
                } else {
                    statusTv.text = "Failed: $name\n$msg"
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (ticks % 4 == 0) scanNow()
            ticks++
            refreshList()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keepAwake()
        askPerms()
        val r = newRoot()
        r.addView(label("Send", 30f, ORANGE, true))
        infoTv = label("", 15f, GRAY)
        r.addView(infoTv)
        r.addView(label("\nChoose the device to send to", 18f, android.graphics.Color.WHITE, true))
        r.addView(label("(the other device must have RECEIVE open)", 14f, GRAY))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(listBox)
        r.addView(btn("Device not shown? Enter its IP") { askIp() })

        connTv = label("", 18f, GREEN, true).apply { setPadding(0, dp(20), 0, 0) }
        r.addView(connTv)
        actionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        actionsBox.addView(btn("Choose videos") { pickVideos() })
        actionsBox.addView(btn("Choose any files (photos, documents...)") { pickFiles() })
        actionsBox.addView(btn("Share the Zala Sharing app (APK)") { sendApk() })
        r.addView(actionsBox)

        statusTv = label("", 17f, android.graphics.Color.WHITE).apply { setPadding(0, dp(16), 0, 0) }
        r.addView(statusTv)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(8) }
        }
        r.addView(bar)
        show(r)
        ui.post(tick)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun scanNow() {
        if (scanning || sending) return
        scanning = true
        thread {
            try {
                val res = scanLan()
                val now = System.currentTimeMillis()
                for (p in res) found[p.first] = p.second to now
            } finally {
                scanning = false
            }
        }
    }

    private fun refreshList() {
        val now = System.currentTimeMillis()
        found.entries.removeIf { now - it.value.second > 16000 }
        val me = netInfo()?.ip
        infoTv.text = if (me != null) "This device: ${deviceName()}   |   IP: $me" else "Not connected to Wi-Fi/Hotspot yet. Turn on the Hotspot and connect both devices."
        val list = found.entries.sortedBy { it.value.first }
        val sig = list.joinToString(",") { it.key + it.value.first }
        if (sig == lastSig) return
        lastSig = sig
        listBox.removeAllViews()
        if (list.isEmpty()) {
            listBox.addView(label("Searching...", 16f, GRAY))
        } else {
            for (e in list) {
                val ip = e.key
                val n = e.value.first
                listBox.addView(btn("$n   ($ip)", 20f) { askPin(ip, n) })
            }
        }
    }

    private fun askPin(ip: String, name: String) {
        val et = EditText(this).apply {
            hint = "6-digit code"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 24f
        }
        AlertDialog.Builder(this)
            .setTitle("Type the code shown on $name")
            .setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val p = et.text.toString().trim()
                thread {
                    val err = checkPin(ip, p)
                    runOnUiThread {
                        if (err == null) {
                            target = ip
                            targetName = name
                            pin = p
                            connTv.text = "Connected to $name"
                            actionsBox.visibility = View.VISIBLE
                            actionsBox.getChildAt(0).requestFocus()
                        } else {
                            toast(err)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askIp() {
        val et = EditText(this).apply {
            hint = "e.g. 192.168.43.1"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("IP shown on the Receive screen")
            .setView(et)
            .setPositiveButton("Next") { _, _ ->
                val ip = et.text.toString().trim()
                thread {
                    val n = probe(ip)
                    runOnUiThread {
                        if (n != null) askPin(ip, n) else toast("Cannot reach $ip. Is RECEIVE open on that device?")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickVideos() {
        if (!hasReadPerm()) {
            askPerms()
            toast("Please allow the permission, then tap again")
            return
        }
        val items = ArrayList<SendItem>()
        val coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            coll,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext() && items.size < 500) {
                val id = c.getLong(0)
                items.add(SendItem(ContentUris.withAppendedId(coll, id), c.getString(1) ?: "video_$id.mp4", c.getLong(2)))
            }
        }
        if (items.isEmpty()) {
            toast("No videos found on this device")
            return
        }
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle("Select videos to send")
            .setMultiChoiceItems(items.map { "${it.name}  (${fmtSize(it.size)})" }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("Send") { _, _ -> sendAll(items.filterIndexed { i, _ -> checked[i] }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 10)
        } catch (e: ActivityNotFoundException) {
            toast("File picker is not available on this device. Use 'Choose videos'.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 10 && res == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            sendAll(uris.map { u ->
                val (n, sz) = nameSize(this, u)
                SendItem(u, n, sz)
            })
        }
    }

    private fun sendApk() {
        val f = File(applicationInfo.sourceDir)
        sendAll(listOf(SendItem(Uri.fromFile(f), "ZalaSharing.apk", f.length())))
    }

    private fun sendAll(items: List<SendItem>) {
        val ip = target
        if (ip == null) {
            toast("Choose a device first")
            return
        }
        if (items.isEmpty()) return
        if (sending) {
            toast("A transfer is already running")
            return
        }
        sending = true
        val code = pin
        thread {
            try {
                items.forEachIndexed { i, item ->
                    queueInfo = if (items.size > 1) "   (${i + 1}/${items.size})" else ""
                    sendFile(applicationContext, ip, code, item, listener)
                }
            } finally {
                sending = false
            }
        }
    }
}
