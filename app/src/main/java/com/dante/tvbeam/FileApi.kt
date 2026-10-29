package com.dante.tvbeam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore

class Root(val id: Int, val name: String, val dir: File)

/** Everything the phone exposes: storage roots, directory listings, mime types, thumbnails. */
object FileApi {

    @Volatile private var rootsCache: List<Root> = emptyList()
    @Volatile private var rootsAt = 0L

    /** Internal storage + any SD card / USB volume. */
    fun roots(ctx: Context): List<Root> {
        val now = System.currentTimeMillis()
        if (rootsCache.isNotEmpty() && now - rootsAt < 20_000) return rootsCache

        val list = ArrayList<Root>()
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory()
        list.add(Root(0, "Internal storage", primary))
        val seen = HashSet<String>()
        try { seen.add(primary.canonicalPath) } catch (e: Exception) { seen.add(primary.path) }

        val dirs = try { ctx.getExternalFilesDirs(null) } catch (e: Exception) { null }
        if (dirs != null) {
            for (f in dirs) {
                if (f == null) continue
                val p = f.absolutePath
                val i = p.indexOf("/Android/")
                if (i <= 0) continue
                val root = File(p.substring(0, i))
                val canon = try { root.canonicalPath } catch (e: Exception) { continue }
                if (!seen.add(canon)) continue
                if (!root.canRead()) continue
                list.add(Root(list.size, "SD card / USB ${list.size}", root))
            }
        }
        rootsCache = list
        rootsAt = now
        return list
    }

    /** Resolves a client path inside a root; null if it escapes the root (.., symlinks). */
    fun resolve(roots: List<Root>, rootId: Int, rel: String): File? {
        val root = roots.getOrNull(rootId) ?: return null
        return try {
            val base = root.dir.canonicalFile
            val f = if (rel.isEmpty()) base else File(base, rel).canonicalFile
            val bp = base.path
            if (f.path == bp || f.path.startsWith(bp + File.separator)) f else null
        } catch (e: Exception) {
            null
        }
    }

    fun rootsJson(roots: List<Root>): JSONObject {
        val arr = JSONArray()
        for (r in roots) {
            var free = 0L
            var total = 0L
            try {
                val st = StatFs(r.dir.path)
                free = st.availableBytes
                total = st.totalBytes
            } catch (e: Exception) {
                // unknown
            }
            arr.put(
                JSONObject().put("id", r.id).put("name", r.name)
                    .put("free", free).put("total", total)
            )
        }
        return JSONObject().put("roots", arr)
    }

    /** Folders + playable media + subtitles. Hidden files are skipped. Null if unreadable. */
    fun listJson(dir: File, atRoot: Boolean): JSONObject? {
        val files = dir.listFiles() ?: return null
        val arr = JSONArray()
        for (f in files) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (atRoot && n == "Android") continue
                arr.put(JSONObject().put("n", n).put("k", "d").put("z", 0L).put("m", f.lastModified()))
            } else {
                val ext = n.substringAfterLast('.', "").lowercase()
                val k = when (ext) {
                    in VIDEO_EXT -> "v"
                    in AUDIO_EXT -> "a"
                    in SUB_EXT -> "s"
                    else -> continue
                }
                arr.put(JSONObject().put("n", n).put("k", k).put("z", f.length()).put("m", f.lastModified()))
            }
        }
        return JSONObject().put("entries", arr)
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi", "divx" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "3gp" -> "video/3gpp"
        "wmv", "asf" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "ogv" -> "video/ogg"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        "mka" -> "audio/x-matroska"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------ thumbnails

    private val thumbGate = Semaphore(2)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** A cached JPEG preview (video frame or embedded cover art), or null. */
    fun thumb(ctx: Context, f: File, width: Int): File? {
        val dir = File(ctx.cacheDir, "thumbs")
        dir.mkdirs()
        val out = File(dir, md5(f.absolutePath + "|" + f.lastModified() + "|" + width) + ".jpg")
        if (out.exists() && out.length() > 0) return out

        thumbGate.acquire()
        try {
            if (out.exists() && out.length() > 0) return out
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val ext = f.name.substringAfterLast('.', "").lowercase()
                var bmp: Bitmap? = null
                if (ext in AUDIO_EXT) {
                    val art = mmr.embeddedPicture
                    if (art != null) bmp = BitmapFactory.decodeByteArray(art, 0, art.size)
                } else {
                    bmp = mmr.getFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (bmp == null || bmp.width <= 0) return null
                val h = (bmp.height.toLong() * width / bmp.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, width, h, true)
                val tmp = File(dir, out.name + ".tmp")
                FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (!tmp.renameTo(out)) return null
                return out
            } catch (e: Exception) {
                return null
            } finally {
                try { mmr.release() } catch (e: Exception) { }
            }
        } finally {
            thumbGate.release()
        }
    }
}
