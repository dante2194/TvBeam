package com.dante.tvbeam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.concurrent.thread

/**
 * Keeps the phone discoverable and serving while the screen is off:
 *  • HTTP file server
 *  • mDNS/NSD advertisement  (_tvbeam._tcp)
 *  • UDP broadcast responder (fallback for networks where mDNS is blocked)
 */
class ShareService : Service() {

    companion object {
        const val ACTION_STOP = "com.dante.tvbeam.STOP"
        private const val CH_SHARE = "share"
        private const val CH_REQ = "requests"
        private const val ID_SHARE = 1
        private const val ID_REQ = 2

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, ShareService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ShareService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: BeamServer? = null
    private var udp: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) startAll()
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ start / stop

    private fun startAll() {
        createChannels()
        goForeground(buildNotif("Starting…"))

        val s = BeamServer(applicationContext)
        val port = try {
            s.start()
        } catch (e: Exception) {
            stopSelf()
            return
        }
        server = s
        Share.port = port
        acquireLocks()
        registerNsd(s)
        startUdp(s)
        Share.running.value = true
        updateNotif()

        scope.launch { Share.streams.collect { updateNotif() } }
        scope.launch { Share.pending.collect { notifyPending(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        try { reg?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }
        try { udp?.close() } catch (e: Exception) { }
        server?.stop()
        server = null
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { mcLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        Share.running.value = false
        Share.streams.value = 0
        try { getSystemService(NotificationManager::class.java).cancel(ID_REQ) } catch (e: Exception) { }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvbeam:wifi")
            ?.apply { setReferenceCounted(false); acquire() }
        mcLock = wm?.createMulticastLock("tvbeam:mc")
            ?.apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvbeam:cpu")
            .apply { setReferenceCounted(false); acquire() }
    }

    // ------------------------------------------------------------ discovery

    private fun registerNsd(s: BeamServer) {
        try {
            val m = getSystemService(Context.NSD_SERVICE) as NsdManager
            val id = PhoneStore.deviceId(this)
            val info = NsdServiceInfo()
            info.setServiceName("TvBeam-" + id.take(6))
            info.setServiceType(Proto.SERVICE_TYPE)
            info.setPort(s.port)
            info.setAttribute("id", id)
            info.setAttribute("name", PhoneStore.name(this))
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(i: NsdServiceInfo?, e: Int) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo?, e: Int) {}
                override fun onServiceRegistered(i: NsdServiceInfo?) {}
                override fun onServiceUnregistered(i: NsdServiceInfo?) {}
            }
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            reg = l
        } catch (e: Exception) {
            // UDP + subnet scan still work
        }
    }

    private fun startUdp(s: BeamServer) {
        val sock = DatagramSocket(null as SocketAddress?)
        udp = sock
        thread(isDaemon = true, name = "beam-udp") {
            try {
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(Proto.UDP_PORT))
                val buf = ByteArray(512)
                while (!sock.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (msg.startsWith(Proto.MAGIC)) {
                        val r = s.info().toString().toByteArray()
                        sock.send(DatagramPacket(r, r.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) {
                // closed or port busy
            } finally {
                try { sock.close() } catch (e: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SHARE, "Sharing status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REQ, "TV requests", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, PhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_SHARE)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle("TvBeam is sharing")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SHARE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_SHARE, n)
        }
    }

    private fun updateNotif() {
        val ip = NetUtil.localIp() ?: "no Wi-Fi"
        val state = if (Share.streams.value > 0) "Streaming to your TV" else "Waiting for your TV"
        try {
            getSystemService(NotificationManager::class.java)
                .notify(ID_SHARE, buildNotif("$state  •  $ip:${Share.port}"))
        } catch (e: Exception) { }
    }

    private fun notifyPending(list: List<Pending>) {
        val nm = getSystemService(NotificationManager::class.java)
        val p = list.lastOrNull()
        try {
            if (p == null) {
                nm.cancel(ID_REQ)
            } else {
                val n = NotificationCompat.Builder(this, CH_REQ)
                    .setSmallIcon(R.drawable.ic_stat_beam)
                    .setContentTitle("“${p.name}” wants to connect")
                    .setContentText("Tap to allow or deny")
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                nm.notify(ID_REQ, n)
            }
        } catch (e: Exception) { }
    }
}
