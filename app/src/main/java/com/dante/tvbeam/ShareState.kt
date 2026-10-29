package com.dante.tvbeam

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class Pending(val id: String, val name: String, val at: Long)

/** Process-wide state shared by the server, the service and the phone UI. */
object Share {
    val running = MutableStateFlow(false)
    val streams = MutableStateFlow(0)
    val pending = MutableStateFlow<List<Pending>>(emptyList())
    val trustedTick = MutableStateFlow(0)

    @Volatile var port: Int = Proto.HTTP_PORT

    private val denied = ConcurrentHashMap<String, Long>()

    /**
     * Called by the HTTP server when a TV asks to pair.
     * Returns ("ok", token) | ("pending", null) | ("denied", null).
     */
    fun pair(c: Context, id: String, name: String): Pair<String, String?> {
        PhoneStore.tokenFor(c, id)?.let { return "ok" to it }

        if (PhoneStore.autoAllow(c)) {
            val t = PhoneStore.addTrusted(c, id, name)
            trustedTick.update { it + 1 }
            return "ok" to t
        }

        val now = System.currentTimeMillis()
        val d = denied[id]
        if (d != null && now - d < 60_000) return "denied" to null

        pending.update { l ->
            l.filter { it.id != id && now - it.at < 15_000 } + Pending(id, name, now)
        }
        return "pending" to null
    }

    fun allow(c: Context, id: String) {
        val name = pending.value.firstOrNull { it.id == id }?.name ?: id
        PhoneStore.addTrusted(c, id, name)
        denied.remove(id)
        pending.update { l -> l.filter { it.id != id } }
        trustedTick.update { it + 1 }
    }

    fun deny(id: String) {
        denied[id] = System.currentTimeMillis()
        pending.update { l -> l.filter { it.id != id } }
    }
}
