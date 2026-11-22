package com.zala.sharing

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class WrongPinException : IOException("Wrong code")

/**
 * Security design:
 *  - Fresh ECDH (P-256) key exchange for every connection.
 *  - The 6-digit code shown on the receiver is mixed into the key. A wrong code = no data can be decrypted.
 *  - Everything (file name, size, file data) is encrypted + authenticated with AES-256-GCM.
 *  - Only local-network addresses are ever connected to or accepted.
 */
object Crypto {
    private val rnd = SecureRandom()

    fun newPin(): String = String.format("%06d", rnd.nextInt(1_000_000))

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    fun deriveKey(priv: PrivateKey, peerPub: ByteArray, clientPub: ByteArray, serverPub: ByteArray, pin: String): SecretKeySpec {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val secret = ka.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(secret)
        md.update(clientPub)
        md.update(serverPub)
        md.update(pin.toByteArray())
        md.update("zala-v1".toByteArray())
        return SecretKeySpec(md.digest(), "AES")
    }
}

class Chan(
    private val key: SecretKeySpec,
    private val inp: DataInputStream,
    private val out: DataOutputStream,
    private val sendDir: Int,
    private val recvDir: Int
) {
    private var sc = 0L
    private var rc = 0L

    private fun nonce(dir: Int, ctr: Long): ByteArray = ByteBuffer.allocate(12).putInt(dir).putLong(ctr).array()

    fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(sendDir, sc++)))
        val ct = c.doFinal(data, off, len)
        out.writeInt(ct.size)
        out.write(ct)
        out.flush()
    }

    fun read(): ByteArray {
        val n = inp.readInt()
        if (n == -1) throw WrongPinException()
        if (n < 16 || n > BUF + 16) throw IOException("Bad data")
        val ct = ByteArray(n)
        inp.readFully(ct)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(recvDir, rc++)))
        return c.doFinal(ct)
    }
}

fun ensureLan(ip: String) {
    if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) throw IOException("Enter a numeric IP address")
    val a = InetAddress.getByName(ip)
    if (!(a.isSiteLocalAddress || a.isLinkLocalAddress)) throw IOException("Blocked: only local network addresses are allowed")
}

/** Receiver. Runs only while the Receive screen is open. */
class ZalaServer(private val ctx: Context, private val deviceName: String, private val l: Listener) {
    @Volatile private var running = false
    @Volatile var pin: String = Crypto.newPin()
    private val fails = AtomicInteger(0)
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
                if (running) l.onDone("in", "Receiver", false, "Cannot start receiving: ${e.message}", null)
            }
        }
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (e: Exception) { }
    }

    private fun badPin(out: DataOutputStream) {
        try { Thread.sleep(1000) } catch (e: Exception) { }
        try {
            out.writeInt(-1)
            out.flush()
        } catch (e: Exception) { }
        if (fails.incrementAndGet() >= 5) {
            pin = Crypto.newPin()
            fails.set(0)
            l.onDone("in", "Security", false, "Too many wrong codes. A new code was created.", null)
        }
    }

    private fun handle(sock: Socket) {
        try {
            val a = sock.inetAddress
            if (!(a.isSiteLocalAddress || a.isLinkLocalAddress)) return
            sock.tcpNoDelay = true
            sock.soTimeout = 20000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 65536))
            val magic = ByteArray(5)
            inp.readFully(magic)
            val m = String(magic)
            if (m == "ZPING") {
                out.write("ZALA|$deviceName\n".toByteArray())
                out.flush()
                return
            }
            if (m != "ZALA1") return
            val cl = inp.readUnsignedShort()
            if (cl < 50 || cl > 200) return
            val clientPub = ByteArray(cl)
            inp.readFully(clientPub)
            val kp = Crypto.newKeyPair()
            val myPub = kp.public.encoded
            out.writeShort(myPub.size)
            out.write(myPub)
            out.flush()
            val key = Crypto.deriveKey(kp.private, clientPub, clientPub, myPub, pin)
            val ch = Chan(key, inp, out, 2, 1)
            val header = try {
                String(ch.read())
            } catch (e: GeneralSecurityException) {
                badPin(out)
                return
            }
            fails.set(0)
            val p = header.split("\n", limit = 3)
            if (p[0] == "CHECK") {
                ch.write("OK".toByteArray())
                return
            }
            if (p[0] != "SEND" || p.size < 3) return
            val size = p[1].toLongOrNull() ?: return
            if (size < 0) return
            val name = p[2].take(150)
            receive(ch, sock, name, size)
        } catch (e: Exception) {
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    private fun receive(ch: Chan, sock: Socket, name: String, size: Long) {
        val saved = try {
            openSave(ctx, name)
        } catch (e: Exception) {
            try { ch.write("ERR".toByteArray()) } catch (x: Exception) { }
            l.onDone("in", name, false, e.message ?: "Cannot save file", null)
            return
        }
        ch.write("OK".toByteArray())
        sock.soTimeout = 30000
        var done = 0L
        var lastDone = 0L
        var lastT = System.nanoTime()
        try {
            while (done < size) {
                val chunk = ch.read()
                if (done + chunk.size > size) throw IOException("Too much data")
                saved.out.write(chunk)
                done += chunk.size
                val now = System.nanoTime()
                if (now - lastT > 200_000_000L) {
                    val bps = (done - lastDone) / ((now - lastT) / 1e9)
                    lastT = now
                    lastDone = done
                    l.onProgress("in", name, done, size, bps)
                }
            }
            saved.finish()
            ch.write("DONE".toByteArray())
            l.onProgress("in", name, size, size, 0.0)
            l.onDone("in", name, true, "", saved.uri)
        } catch (e: Exception) {
            saved.abort()
            l.onDone("in", name, false, e.message ?: "Transfer stopped", null)
        }
    }
}

class Conn(val sock: Socket, val ch: Chan)

fun connect(ip: String, pin: String): Conn {
    ensureLan(ip)
    val s = Socket()
    s.tcpNoDelay = true
    s.sendBufferSize = 4 * 1024 * 1024
    s.connect(InetSocketAddress(ip, PORT), 5000)
    s.soTimeout = 30000
    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
    val kp = Crypto.newKeyPair()
    val pub = kp.public.encoded
    out.write("ZALA1".toByteArray())
    out.writeShort(pub.size)
    out.write(pub)
    out.flush()
    val sl = inp.readUnsignedShort()
    if (sl < 50 || sl > 200) throw IOException("Bad reply")
    val serverPub = ByteArray(sl)
    inp.readFully(serverPub)
    val key = Crypto.deriveKey(kp.private, serverPub, pub, serverPub, pin)
    return Conn(s, Chan(key, inp, out, 1, 2))
}

/** Returns null if the code is correct, otherwise a message to show. */
fun checkPin(ip: String, pin: String): String? {
    var c: Conn? = null
    try {
        val cc = connect(ip, pin)
        c = cc
        cc.ch.write("CHECK\n0\n".toByteArray())
        return if (String(cc.ch.read()) == "OK") null else "The other device refused"
    } catch (e: WrongPinException) {
        return "Wrong code. Please try again."
    } catch (e: Exception) {
        return "Cannot connect: ${e.message}"
    } finally {
        try { c?.sock?.close() } catch (e: Exception) { }
    }
}

fun sendFile(ctx: Context, ip: String, pin: String, item: SendItem, l: Listener): Boolean {
    var conn: Conn? = null
    try {
        if (item.size < 0) throw IOException("Unknown file size")
        val input = ctx.contentResolver.openInputStream(item.uri) ?: throw IOException("Cannot open file")
        val cc = connect(ip, pin)
        conn = cc
        val ch = cc.ch
        ch.write("SEND\n${item.size}\n${item.name.replace('\n', ' ')}".toByteArray())
        if (String(ch.read()) != "OK") throw IOException("The other device cannot save this file")
        val buf = ByteArray(BUF)
        var done = 0L
        var lastDone = 0L
        var lastT = System.nanoTime()
        input.use { ins ->
            while (done < item.size) {
                val want = minOf(buf.size.toLong(), item.size - done).toInt()
                var n = 0
                while (n < want) {
                    val r = ins.read(buf, n, want - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) throw IOException("File ended early")
                ch.write(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastT > 200_000_000L) {
                    val bps = (done - lastDone) / ((now - lastT) / 1e9)
                    lastT = now
                    lastDone = done
                    l.onProgress("out", item.name, done, item.size, bps)
                }
            }
        }
        if (String(ch.read()) != "DONE") throw IOException("The other device did not confirm")
        l.onProgress("out", item.name, item.size, item.size, 0.0)
        l.onDone("out", item.name, true, "", null)
        return true
    } catch (e: WrongPinException) {
        l.onDone("out", item.name, false, "Wrong code. Connect again.", null)
        return false
    } catch (e: Exception) {
        l.onDone("out", item.name, false, e.message ?: "error", null)
        return false
    } finally {
        try { conn?.sock?.close() } catch (e: Exception) { }
    }
}

/** Asks one address whether it is a Zala Sharing receiver. */
fun probe(ip: String): String? {
    return try {
        ensureLan(ip)
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, PORT), 400)
            s.soTimeout = 800
            s.getOutputStream().write("ZPING".toByteArray())
            s.getOutputStream().flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            if (line != null && line.startsWith("ZALA|")) line.substring(5) else null
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    } catch (e: Exception) {
        null
    }
}

/** Looks for receivers on the same Wi-Fi/Hotspot (a few seconds). */
fun scanLan(): List<Pair<String, String>> {
    val me = netInfo()?.ip ?: return emptyList()
    val base = me.substringBeforeLast('.')
    val found = CopyOnWriteArrayList<Pair<String, String>>()
    val pool = Executors.newFixedThreadPool(64)
    for (i in 1..254) {
        val ip = "$base.$i"
        if (ip == me) continue
        pool.execute {
            val n = probe(ip)
            if (n != null) found.add(ip to n)
        }
    }
    pool.shutdown()
    pool.awaitTermination(8, TimeUnit.SECONDS)
    return found.toList()
}
