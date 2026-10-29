package com.dante.tvbeam

import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AuthException : IOException("Unauthorized")
class DeniedException : IOException("The phone denied this TV")
class PairTimeoutException : IOException("No answer from the phone")

data class RootInfo(val id: Int, val name: String, val free: Long, val total: Long)
data class Item(val name: String, val kind: String, val size: Long, val mtime: Long)

/** TV → phone HTTP client. All calls are blocking; run them on Dispatchers.IO. */
object Remote {

    private fun base(p: PhoneInfo) = "http://${p.host}:${p.port}"

    private fun get(url: String, timeoutMs: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Asks a host whether it is a TvBeam phone. Null if not (or unreachable). */
    fun info(host: String, port: Int, timeoutMs: Int = 1500): PhoneInfo? = try {
        val (code, body) = get("http://$host:$port/api/info", timeoutMs)
        if (code != 200) {
            null
        } else {
            val o = JSONObject(body)
            if (o.optString("app") != Proto.APP) null
            else PhoneInfo(o.getString("id"), o.optString("name", host), host, port)
        }
    } catch (e: Exception) {
        null
    }

    /** Returns ("ok", token) | ("pending", null) | ("denied", null). */
    fun pair(p: PhoneInfo, tvId: String, tvName: String): Pair<String, String?> {
        val url = "${base(p)}/api/pair?id=${Uri.encode(tvId)}&name=${Uri.encode(tvName)}"
        val (code, body) = get(url, 6000)
        if (code != 200) throw IOException("HTTP $code")
        val o = JSONObject(body)
        return o.optString("status") to (if (o.has("token")) o.getString("token") else null)
    }

    fun roots(p: PhoneInfo, token: String): List<RootInfo> {
        val (code, body) = get("${base(p)}/api/roots?t=${Uri.encode(token)}", 8000)
        if (code == 401) throw AuthException()
        if (code != 200) throw IOException("HTTP $code")
        val arr = JSONObject(body).getJSONArray("roots")
        val out = ArrayList<RootInfo>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(RootInfo(r.getInt("id"), r.optString("name", "Storage"), r.optLong("free"), r.optLong("total")))
        }
        return out
    }

    fun list(p: PhoneInfo, token: String, rootId: Int, rel: String): List<Item> {
        val url = "${base(p)}/api/list?t=${Uri.encode(token)}&root=$rootId&path=${Uri.encode(rel)}"
        val (code, body) = get(url, 20000)
        if (code == 401) throw AuthException()
        if (code != 200) throw IOException("Can't open this folder (HTTP $code)")
        val arr = JSONObject(body).getJSONArray("entries")
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            out.add(Item(e.getString("n"), e.getString("k"), e.optLong("z"), e.optLong("m")))
        }
        return out
    }

    private fun encPath(rel: String): String =
        rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    fun fileUrl(p: PhoneInfo, token: String, rootId: Int, rel: String) =
        "${base(p)}/f/$rootId/${encPath(rel)}?t=${Uri.encode(token)}"

    fun thumbUrl(p: PhoneInfo, token: String, rootId: Int, rel: String) =
        "${base(p)}/thumb/$rootId/${encPath(rel)}?t=${Uri.encode(token)}&w=320"
}
