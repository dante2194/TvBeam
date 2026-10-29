package com.dante.tvbeam

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny HTTP/1.1 file server (no external libraries).
 *
 *  GET /api/info                     public  – who am I (used for discovery verification)
 *  GET /api/pair?id=&name=           public  – ask for a token (auto-allowed or phone approves)
 *  GET /api/roots        ?t=TOKEN    storage roots
 *  GET /api/list         ?t=&root=&path=      folders + media + subtitles
 *  GET /f/<root>/<path>  ?t=         file bytes, with Range support (seekable video)
 *  GET /thumb/<root>/<path> ?t=&w=   cached JPEG preview
 */
class BeamServer(private val ctx: Context) {

    @Volatile
    var port: Int = 0
        private set

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "beam-http").apply { isDaemon = true }
    }

    fun info(): JSONObject = JSONObject()
        .put("app", Proto.APP)
        .put("v", 1)
        .put("id", PhoneStore.deviceId(ctx))
        .put("name", PhoneStore.name(ctx))
        .put("port", port)

    /** Binds the first free port from 8765 upward and starts accepting. */
    fun start(): Int {
        var last: Exception? = null
        for (p in Proto.HTTP_PORT..Proto.HTTP_PORT + 20) {
            var opened: ServerSocket? = null
            try {
                val ss = ServerSocket()
                opened = ss
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                server = ss
                port = p
                running = true
                pool.execute { acceptLoop(ss) }
                return p
            } catch (e: IOException) {
                last = e
                try { opened?.close() } catch (x: Exception) { }
            }
        }
        throw last ?: IOException("No free port")
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        pool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (!running) return
                continue
            }
            try {
                pool.execute { handle(s) }
            } catch (e: Exception) {
                try { s.close() } catch (x: Exception) { }
                return
            }
        }
    }

    // ------------------------------------------------------------ request parsing

    private class Req(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>
    )

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun readRequest(inp: InputStream): Req? {
        val line = readLine(inp) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val rawPath = target.substringBefore('?')
        val rawQuery = if (target.contains('?')) target.substringAfter('?') else ""

        val query = HashMap<String, String>()
        if (rawQuery.isNotEmpty()) {
            for (kv in rawQuery.split('&')) {
                if (kv.isEmpty()) continue
                val k = Uri.decode(kv.substringBefore('='))
                val v = if (kv.contains('=')) Uri.decode(kv.substringAfter('=')) else ""
                query[k] = v
            }
        }

        val headers = HashMap<String, String>()
        var n = 0
        while (n++ < 100) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Req(parts[0].uppercase(), Uri.decode(rawPath), query, headers)
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 20_000
            s.tcpNoDelay = true
            val inp = BufferedInputStream(s.getInputStream(), 8192)
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val req = readRequest(inp)
            if (req != null) {
                route(req, out)
                out.flush()
            }
        } catch (e: Exception) {
            // client went away – normal for video seeking
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ responses

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        else -> "Error"
    }

    private fun head(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun json(out: OutputStream, code: Int, body: String, headOnly: Boolean) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        head(
            out, code, listOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-store"
            )
        )
        if (!headOnly) out.write(bytes)
    }

    private fun err(out: OutputStream, code: Int, msg: String, headOnly: Boolean = false) =
        json(out, code, JSONObject().put("error", msg).toString(), headOnly)

    // ------------------------------------------------------------ routing

    private fun route(req: Req, out: OutputStream) {
        val headOnly = req.method == "HEAD"
        if (req.method != "GET" && req.method != "HEAD" && req.method != "POST") {
            err(out, 405, "method not allowed")
            return
        }
        val p = req.path

        if (p == "/api/info") {
            json(out, 200, info().toString(), headOnly)
            return
        }
        if (p == "/api/pair") {
            val id = req.query["id"].orEmpty().take(64)
            val name = req.query["name"].orEmpty().ifBlank { "TV" }.take(48)
            if (id.isBlank()) {
                err(out, 400, "missing id", headOnly)
                return
            }
            val (status, token) = Share.pair(ctx, id, name)
            val o = JSONObject().put("status", status)
            if (token != null) o.put("token", token)
            json(out, 200, o.toString(), headOnly)
            return
        }

        val tok = req.query["t"] ?: req.headers["x-token"]
        if (!PhoneStore.checkToken(ctx, tok)) {
            err(out, 401, "unauthorized", headOnly)
            return
        }

        val roots = FileApi.roots(ctx)
        when {
            p == "/api/roots" -> json(out, 200, FileApi.rootsJson(roots).toString(), headOnly)

            p == "/api/list" -> {
                val rootId = req.query["root"]?.toIntOrNull() ?: -1
                val rel = req.query["path"].orEmpty().trim('/')
                val dir = FileApi.resolve(roots, rootId, rel)
                if (dir == null) {
                    err(out, 403, "forbidden", headOnly)
                } else if (!dir.isDirectory) {
                    err(out, 404, "not a folder", headOnly)
                } else {
                    val j = FileApi.listJson(dir, rel.isEmpty())
                    if (j == null) err(out, 403, "unreadable", headOnly)
                    else json(out, 200, j.toString(), headOnly)
                }
            }

            p.startsWith("/f/") || p.startsWith("/thumb/") -> {
                val isThumb = p.startsWith("/thumb/")
                val rest = p.removePrefix(if (isThumb) "/thumb/" else "/f/")
                val rootId = rest.substringBefore('/').toIntOrNull() ?: -1
                val rel = rest.substringAfter('/', "").trim('/')
                val f = FileApi.resolve(roots, rootId, rel)
                if (f == null || !f.isFile) {
                    err(out, 404, "not found", headOnly)
                } else if (isThumb) {
                    val w = (req.query["w"]?.toIntOrNull() ?: 320).coerceIn(64, 960)
                    val t = FileApi.thumb(ctx, f, w)
                    if (t == null) err(out, 404, "no preview", headOnly)
                    else serveFile(req, out, t, "image/jpeg", count = false, cache = true)
                } else {
                    serveFile(req, out, f, FileApi.mimeFor(f.name), count = true, cache = false)
                }
            }

            else -> err(out, 404, "not found", headOnly)
        }
    }

    /** Serves a file with HTTP Range support so players can seek instantly. */
    private fun serveFile(
        req: Req, out: OutputStream, f: File, mime: String, count: Boolean, cache: Boolean
    ) {
        if (!f.canRead()) {
            err(out, 403, "unreadable")
            return
        }
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false

        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val spec = range.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        val n = b.toLong()
                        start = maxOf(0L, len - n)
                        end = len - 1
                    } else {
                        start = a.toLong()
                        end = if (b.isEmpty()) len - 1 else minOf(b.toLong(), len - 1)
                    }
                    partial = true
                } catch (e: NumberFormatException) {
                    start = 0L
                    end = len - 1
                    partial = false
                }
                if (partial && (start > end || start >= len)) {
                    head(out, 416, listOf("Content-Range" to "bytes */$len", "Content-Length" to "0"))
                    return
                }
            }
        }

        val n = if (len == 0L) 0L else end - start + 1
        val h = ArrayList<Pair<String, String>>()
        h.add("Content-Type" to mime)
        h.add("Accept-Ranges" to "bytes")
        h.add("Content-Length" to n.toString())
        if (partial) h.add("Content-Range" to "bytes $start-$end/$len")
        h.add("Cache-Control" to (if (cache) "private, max-age=86400" else "no-store"))
        head(out, if (partial) 206 else 200, h)

        if (req.method == "HEAD" || n == 0L) return

        if (count) Share.streams.update { it + 1 }
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(128 * 1024)
                var left = n
                while (left > 0) {
                    val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r)
                    left -= r
                }
            }
        } finally {
            if (count) Share.streams.update { it - 1 }
        }
    }
}
