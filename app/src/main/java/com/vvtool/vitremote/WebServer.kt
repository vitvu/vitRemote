package com.vvtool.vitremote

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/**
 * Minimal HTTP + WebSocket server (RFC 6455), no external libraries.
 * One thread per connection, blocking I/O => ~0% CPU while idle.
 */
class WebServer(
    private val port: Int,
    private val pin: String,
    indexHtml: ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(c: Client)
        fun onText(c: Client, msg: String)
        fun onClose(c: Client)
    }

    // Gzip the page once at startup to save bandwidth
    private val indexGz: ByteArray = ByteArrayOutputStream().also { bo ->
        GZIPOutputStream(bo).use { it.write(indexHtml) }
    }.toByteArray()

    val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                Thread({ handle(s) }, "vit-conn").apply { isDaemon = true }.start()
            }
        }, "vit-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15000
            val inp = s.getInputStream().buffered(8192)
            val out = s.getOutputStream()
            val reqLine = readLine(inp) ?: return s.close()
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(inp) ?: return s.close()
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val target = reqLine.split(' ').getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val key = headers["sec-websocket-key"]

            if (path == "/ws" && key != null) {
                val k = query.split('&').firstOrNull { it.startsWith("k=") }?.substring(2)
                if (k != pin) {
                    respond(out, "401 Unauthorized", "text/plain", "Wrong PIN".toByteArray())
                    return s.close()
                }
                val accept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                    Base64.NO_WRAP
                )
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
                )
                out.flush()
                s.soTimeout = 0
                val c = Client(s, out)
                clients.add(c)
                listener.onOpen(c)
                try { wsLoop(c, inp) } finally {
                    clients.remove(c); c.close(); listener.onClose(c)
                }
            } else if (path == "/" || path == "/index.html") {
                val gz = headers["accept-encoding"]?.contains("gzip") == true
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        (if (gz) "Content-Encoding: gzip\r\n" else "") +
                        "Content-Length: ${if (gz) indexGz.size else indexRaw.size}\r\n" +
                        "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
                )
                out.write(if (gz) indexGz else indexRaw)
                out.flush(); s.close()
            } else {
                respond(out, "404 Not Found", "text/plain", "404".toByteArray()); s.close()
            }
        } catch (_: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private val indexRaw = indexHtml

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun wsLoop(c: Client, inp: InputStream) {
        val hdr = ByteArray(8)
        val mask = ByteArray(4)
        while (true) {
            readFully(inp, hdr, 2)
            val op = hdr[0].toInt() and 0x0F
            val masked = hdr[1].toInt() and 0x80 != 0
            var len = (hdr[1].toInt() and 0x7F).toLong()
            if (len == 126L) {
                readFully(inp, hdr, 2)
                len = ((hdr[0].toInt() and 0xFF) shl 8 or (hdr[1].toInt() and 0xFF)).toLong()
            } else if (len == 127L) {
                readFully(inp, hdr, 8)
                len = 0; for (i in 0 until 8) len = (len shl 8) or (hdr[i].toLong() and 0xFF)
            }
            if (len > 1_000_000) return
            if (masked) readFully(inp, mask, 4)
            val data = ByteArray(len.toInt())
            readFully(inp, data, data.size)
            if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                0x1 -> listener.onText(c, String(data, Charsets.UTF_8))
                0x8 -> { c.sendFrame(0x8, ByteArray(0)); return }
                0x9 -> c.sendFrame(0xA, data)
            }
        }
    }

    private fun readFully(inp: InputStream, b: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
    }

    class Client(private val socket: Socket, private val out: OutputStream) {
        @Volatile var liveWidth = 0          // 0 = not watching
        @Volatile var lastFrameCrc = 0L
        val addr: String = socket.inetAddress?.hostAddress ?: "?"

        fun send(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
        fun sendBin(data: ByteArray) = sendFrame(0x2, data)

        @Synchronized
        fun sendFrame(op: Int, data: ByteArray) {
            if (socket.isClosed) return
            val n = data.size
            val hl = if (n < 126) 2 else if (n < 65536) 4 else 10
            // Header + payload in one write => one TCP packet for small messages
            val buf = ByteArray(hl + n)
            buf[0] = (0x80 or op).toByte()
            when (hl) {
                2 -> buf[1] = n.toByte()
                4 -> { buf[1] = 126; buf[2] = (n shr 8).toByte(); buf[3] = n.toByte() }
                else -> { buf[1] = 127; for (i in 0 until 8) buf[2 + i] = (n.toLong() shr (56 - 8 * i)).toByte() }
            }
            System.arraycopy(data, 0, buf, hl, n)
            try { out.write(buf); out.flush() } catch (_: Exception) { close() }
        }

        fun close() { try { socket.close() } catch (_: Exception) {} }
    }
}
