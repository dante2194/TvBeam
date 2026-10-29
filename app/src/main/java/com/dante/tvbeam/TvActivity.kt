package com.dante.tvbeam

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dante.tvbeam.databinding.ActivityTvBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * TV app: finds phones running TvBeam, pairs automatically, and browses their folders.
 * Levels:  0 = phones   1 = storage roots of a phone   2 = folder inside a root
 */
class TvActivity : AppCompatActivity() {

    private lateinit var b: ActivityTvBinding
    private lateinit var adapter: EntryAdapter
    private lateinit var discovery: Discovery

    private val phones = LinkedHashMap<String, PhoneInfo>()
    private val live = LinkedHashSet<String>()
    private var roots: List<RootInfo> = emptyList()
    private var subs: List<SubFile> = emptyList()

    private var cur: PhoneInfo? = null
    private var token: String? = null
    private var rootId = -1
    private var rel = ""

    private var loadJob: Job? = null
    private var scanning = false
    private var autoDone = false
    private var userActed = false

    private class Loaded(val entries: List<Entry>, val subs: List<SubFile>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTvBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = EntryAdapter(this, lifecycleScope, ::onEntryClick, ::onEntryLong)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.itemAnimator = null

        b.btnRescan.setOnClickListener { rescan() }
        b.btnAdd.setOnClickListener {
            userActed = true
            showAddPhone()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        phones.putAll(TvStore.phones(this))
        showPhones(null)

        discovery = Discovery(this, lifecycleScope, ::onPhone, ::onScan)
        discovery.start()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    override fun onDestroy() {
        if (::discovery.isInitialized) discovery.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            rescan()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ discovery callbacks

    private fun rescan() {
        userActed = true
        if (cur != null) showPhones(null)
        live.clear()
        scanning = true
        b.progress.visibility = View.VISIBLE
        b.statusText.text = "Looking for phones…"
        discovery.start(scanNow = true)
        showPhones(null, focus = false)
    }

    private fun onPhone(p: PhoneInfo) {
        live.add(p.id)
        phones[p.id] = p
        TvStore.savePhone(this, p)
        b.statusText.text = when (live.size) {
            1 -> "1 phone online"
            else -> "${live.size} phones online"
        }
        if (cur == null) showPhones(null, focus = currentFocus == null || currentFocus === b.btnRescan)
        maybeAutoOpen(false)
    }

    private fun onScan(done: Int, total: Int) {
        if (total < 0) {
            scanning = false
            b.progress.visibility = View.INVISIBLE
            b.statusText.text = when (live.size) {
                0 -> "No phone found — open TvBeam on your phone and tap Start sharing"
                1 -> "1 phone online"
                else -> "${live.size} phones online"
            }
            if (cur == null) showPhones(null, focus = false)
            maybeAutoOpen(true)
        } else {
            scanning = true
            b.progress.visibility = View.VISIBLE
            b.statusText.text =
                if (total == 0) "No local network found"
                else "Searching…  $done / $total   •   ${live.size} found"
        }
    }

    /** Opens the last-used phone as soon as it shows up, or the only phone found. */
    private fun maybeAutoOpen(finished: Boolean) {
        if (autoDone || userActed || cur != null) return
        val last = TvStore.lastPhone(this)
        val target: String = when {
            last != null && live.contains(last) -> last
            finished && live.size == 1 -> live.first()
            else -> null
        } ?: return
        val p = phones[target] ?: return
        autoDone = true
        openPhone(p)
    }

    // ------------------------------------------------------------ navigation

    private fun level(): Int = when {
        cur == null -> 0
        rootId < 0 -> 1
        else -> 2
    }

    private fun goBack() {
        when (level()) {
            0 -> finish()
            1 -> {
                userActed = true
                showPhones(cur?.id)
            }
            else -> {
                userActed = true
                if (rel.isEmpty()) {
                    if (roots.size <= 1) showPhones(cur?.id) else showRoots(rootId)
                } else {
                    openFolder(rootId, rel.substringBeforeLast('/', ""), focusKey = "$rootId/$rel")
                }
            }
        }
    }

    private fun showPhones(focusKey: String?, focus: Boolean = true) {
        loadJob?.cancel()
        val wasPhones = cur == null
        cur = null
        token = null
        rootId = -1
        rel = ""
        b.pathText.text = "Phones on your network"
        if (!scanning) b.progress.visibility = View.INVISIBLE

        val entries = phones.values.map { p ->
            val state = when {
                live.contains(p.id) -> "online"
                else -> "saved, not seen yet"
            }
            val paired = if (TvStore.token(this, p.id) != null) "   •   paired" else ""
            Entry(
                key = p.id,
                name = p.name,
                kind = Kind.PHONE,
                subtitle = "${p.host}   •   $state$paired"
            )
        }
        adapter.submit(entries, reset = !wasPhones)
        b.emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        b.emptyText.text = if (scanning) {
            "Looking for phones…"
        } else {
            "No phone found yet.\n\n1. Install TvBeam on your phone and open it\n2. Tap “Start sharing”\n3. Make sure the phone and this TV are on the same Wi-Fi\n\nThen press Rescan — or use Add phone to type its IP."
        }
        if (focus) focusList(focusKey)
    }

    private fun showRoots(focusRoot: Int?) {
        val p = cur ?: return
        loadJob?.cancel()
        rootId = -1
        rel = ""
        b.pathText.text = p.name
        val entries = roots.map { r ->
            Entry(
                key = "root${r.id}",
                name = r.name,
                kind = Kind.ROOT,
                subtitle = if (r.total > 0) "${fmtSize(r.free)} free of ${fmtSize(r.total)}" else "Storage",
                rootId = r.id
            )
        }
        adapter.submit(entries, reset = true)
        b.emptyText.visibility = View.GONE
        focusList(if (focusRoot != null) "root$focusRoot" else null)
    }

    private fun openPhone(p: PhoneInfo) {
        loadJob?.cancel()
        cur = p
        rootId = -1
        rel = ""
        b.pathText.text = p.name
        b.emptyText.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        b.statusText.text = "Connecting to ${p.name}…"
        adapter.submit(emptyList(), reset = true)

        loadJob = lifecycleScope.launch {
            var next: (() -> Unit)? = null
            try {
                val list = withToken(p) { tk ->
                    withContext(Dispatchers.IO) { Remote.roots(p, tk) }
                }
                roots = list
                TvStore.setLastPhone(this@TvActivity, p.id)
                b.statusText.text = p.name
                next = if (list.size == 1) {
                    { openFolder(list[0].id, "") }
                } else {
                    { showRoots(null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(p, e)
            } finally {
                if (!scanning) b.progress.visibility = View.INVISIBLE
            }
            next?.invoke()
        }
    }

    private fun openFolder(r: Int, path: String, focusKey: String? = null) {
        val p = cur ?: return
        loadJob?.cancel()
        rootId = r
        rel = path
        b.pathText.text = pretty()
        b.emptyText.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        adapter.submit(emptyList(), reset = true)

        loadJob = lifecycleScope.launch {
            try {
                val res = withToken(p) { tk ->
                    withContext(Dispatchers.IO) { loadFolder(p, tk, r, path) }
                }
                subs = res.subs
                adapter.submit(res.entries, reset = true)
                b.emptyText.visibility = View.GONE
                if (res.entries.isEmpty()) {
                    b.emptyText.text = "Nothing playable here.\nPress BACK to go up."
                    b.emptyText.visibility = View.VISIBLE
                }
                focusList(focusKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(p, e)
            } finally {
                if (!scanning) b.progress.visibility = View.INVISIBLE
            }
        }
    }

    private fun loadFolder(p: PhoneInfo, tk: String, r: Int, path: String): Loaded {
        val items = Remote.list(p, tk, r, path)
        val folders = ArrayList<Entry>()
        val media = ArrayList<Entry>()
        val subList = ArrayList<SubFile>()

        for (it in items) {
            val childRel = if (path.isEmpty()) it.name else "$path/${it.name}"
            when (it.kind) {
                "d" -> folders.add(
                    Entry("$r/$childRel", it.name, Kind.FOLDER, subtitle = "Folder", rootId = r, rel = childRel)
                )
                "v", "a" -> media.add(
                    Entry(
                        key = "$r/$childRel",
                        name = it.name,
                        kind = if (it.kind == "v") Kind.VIDEO else Kind.AUDIO,
                        size = it.size,
                        subtitle = fmtSize(it.size),
                        rootId = r,
                        rel = childRel,
                        thumb = Remote.thumbUrl(p, tk, r, childRel),
                        resumeKey = "${p.id}/$r/$childRel"
                    )
                )
                "s" -> subList.add(SubFile(it.name, Remote.fileUrl(p, tk, r, childRel)))
            }
        }
        folders.sortWith(Comparator { a, c -> naturalCompare(a.name, c.name) })
        media.sortWith(Comparator { a, c -> naturalCompare(a.name, c.name) })
        return Loaded(folders + media, subList)
    }

    /** Gets a token (pairing on the phone if needed), runs [block], re-pairs once on 401. */
    private suspend fun <T> withToken(p: PhoneInfo, block: suspend (String) -> T): T {
        var tk = TvStore.token(this, p.id) ?: pair(p)
        token = tk
        try {
            return block(tk)
        } catch (e: AuthException) {
            TvStore.clearToken(this, p.id)
            tk = pair(p)
            token = tk
            return block(tk)
        }
    }

    private suspend fun pair(p: PhoneInfo): String {
        val tvId = TvStore.tvId(this)
        val tvName = TvStore.tvName(this)
        for (attempt in 0 until 60) {
            val (status, tk) = withContext(Dispatchers.IO) { Remote.pair(p, tvId, tvName) }
            when (status) {
                "ok" -> if (tk != null) {
                    TvStore.setToken(this, p.id, tk)
                    return tk
                }
                "denied" -> throw DeniedException()
            }
            b.statusText.text = "Waiting for approval on ${p.name}…"
            b.emptyText.text = "Open TvBeam on ${p.name} and tap “Allow”."
            b.emptyText.visibility = View.VISIBLE
            delay(2000)
        }
        throw PairTimeoutException()
    }

    private fun focusList(key: String?) {
        b.list.post {
            if (adapter.itemCount == 0) {
                b.btnAdd.requestFocus()
                return@post
            }
            val pos = if (key != null) adapter.items.indexOfFirst { it.key == key }.coerceAtLeast(0) else 0
            (b.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 200)
            b.list.post { b.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    private fun pretty(): String {
        val p = cur ?: return ""
        val parts = ArrayList<String>()
        parts.add(p.name)
        roots.firstOrNull { it.id == rootId }?.let { if (roots.size > 1) parts.add(it.name) }
        if (rel.isNotEmpty()) parts.addAll(rel.split('/'))
        return parts.joinToString("  ›  ")
    }

    // ------------------------------------------------------------ clicks

    private fun onEntryClick(e: Entry) {
        userActed = true
        when (e.kind) {
            Kind.PHONE -> phones[e.key]?.let { openPhone(it) }
            Kind.ROOT -> openFolder(e.rootId, "")
            Kind.FOLDER -> openFolder(e.rootId, e.rel)
            Kind.VIDEO, Kind.AUDIO -> play(e)
        }
    }

    private fun onEntryLong(e: Entry): Boolean {
        if (e.kind != Kind.PHONE) return false
        AlertDialog.Builder(this)
            .setTitle("Forget ${e.name}?")
            .setMessage("Removes it from this TV. The phone will ask to pair again next time.")
            .setPositiveButton("Forget") { _, _ ->
                TvStore.forgetPhone(this, e.key)
                if (!live.contains(e.key)) phones.remove(e.key)
                showPhones(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    private fun play(e: Entry) {
        val p = cur ?: return
        val tk = token ?: return
        val playable = adapter.items.filter { it.kind == Kind.VIDEO || it.kind == Kind.AUDIO }
        val idx = playable.indexOfFirst { it.key == e.key }.coerceAtLeast(0)
        Playlist.items = playable.map {
            PItem(it.name, Remote.fileUrl(p, tk, it.rootId, it.rel), it.resumeKey ?: it.key)
        }
        Playlist.index = idx
        Playlist.subs = subs
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ------------------------------------------------------------ dialogs + errors

    private fun showAddPhone() {
        val input = EditText(this).apply {
            hint = "192.168.1.20   or   192.168.1.20:8765"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Add phone by IP")
            .setMessage("The IP is shown on the TvBeam screen of your phone.")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim().removePrefix("http://").trimEnd('/')
                val host = raw.substringBefore(':')
                val port = raw.substringAfter(':', "").toIntOrNull() ?: Proto.HTTP_PORT
                if (host.isNotEmpty()) addByIp(host, port)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addByIp(host: String, port: Int) {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { Remote.info(host, port, 3000) }
            if (p == null) {
                Toast.makeText(this@TvActivity, "No TvBeam found at $host:$port", Toast.LENGTH_LONG).show()
            } else {
                onPhone(p)
                openPhone(p)
            }
        }
    }

    private fun showError(p: PhoneInfo, e: Exception) {
        adapter.submit(emptyList(), reset = true)
        val msg = when (e) {
            is DeniedException ->
                "${p.name} denied this TV.\nOpen TvBeam on the phone and approve it, or turn on “Auto-allow new TVs”."
            is PairTimeoutException ->
                "No answer from ${p.name}.\nOpen TvBeam on the phone and tap “Allow”."
            is SocketTimeoutException, is ConnectException, is UnknownHostException ->
                "Can't reach ${p.name}.\nMake sure TvBeam is sharing on the phone and both are on the same Wi-Fi."
            else -> e.message ?: e.javaClass.simpleName
        }
        b.emptyText.text = "$msg\n\nPress BACK to go up."
        b.emptyText.visibility = View.VISIBLE
        b.statusText.text = ""
    }
}
