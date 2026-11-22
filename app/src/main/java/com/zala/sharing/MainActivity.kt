package com.zala.sharing

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var server: ZalaServer
    private lateinit var disc: Discovery
    private val peers = ConcurrentHashMap<String, Pair<String, Long>>()
    private var selected: String? = null
    private var selectedName = ""
    @Volatile private var sending = false
    @Volatile private var queueInfo = ""
    private var autoPlay = false
    private var isTv = false
    private var lastSig = ""
    private var wifiLock: WifiManager.WifiLock? = null
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var infoTv: TextView
    private lateinit var targetTv: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var statusTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var autoBtn: Button

    private val listener = object : Listener {
        override fun onProgress(dir: String, name: String, done: Long, total: Long, bps: Double) {
            runOnUiThread {
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                bar.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                val label = if (dir == "out") "SENDING" else "RECEIVING"
                val q = if (dir == "out") queueInfo else ""
                statusTv.text = "$label  $name$q\n$pct%   |   ${fmtSize(done)} / ${fmtSize(total)}   |   ${fmtSpeed(bps)}"
            }
        }

        override fun onDone(dir: String, name: String, ok: Boolean, msg: String, uri: Uri?) {
            runOnUiThread {
                if (ok) {
                    bar.progress = 1000
                    statusTv.text = (if (dir == "out") "Sent: " else "Received: ") + name + "   (100%)"
                    if (dir == "in" && autoPlay && uri != null && isVideo(name)) play(uri)
                } else {
                    statusTv.text = "Failed: $name\n$msg"
                }
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isTv = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        autoPlay = isTv
        askPerms()
        buildUi()

        val name = (if (isTv) "TV " else "Phone ") + Build.MODEL
        server = ZalaServer(applicationContext, name, listener)
        server.start()
        disc = Discovery(applicationContext, name) { ip, n -> peers[ip] = n to System.currentTimeMillis() }
        disc.start()

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zala").also { it.acquire() }
        } catch (e: Exception) {
        }
        ui.post(tickRun)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tickRun)
        server.stop()
        disc.stop()
        try { wifiLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    private val tickRun = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun bg(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun btn(t: String, a: () -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), bg(0xFFFF6D00.toInt()))
        sl.addState(intArrayOf(android.R.attr.state_pressed), bg(0xFFFF6D00.toInt()))
        sl.addState(intArrayOf(), bg(0xFF2A2F3A.toInt()))
        background = sl
        isFocusable = true
        setOnClickListener { a() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        root.addView(label("Zala Sharing", 30f, 0xFFFF6D00.toInt(), true))
        infoTv = label("", 15f, 0xFF9AA3B2.toInt())
        root.addView(infoTv)
        root.addView(label("Connect both devices to the same Hotspot / Wi-Fi. Internet is not needed.", 15f, 0xFF9AA3B2.toInt()))

        root.addView(label("\nNearby devices", 18f, Color.WHITE, true))
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(peersBox)
        targetTv = label("", 16f, 0xFF7CFC9A.toInt())
        root.addView(targetTv)

        root.addView(label("\nSend", 18f, Color.WHITE, true))
        root.addView(btn("Send videos from this device") { pickVideos() })
        root.addView(btn("Send any files (photos, documents, APK...)") { pickFiles() })
        root.addView(btn("Share the Zala Sharing app (APK)") { sendApk() })

        root.addView(label("\nReceive", 18f, Color.WHITE, true))
        root.addView(btn("Received files  (play / install)") { showReceived() })
        autoBtn = btn("") { autoPlay = !autoPlay; updateAutoBtn() }
        updateAutoBtn()
        root.addView(autoBtn)
        root.addView(btn("Enter other device IP manually") { askIp() })

        root.addView(label("\nTransfer", 18f, Color.WHITE, true))
        statusTv = label("Idle", 17f, Color.WHITE)
        root.addView(statusTv)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(8) }
        }
        root.addView(bar)

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
    }

    private fun updateAutoBtn() {
        autoBtn.text = "Auto-play received videos: " + if (autoPlay) "ON" else "OFF"
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { now - it.value.second > 9000 }
        val list = peers.entries.sortedBy { it.value.first }
        if (selected == null && list.size == 1) {
            selected = list[0].key
            selectedName = list[0].value.first
        }
        val me = netInfo()?.ip
        infoTv.text = if (me != null) "This device IP: $me   (port $PORT)" else "Not on Wi-Fi/Hotspot yet"
        targetTv.text = if (selected != null) "Sending to: $selectedName ($selected)" else "No device selected"

        val sig = list.joinToString(",") { it.key + it.value.first } + "|" + selected
        if (sig == lastSig) return
        lastSig = sig
        peersBox.removeAllViews()
        if (list.isEmpty()) {
            peersBox.addView(label("Searching...", 16f, 0xFF9AA3B2.toInt()))
        } else {
            for (e in list) {
                val ip = e.key
                val n = e.value.first
                peersBox.addView(btn((if (ip == selected) "[x] " else "[  ] ") + n + "   ($ip)") {
                    selected = ip
                    selectedName = n
                    lastSig = ""
                    refresh()
                })
            }
        }
    }

    private fun hasReadPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPerms() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.READ_MEDIA_VIDEO)
        else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
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
            toast("File picker is not available on this device. Use 'Send videos'.")
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
        val ip = selected
        if (ip == null) {
            toast("Select a device first (or enter its IP)")
            return
        }
        if (items.isEmpty()) return
        if (sending) {
            toast("A transfer is already running")
            return
        }
        sending = true
        thread {
            items.forEachIndexed { i, item ->
                queueInfo = if (items.size > 1) "   (${i + 1}/${items.size})" else ""
                sendFile(applicationContext, ip, item, listener)
            }
            sending = false
        }
    }

    private fun showReceived() {
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

    private fun openItem(item: Item) {
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

    private fun play(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
    }

    private fun askIp() {
        val et = EditText(this).apply {
            hint = "e.g. 192.168.43.1"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("Other device IP")
            .setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                if (ip.isNotEmpty()) {
                    disc.extraIps.add(ip)
                    thread {
                        val n = ping(ip)
                        runOnUiThread {
                            if (n != null) {
                                peers[ip] = n to System.currentTimeMillis()
                                selected = ip
                                selectedName = n
                                lastSig = ""
                                refresh()
                            } else toast("Could not reach $ip. Is Zala Sharing open on that device?")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
