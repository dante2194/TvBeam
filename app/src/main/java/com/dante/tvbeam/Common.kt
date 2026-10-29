package com.dante.tvbeam

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/** Wire constants shared by the phone (server) and the TV (client). */
object Proto {
    const val SERVICE_TYPE = "_tvbeam._tcp."
    const val HTTP_PORT = 8765
    const val UDP_PORT = 8766
    const val MAGIC = "TVBEAM_DISCOVER_V1"
    const val APP = "tvbeam"
}

val VIDEO_EXT = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm", "ts", "m2ts",
    "mts", "mpg", "mpeg", "3gp", "ogv", "vob", "divx", "asf"
)
val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "mka")
val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")

data class PhoneInfo(val id: String, val name: String, val host: String, val port: Int)

enum class Kind { PHONE, ROOT, FOLDER, VIDEO, AUDIO }

data class Entry(
    val key: String,
    val name: String,
    val kind: Kind,
    val size: Long = 0L,
    val subtitle: String = "",
    val rootId: Int = -1,
    val rel: String = "",
    val thumb: String? = null,
    val resumeKey: String? = null
)

data class SubFile(val name: String, val url: String)
data class PItem(val name: String, val url: String, val key: String)

/** Hand-off between the browser and the player (avoids huge Intent extras). */
object Playlist {
    @Volatile var items: List<PItem> = emptyList()
    @Volatile var index: Int = 0
    @Volatile var subs: List<SubFile> = emptyList()
}

object NetUtil {
    /** This device's Wi-Fi / LAN IPv4 address, or null when offline. */
    fun localIp(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    if (!a.isSiteLocalAddress) continue
                    val ip = a.hostAddress ?: continue
                    val n = ni.name ?: ""
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("eth")) return ip
                    if (fallback == null) fallback = ip
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return fallback
    }
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    b >= (1L shl 20) -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
    b >= (1L shl 10) -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var si = i
            while (si < a.length && a[si].isDigit()) si++
            var sj = j
            while (sj < b.length && b[sj].isDigit()) sj++
            val na = a.substring(i, si).trimStart('0')
            val nb = b.substring(j, sj).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = si
            j = sj
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
