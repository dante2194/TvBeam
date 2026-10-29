package com.dante.tvbeam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dante.tvbeam.databinding.ActivityPhoneBinding
import kotlinx.coroutines.launch

/** The phone app: one switch to share this phone's folders with any TvBeam TV on the Wi-Fi. */
class PhoneActivity : AppCompatActivity() {

    private lateinit var b: ActivityPhoneBinding
    private var dialog: AlertDialog? = null
    private var shownPendingId: String? = null
    private var wantStart = false

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val readPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it && wantStart) startSharing()
            refreshAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhoneBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnToggle.setOnClickListener {
            if (Share.running.value) {
                wantStart = false
                ShareService.stop(this)
            } else {
                startSharing()
            }
        }
        b.btnPerm.setOnClickListener { requestStorage() }
        b.deviceName.setOnClickListener { showRename() }
        b.btnOpenTv.setOnClickListener { startActivity(Intent(this, TvActivity::class.java)) }

        b.swAutoAllow.isChecked = PhoneStore.autoAllow(this)
        b.swAutoAllow.setOnCheckedChangeListener { _, v -> PhoneStore.setAutoAllow(this, v) }
        b.swAutoStart.isChecked = PhoneStore.autoStart(this)
        b.swAutoStart.setOnCheckedChangeListener { _, v -> PhoneStore.setAutoStart(this, v) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Share.running.collect { renderStatus() } }
                launch { Share.streams.collect { renderStatus() } }
                launch { Share.trustedTick.collect { renderTvs() } }
                launch { Share.pending.collect { showPending(it) } }
            }
        }

        if (savedInstanceState == null && PhoneStore.autoStart(this) &&
            hasStorage() && !Share.running.value
        ) {
            startSharing()
        }
    }

    override fun onResume() {
        super.onResume()
        if (wantStart && hasStorage() && !Share.running.value) startSharing()
        refreshAll()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    // ------------------------------------------------------------ permissions + start

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            readPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun startSharing() {
        if (!hasStorage()) {
            wantStart = true
            requestStorage()
            return
        }
        wantStart = false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ShareService.start(this)
    }

    // ------------------------------------------------------------ rendering

    private fun refreshAll() {
        renderStatus()
        renderTvs()
    }

    private fun renderStatus() {
        if (!::b.isInitialized) return
        val running = Share.running.value
        val streaming = Share.streams.value > 0

        b.deviceName.text = PhoneStore.name(this)
        b.btnToggle.text = if (running) "Stop sharing" else "Start sharing"
        b.addressText.text =
            if (running) "${NetUtil.localIp() ?: "no Wi-Fi"}:${Share.port}" else "Not sharing"
        b.stateText.text = when {
            !running -> "Your TV can't see this phone right now."
            streaming -> "Streaming to your TV…"
            else -> "Ready — open TvBeam on your TV and it will find this phone."
        }
        b.stateText.setTextColor(
            if (running) Color.parseColor("#5CE1A8") else ContextCompat.getColor(this, R.color.text_secondary)
        )

        val ok = hasStorage()
        b.permCard.visibility = if (ok) View.GONE else View.VISIBLE
        b.rootsBox.removeAllViews()
        if (ok) {
            for (r in FileApi.roots(this)) {
                addRow(b.rootsBox, R.drawable.ic_folder, 0xFFFFC857.toInt(), r.name, r.dir.path)
            }
        } else {
            addText(b.rootsBox, "Grant file access to share folders.")
        }
    }

    private fun renderTvs() {
        if (!::b.isInitialized) return
        b.tvBox.removeAllViews()
        val list = PhoneStore.trustedList(this)
        if (list.isEmpty()) {
            addText(b.tvBox, "No TV paired yet. It happens automatically the first time a TV connects.")
        }
        for (t in list) {
            addRow(b.tvBox, R.drawable.ic_tv, 0xFF5CE1E6.toInt(), t.name, "Paired", "Remove") {
                PhoneStore.removeTrusted(this, t.id)
                Share.trustedTick.value = Share.trustedTick.value + 1
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addText(box: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        t.textSize = 14f
        t.setPadding(dp(4), dp(4), dp(4), dp(8))
        box.addView(t)
    }

    private fun addRow(
        box: LinearLayout, icon: Int, tint: Int, title: String, sub: String,
        action: String? = null, onAction: (() -> Unit)? = null
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = ContextCompat.getDrawable(this, R.drawable.bg_card)
        val rlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        rlp.bottomMargin = dp(8)
        row.layoutParams = rlp

        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.imageTintList = ColorStateList.valueOf(tint)
        iv.layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        row.addView(iv)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val clp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        clp.marginStart = dp(14)
        col.layoutParams = clp

        val t1 = TextView(this)
        t1.text = title
        t1.setTextColor(Color.WHITE)
        t1.textSize = 16f
        t1.maxLines = 1
        col.addView(t1)

        val t2 = TextView(this)
        t2.text = sub
        t2.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        t2.textSize = 12f
        t2.maxLines = 1
        col.addView(t2)
        row.addView(col)

        if (action != null && onAction != null) {
            val btn = TextView(this)
            btn.text = action
            btn.setTextColor(ContextCompat.getColor(this, R.color.accent))
            btn.textSize = 14f
            btn.setPadding(dp(12), dp(8), dp(4), dp(8))
            btn.setOnClickListener { onAction() }
            row.addView(btn)
        }
        box.addView(row)
    }

    // ------------------------------------------------------------ dialogs

    private fun showPending(list: List<Pending>) {
        val p = list.firstOrNull { System.currentTimeMillis() - it.at < 20_000 }
        if (p == null) {
            dialog?.dismiss()
            dialog = null
            shownPendingId = null
            return
        }
        if (dialog?.isShowing == true && shownPendingId == p.id) return
        dialog?.dismiss()
        shownPendingId = p.id
        dialog = AlertDialog.Builder(this)
            .setTitle("Allow this TV?")
            .setMessage("“${p.name}” wants to browse and play files from this phone.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ -> Share.allow(this, p.id) }
            .setNegativeButton("Deny") { _, _ -> Share.deny(p.id) }
            .show()
    }

    private fun showRename() {
        val input = EditText(this)
        input.setText(PhoneStore.name(this))
        input.setSingleLine()
        input.setSelectAllOnFocus(true)
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Name shown on your TV")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) PhoneStore.setName(this, n)
                renderStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
