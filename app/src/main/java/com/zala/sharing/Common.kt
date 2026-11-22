package com.zala.sharing

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

const val PORT = 8765
const val DISC_PORT = 48555
const val BUF = 1024 * 1024

interface Listener {
    fun onProgress(dir: String, name: String, done: Long, total: Long, bps: Double)
    fun onDone(dir: String, name: String, ok: Boolean, msg: String, uri: Uri?)
}

data class SendItem(val uri: Uri, val name: String, val size: Long)
data class Item(val name: String, val size: Long, val uri: Uri, val mime: String)
data class NetInfo(val ip: String, val bcast: String?)

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

fun isVideo(name: String) = mimeOf(name).startsWith("video/")

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format("%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format("%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun fmtSpeed(bps: Double): String = String.format("%.1f MB/s", bps / 1048576.0)

/** Finds this device's Wi-Fi / hotspot IPv4 address (ignores mobile data). */
fun netInfo(): NetInfo? {
    try {
        val good = Regex("wlan|^ap|swlan|wifi|eth")
        val bad = Regex("rmnet|ccmni|ppp|dummy|tun|lo")
        var fallback: NetInfo? = null
        for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!ni.isUp || ni.isLoopback) continue
            for (ia in ni.interfaceAddresses) {
                val a = ia.address
                if (a is Inet4Address && a.isSiteLocalAddress) {
                    val info = NetInfo(a.hostAddress ?: continue, ia.broadcast?.hostAddress)
                    if (good.containsMatchIn(ni.name)) return info
                    if (!bad.containsMatchIn(ni.name) && fallback == null) fallback = info
                }
            }
        }
        return fallback
    } catch (e: Exception) {
        return null
    }
}

fun gatewayIp(ctx: Context): String? {
    return try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val g = wm.dhcpInfo.gateway
        if (g == 0) null else "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
    } catch (e: Exception) {
        null
    }
}

fun nameSize(ctx: Context, u: Uri): Pair<String, Long> {
    var n = "file"
    var s = -1L
    try {
        ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) n = c.getString(ni) ?: n
                if (si >= 0 && !c.isNull(si)) s = c.getLong(si)
            }
        }
    } catch (e: Exception) {
    }
    if (s < 0) {
        s = try {
            ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }
    return n to s
}

class Saved(val out: OutputStream, val uri: Uri, val finish: () -> Unit, val abort: () -> Unit)

/** Opens a destination for an incoming file. Videos -> Movies/ZalaSharing, others -> Download/ZalaSharing. */
fun openSave(ctx: Context, rawName: String): Saved {
    val name = rawName.replace('/', '_').replace('\\', '_')
    val mime = mimeOf(name)
    val video = mime.startsWith("video/")
    if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/ZalaSharing" else "Download/ZalaSharing")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = ctx.contentResolver.insert(coll, cv) ?: throw java.io.IOException("Cannot create file")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open file")
        return Saved(os, uri, {
            os.close()
            val u = ContentValues()
            u.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, u, null, null)
        }, {
            try { os.close() } catch (e: Exception) { }
            ctx.contentResolver.delete(uri, null, null)
        })
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS),
            "ZalaSharing"
        )
        dir.mkdirs()
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        val os = FileOutputStream(f)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)
        return Saved(os, uri, {
            os.close()
            MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null)
        }, {
            try { os.close() } catch (e: Exception) { }
            f.delete()
        })
    }
}

fun listReceived(ctx: Context): List<Item> {
    val res = ArrayList<Item>()
    if (Build.VERSION.SDK_INT >= 29) {
        val coll = MediaStore.Files.getContentUri("external")
        val rp = MediaStore.MediaColumns.RELATIVE_PATH
        ctx.contentResolver.query(
            coll,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE),
            "$rp LIKE ? OR $rp LIKE ?",
            arrayOf("Movies/ZalaSharing%", "Download/ZalaSharing%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                res.add(Item(c.getString(1) ?: "file", c.getLong(2), ContentUris.withAppendedId(coll, c.getLong(0)), c.getString(3) ?: ""))
            }
        }
    } else {
        for (d in listOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS)) {
            val dir = File(Environment.getExternalStoragePublicDirectory(d), "ZalaSharing")
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
                res.add(Item(f.name, f.length(), FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f), mimeOf(f.name)))
            }
        }
    }
    return res
}
