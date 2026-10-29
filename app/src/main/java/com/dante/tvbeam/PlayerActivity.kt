package com.dante.tvbeam

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.dante.tvbeam.databinding.ActivityPlayerBinding
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var startIndex = 0
    private var startPos = 0L

    private var resizeIdx = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeNames = arrayOf("Fit", "Zoom (crop)", "Stretch")
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private val hideSeek = Runnable { b.seekOverlay.visibility = View.GONE }
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5000)
        }
    }

    private val handledDpad = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Playlist.items.isEmpty()) {
            finish()
            return
        }
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        startIndex = Playlist.index.coerceIn(0, Playlist.items.size - 1)
        startPos = TvStore.resume(this, Playlist.items[startIndex].key)?.first ?: 0L
        if (startPos > 0) {
            Toast.makeText(this, "Resuming from ${fmtTime(startPos)}", Toast.LENGTH_SHORT).show()
        }

        b.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { v ->
                b.topBar.visibility = if (v == View.VISIBLE) View.VISIBLE else View.GONE
            }
        )
        b.playerView.subtitleView?.apply {
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
                )
            )
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
        }

        b.btnAudio.setOnClickListener { showTracks(C.TRACK_TYPE_AUDIO, "Audio track", false) }
        b.btnSubs.setOnClickListener { showTracks(C.TRACK_TYPE_TEXT, "Subtitles", true) }
        b.btnSpeed.setOnClickListener { showSpeed() }
        b.btnAspect.setOnClickListener { cycleAspect() }
        b.btnNext.setOnClickListener { player?.seekToNextMediaItem() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.playerView.isControllerFullyVisible) b.playerView.hideController() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (::b.isInitialized) initPlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    // ------------------------------------------------------------ player setup

    private fun initPlayer() {
        if (player != null) return

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(false)
        val dsFactory = DefaultDataSource.Factory(this, http)
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractors)
            .setDataSourceFactory(dsFactory)

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 90_000, 2_000, 5_000)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = showError(error)

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = p.currentMediaItemIndex + 1
                b.titleText.text = "${item?.mediaMetadata?.title ?: ""}   ($idx/${Playlist.items.size})"
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    Playlist.items.lastOrNull()?.let { TvStore.clearResume(this@PlayerActivity, it.key) }
                    finish()
                }
            }
        })

        p.setMediaItems(Playlist.items.map { buildItem(it) }, startIndex, startPos)
        p.prepare()
        p.playWhenReady = true

        player = p
        b.playerView.player = p
        handler.postDelayed(saver, 5000)
    }

    private fun releasePlayer() {
        val p = player ?: return
        saveProgress()
        startIndex = p.currentMediaItemIndex
        startPos = p.currentPosition
        handler.removeCallbacks(saver)
        b.playerView.player = null
        p.release()
        player = null
    }

    private fun buildItem(it: PItem): MediaItem {
        val base = it.name.substringBeforeLast('.').lowercase()
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        for (s in Playlist.subs) {
            val sName = s.name.substringBeforeLast('.')
            val sb = sName.lowercase()
            val matches = sb == base ||
                (sb.startsWith(base) && sb.length > base.length && sb[base.length] in ". _-")
            if (!matches) continue
            val mime = when (s.name.substringAfterLast('.').lowercase()) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "vtt" -> MimeTypes.TEXT_VTT
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                else -> null
            } ?: continue
            val tag = sName.drop(base.length).trim('.', ' ', '_', '-')
            subs.add(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                    .setMimeType(mime)
                    .setLanguage(if (tag.length in 2..3) tag else null)
                    .setLabel(if (tag.isEmpty()) "External" else tag)
                    .setSelectionFlags(if (subs.isEmpty()) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            )
        }
        return MediaItem.Builder()
            .setUri(Uri.parse(it.url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build())
            .setSubtitleConfigurations(subs)
            .build()
    }

    // ------------------------------------------------------------ remote control

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(e)
        val ctrl = b.playerView.isControllerFullyVisible

        if (e.action == KeyEvent.ACTION_DOWN) {
            if (ctrl) b.playerView.showController() // keep the overlay alive while navigating it
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!ctrl) {
                    val step = when {
                        e.repeatCount < 8 -> 10_000L
                        e.repeatCount < 20 -> 30_000L
                        else -> 60_000L
                    }
                    seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!ctrl) {
                    if (e.repeatCount == 0) togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!ctrl) {
                    b.playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (e.repeatCount == 0) togglePlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000L); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000L); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    p.seekToNextMediaItem(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    p.seekToPreviousMediaItem(); return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
            }
        } else if (!ctrl && e.keyCode in handledDpad) {
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        showSeekText(if (p.isPlaying) "▶  Play" else "❚❚  Pause")
        b.playerView.showController()
    }

    private fun seekBy(delta: Long) {
        val p = player ?: return
        val dur = p.duration
        var t = p.currentPosition + delta
        t = if (dur != C.TIME_UNSET) t.coerceIn(0L, dur) else maxOf(0L, t)
        p.seekTo(t)
        showSeekText("${if (delta > 0) "▶▶" else "◀◀"}  ${fmtTime(t)}")
    }

    private fun showSeekText(text: String) {
        b.seekOverlay.text = text
        b.seekOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeek)
        handler.postDelayed(hideSeek, 900)
    }

    // ------------------------------------------------------------ menus

    private fun cycleAspect() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        b.playerView.resizeMode = resizeModes[resizeIdx]
        showSeekText("Aspect: ${resizeNames[resizeIdx]}")
    }

    private fun showSpeed() {
        val p = player ?: return
        val labels = speeds.map { if (it == 1f) "Normal (1x)" else "${it}x" }.toTypedArray()
        val cur = speeds.indexOfFirst { it == p.playbackParameters.speed }.coerceAtLeast(2)
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, cur) { d, which ->
                p.setPlaybackSpeed(speeds[which])
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private class Opt(val label: String, val group: androidx.media3.common.Tracks.Group?, val idx: Int)

    private fun showTracks(type: Int, title: String, allowOff: Boolean) {
        val p = player ?: return
        val opts = ArrayList<Opt>()
        if (allowOff) opts.add(Opt("Off", null, -1))
        var checked = if (allowOff) 0 else -1

        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                if (g.isTrackSelected(i)) checked = opts.size
                opts.add(Opt(trackLabel(g.getTrackFormat(i), opts.size), g, i))
            }
        }
        if (allowOff && p.trackSelectionParameters.disabledTrackTypes.contains(type)) checked = 0

        if (opts.isEmpty() || (allowOff && opts.size == 1)) {
            Toast.makeText(this, "No $title available", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), checked) { d, which ->
                val o = opts[which]
                val pb = p.trackSelectionParameters.buildUpon()
                val g = o.group
                if (g == null) {
                    pb.setTrackTypeDisabled(type, true)
                } else {
                    pb.setTrackTypeDisabled(type, false)
                    pb.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.idx))
                }
                p.trackSelectionParameters = pb.build()
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun trackLabel(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        f.label?.let { parts.add(it) }
        f.language?.takeIf { it != "und" }?.let {
            val d = Locale.forLanguageTag(it).displayLanguage
            if (d.isNotEmpty() && !parts.contains(d)) parts.add(d)
        }
        if (MimeTypes.isAudio(f.sampleMimeType)) {
            codecName(f.sampleMimeType)?.let { parts.add(it) }
            channelName(f.channelCount)?.let { parts.add(it) }
        }
        return if (parts.isEmpty()) "Track ${n + 1}" else parts.joinToString("  •  ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        null -> null
        else -> mime.substringAfter('/').uppercase()
    }

    private fun channelName(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "Mono"
        n == 2 -> "Stereo"
        n == 6 -> "5.1"
        n == 8 -> "7.1"
        else -> "${n}ch"
    }

    // ------------------------------------------------------------ progress + errors

    private fun saveProgress() {
        val p = player ?: return
        val item = Playlist.items.getOrNull(p.currentMediaItemIndex) ?: return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = p.currentPosition
        if (pos > dur * 0.95 || pos < 15_000) TvStore.clearResume(this, item.key)
        else TvStore.saveResume(this, item.key, pos, dur)
    }

    private fun showError(e: PlaybackException) {
        if (isFinishing) return
        val msg = when (e.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "This TV can't decode this file's video/audio format."
            else -> e.cause?.message ?: e.message ?: "Unknown error"
        }
        AlertDialog.Builder(this)
            .setTitle("Can't play this file")
            .setMessage("$msg\n\n(${e.errorCodeName})")
            .setPositiveButton("Retry") { _, _ -> player?.prepare() }
            .setNeutralButton("Next") { _, _ ->
                player?.seekToNextMediaItem()
                player?.prepare()
            }
            .setNegativeButton("Back") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
