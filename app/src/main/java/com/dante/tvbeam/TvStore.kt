package com.dante.tvbeam

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.security.SecureRandom

/** TV-side storage: this TV's identity, known phones, their tokens, resume positions. */
object TvStore {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("tvbeam_tv", Context.MODE_PRIVATE)

    fun tvId(c: Context): String {
        val s = sp(c)
        s.getString("id", null)?.let { return it }
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        val id = b.joinToString("") { "%02x".format(it) }
        s.edit().putString("id", id).apply()
        return id
    }

    fun tvName(c: Context): String {
        val n = try {
            Settings.Global.getString(c.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return if (n.isNullOrBlank()) Build.MODEL else n
    }

    // ------------------------------------------------------------ phones

    fun phones(c: Context): Map<String, PhoneInfo> {
        val out = LinkedHashMap<String, PhoneInfo>()
        try {
            val o = JSONObject(sp(c).getString("phones", "{}") ?: "{}")
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                out[k] = PhoneInfo(k, j.optString("n", k), j.getString("h"), j.optInt("p", Proto.HTTP_PORT))
            }
        } catch (e: Exception) {
            // start clean
        }
        return out
    }

    private fun writePhones(c: Context, m: Map<String, PhoneInfo>) {
        val o = JSONObject()
        for (p in m.values) {
            o.put(p.id, JSONObject().put("n", p.name).put("h", p.host).put("p", p.port))
        }
        sp(c).edit().putString("phones", o.toString()).apply()
    }

    fun savePhone(c: Context, p: PhoneInfo) {
        val m = LinkedHashMap(phones(c))
        if (m[p.id] == p) return
        m[p.id] = p
        writePhones(c, m)
    }

    fun forgetPhone(c: Context, id: String) {
        val m = LinkedHashMap(phones(c))
        m.remove(id)
        writePhones(c, m)
        sp(c).edit().remove("tok_$id").apply()
    }

    fun token(c: Context, phoneId: String): String? = sp(c).getString("tok_$phoneId", null)
    fun setToken(c: Context, phoneId: String, t: String) { sp(c).edit().putString("tok_$phoneId", t).apply() }
    fun clearToken(c: Context, phoneId: String) { sp(c).edit().remove("tok_$phoneId").apply() }

    fun lastPhone(c: Context): String? = sp(c).getString("last", null)
    fun setLastPhone(c: Context, id: String) { sp(c).edit().putString("last", id).apply() }

    // ------------------------------------------------------------ resume

    fun resume(c: Context, key: String): Pair<Long, Long>? {
        val s = sp(c).getString("r:$key", null) ?: return null
        val p = s.split('|')
        if (p.size != 2) return null
        val pos = p[0].toLongOrNull() ?: return null
        val dur = p[1].toLongOrNull() ?: return null
        return pos to dur
    }

    fun saveResume(c: Context, key: String, pos: Long, dur: Long) {
        sp(c).edit().putString("r:$key", "$pos|$dur").apply()
    }

    fun clearResume(c: Context, key: String) { sp(c).edit().remove("r:$key").apply() }
}
