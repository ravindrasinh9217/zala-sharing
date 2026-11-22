package com.zala.sharing

import android.content.Context
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/** Tiny HTTP server: /ping and /upload. Streams the body straight to storage with a 1 MB buffer. */
class ZalaServer(private val ctx: Context, private val deviceName: String, private val l: Listener) {
    @Volatile private var running = false
    private var ss: ServerSocket? = null

    fun start() {
        running = true
        thread(isDaemon = true) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.receiveBufferSize = 4 * 1024 * 1024
                s.bind(InetSocketAddress(PORT))
                ss = s
                while (running) {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                }
            } catch (e: Exception) {
                if (running) l.onDone("in", "server", false, "Server error: ${e.message}", null)
            }
        }
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (e: Exception) { }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun respond(o: OutputStream, code: Int, body: String) {
        val b = body.toByteArray()
        o.write("HTTP/1.1 $code X\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(b)
        o.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val inp = BufferedInputStream(sock.getInputStream(), 65536)
            val out = sock.getOutputStream()
            val req = readLine(inp) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(inp) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val parts = req.split(" ")
            val method = parts[0]
            val path = if (parts.size > 1) parts[1] else "/"
            when {
                path.startsWith("/ping") -> respond(out, 200, "ZALA|$deviceName")
                method == "POST" && path.startsWith("/upload") -> receive(inp, out, headers)
                else -> respond(out, 404, "no")
            }
        } catch (e: Exception) {
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    private fun receive(inp: InputStream, out: OutputStream, headers: Map<String, String>) {
        val name = URLDecoder.decode(headers["x-name"] ?: "file", "UTF-8")
        val total = headers["content-length"]?.toLongOrNull() ?: -1L
        val saved = try {
            openSave(ctx, name)
        } catch (e: Exception) {
            l.onDone("in", name, false, e.message ?: "Cannot save", null)
            respond(out, 500, "save failed")
            return
        }
        val buf = ByteArray(BUF)
        var done = 0L
        var lastDone = 0L
        var lastT = System.nanoTime()
        try {
            while (total < 0 || done < total) {
                val want = if (total < 0) buf.size else minOf(buf.size.toLong(), total - done).toInt()
                val n = inp.read(buf, 0, want)
                if (n < 0) break
                saved.out.write(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastT > 200_000_000L) {
                    val bps = (done - lastDone) / ((now - lastT) / 1e9)
                    lastT = now
                    lastDone = done
                    l.onProgress("in", name, done, total, bps)
                }
            }
            if (total >= 0 && done < total) throw IOException("Connection lost")
            saved.finish()
            respond(out, 200, "OK")
            l.onProgress("in", name, done, total, 0.0)
            l.onDone("in", name, true, "", saved.uri)
        } catch (e: Exception) {
            saved.abort()
            l.onDone("in", name, false, e.message ?: "error", null)
        }
    }
}

fun ping(ip: String): String? {
    return try {
        val c = URL("http://$ip:$PORT/ping").openConnection() as HttpURLConnection
        c.connectTimeout = 700
        c.readTimeout = 700
        val t = c.inputStream.bufferedReader().readText()
        c.disconnect()
        if (t.startsWith("ZALA|")) t.substring(5) else null
    } catch (e: Exception) {
        null
    }
}

fun sendFile(ctx: Context, ip: String, item: SendItem, l: Listener): Boolean {
    var c: HttpURLConnection? = null
    try {
        if (item.size < 0) throw IOException("Unknown file size")
        val ins = ctx.contentResolver.openInputStream(item.uri) ?: throw IOException("Cannot open file")
        val conn = URL("http://$ip:$PORT/upload").openConnection() as HttpURLConnection
        c = conn
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.useCaches = false
        conn.connectTimeout = 5000
        conn.readTimeout = 60000
        conn.setFixedLengthStreamingMode(item.size)
        conn.setRequestProperty("X-Name", URLEncoder.encode(item.name, "UTF-8").replace("+", "%20"))
        val buf = ByteArray(BUF)
        var done = 0L
        var lastDone = 0L
        var lastT = System.nanoTime()
        ins.use { input ->
            conn.outputStream.use { os ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastT > 200_000_000L) {
                        val bps = (done - lastDone) / ((now - lastT) / 1e9)
                        lastT = now
                        lastDone = done
                        l.onProgress("out", item.name, done, item.size, bps)
                    }
                }
                os.flush()
            }
        }
        val code = conn.responseCode
        if (code != 200) throw IOException("Receiver replied $code")
        l.onProgress("out", item.name, item.size, item.size, 0.0)
        l.onDone("out", item.name, true, "", null)
        return true
    } catch (e: Exception) {
        l.onDone("out", item.name, false, e.message ?: "error", null)
        return false
    } finally {
        try { c?.disconnect() } catch (e: Exception) { }
    }
}

/** Finds the other device: UDP broadcast + probing the Wi-Fi gateway (the hotspot owner) + manual IPs. */
class Discovery(private val ctx: Context, private val name: String, private val onPeer: (String, String) -> Unit) {
    @Volatile private var running = false
    val extraIps = CopyOnWriteArraySet<String>()

    fun start() {
        running = true
        thread(isDaemon = true) {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(DISC_PORT))
                s.soTimeout = 1000
                val b = ByteArray(256)
                while (running) {
                    try {
                        val p = DatagramPacket(b, b.size)
                        s.receive(p)
                        val msg = String(p.data, 0, p.length)
                        if (msg.startsWith("ZALA|")) {
                            val ip = p.address.hostAddress ?: continue
                            if (ip != netInfo()?.ip) onPeer(ip, msg.substring(5))
                        }
                    } catch (e: SocketTimeoutException) {
                    }
                }
                s.close()
            } catch (e: Exception) {
            }
        }
        thread(isDaemon = true) {
            try {
                val s = DatagramSocket()
                s.broadcast = true
                var round = 0
                while (running) {
                    try {
                        val msg = "ZALA|$name".toByteArray()
                        val targets = HashSet<String>()
                        targets.add("255.255.255.255")
                        netInfo()?.bcast?.let { targets.add(it) }
                        for (t in targets) s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), DISC_PORT))
                        if (round % 2 == 0) {
                            val probes = HashSet<String>(extraIps)
                            gatewayIp(ctx)?.let { probes.add(it) }
                            for (ip in probes) {
                                if (ip == netInfo()?.ip) continue
                                ping(ip)?.let { onPeer(ip, it) }
                            }
                        }
                    } catch (e: Exception) {
                    }
                    round++
                    Thread.sleep(1500)
                }
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    fun stop() {
        running = false
    }
}
