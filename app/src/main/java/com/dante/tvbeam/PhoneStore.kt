package com.dante.tvbeam

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class Trusted(val id: String, val name: String, val token: String)

/** Phone-side settings and the list of paired TVs (id → secret token). */
object PhoneStore {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("tvbeam_phone", Context.MODE_PRIVATE)

    private val trusted = ConcurrentHashMap<String, Trusted>()
    @Volatile private var loaded = false

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun deviceId(c: Context): String {
        val s = sp(c)
        s.getString("id", null)?.let { return it }
        val id = randomHex(8)
        s.edit().putString("id", id).apply()
        return id
    }

    fun defaultName(c: Context): String {
        val n = try {
            Settings.Global.getString(c.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return if (n.isNullOrBlank()) Build.MODEL else n
    }

    fun name(c: Context): String =
        sp(c).getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName(c)

    fun setName(c: Context, n: String) {
        sp(c).edit().putString("name", n.trim().take(40)).apply()
    }

    fun autoAllow(c: Context): Boolean = sp(c).getBoolean("auto_allow", true)
    fun setAutoAllow(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_allow", v).apply() }

    fun autoStart(c: Context): Boolean = sp(c).getBoolean("auto_start", true)
    fun setAutoStart(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_start", v).apply() }

    // ------------------------------------------------------------ trusted TVs

    private fun load(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val o = JSONObject(sp(c).getString("trusted", "{}") ?: "{}")
                for (k in o.keys()) {
                    val t = o.getJSONObject(k)
                    trusted[k] = Trusted(k, t.optString("n", k), t.getString("t"))
                }
            } catch (e: Exception) {
                // start clean
            }
            loaded = true
        }
    }

    private fun persist(c: Context) {
        val o = JSONObject()
        for (t in trusted.values) {
            o.put(t.id, JSONObject().put("n", t.name).put("t", t.token))
        }
        sp(c).edit().putString("trusted", o.toString()).apply()
    }

    fun trustedList(c: Context): List<Trusted> {
        load(c)
        return trusted.values.sortedBy { it.name.lowercase() }
    }

    fun tokenFor(c: Context, id: String): String? {
        load(c)
        return trusted[id]?.token
    }

    fun addTrusted(c: Context, id: String, name: String): String {
        load(c)
        val token = trusted[id]?.token ?: randomHex(24)
        trusted[id] = Trusted(id, name, token)
        persist(c)
        return token
    }

    fun removeTrusted(c: Context, id: String) {
        load(c)
        trusted.remove(id)
        persist(c)
    }

    fun checkToken(c: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        load(c)
        return trusted.values.any { it.token == token }
    }
}
