package com.dante.tvbeam

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds phones running TvBeam, using every trick at once:
 *  1. mDNS / NSD   (_tvbeam._tcp)               – instant on most networks
 *  2. UDP broadcast probe on port 8766          – works when mDNS is filtered
 *  3. TCP sweep of the local /24 on port 8765   – works on hotspots & odd routers
 *  4. Last-known address of every saved phone
 * Every candidate is verified with GET /api/info before it reaches [onPhone].
 */
class Discovery(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onPhone: (PhoneInfo) -> Unit,
    private val onScan: (done: Int, total: Int) -> Unit
) {
    private val seen = ConcurrentHashMap<String, Long>()
    @Volatile private var found = false

    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private var udp: DatagramSocket? = null
    private var udpJob: Job? = null
    private var scanJob: Job? = null
    private var autoScanJob: Job? = null

    fun start(scanNow: Boolean = false) {
        stop()
        seen.clear()
        found = false
        startNsd()
        udpJob = scope.launch(Dispatchers.IO) { udpLoop() }
        for (p in TvStore.phones(ctx).values) report(p.host, p.port)
        autoScanJob = scope.launch {
            if (!scanNow) delay(3500)
            if (scanNow || !found) scan()
        }
    }

    fun stop() {
        autoScanJob?.cancel()
        scanJob?.cancel()
        udpJob?.cancel()
        try { udp?.close() } catch (e: Exception) { }
        udp = null
        stopNsd()
    }

    // ------------------------------------------------------------ verification

    /** Verifies host:port is a TvBeam phone and reports it on the main thread. */
    fun report(host: String, port: Int) {
        val key = "$host:$port"
        val now = System.currentTimeMillis()
        val prev = seen[key]
        if (prev != null && now - prev < 4000) return
        seen[key] = now
        scope.launch(Dispatchers.IO) {
            val p = Remote.info(host, port)
            if (p == null) {
                seen.remove(key)
                return@launch
            }
            found = true
            withContext(Dispatchers.Main) { onPhone(p) }
        }
    }

    // ------------------------------------------------------------ 1. NSD

    @Suppress("DEPRECATION")
    private fun startNsd() {
        try {
            val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    val s = serviceInfo ?: return
                    if (s.serviceType?.contains("tvbeam") != true) return
                    synchronized(lock) { queue.addLast(s) }
                    resolveNext()
                }
            }
            nsd = m
            nsdListener = l
            m.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            // other methods still work
        }
    }

    private fun stopNsd() {
        try { nsdListener?.let { nsd?.stopServiceDiscovery(it) } } catch (e: Exception) { }
        nsdListener = null
        nsd = null
        synchronized(lock) {
            queue.clear()
            resolving = false
        }
    }

    // NSD can only resolve one service at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        val next: NsdServiceInfo? = synchronized(lock) {
            if (resolving) null else queue.removeFirstOrNull()?.also { resolving = true }
        }
        if (next == null) return
        try {
            m.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    resolveDone()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    val host = serviceInfo?.host
                    if (serviceInfo != null && host is Inet4Address) {
                        host.hostAddress?.let { report(it, serviceInfo.port) }
                    }
                    resolveDone()
                }
            })
        } catch (e: Exception) {
            resolveDone()
        }
    }

    private fun resolveDone() {
        synchronized(lock) { resolving = false }
        resolveNext()
    }

    // ------------------------------------------------------------ 2. UDP broadcast

    private fun broadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try { out.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) { }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return out.toList()
    }

    private suspend fun udpLoop() {
        val sock = try {
            DatagramSocket().apply { broadcast = true; soTimeout = 600 }
        } catch (e: Exception) {
            return
        }
        udp = sock
        val cc = currentCoroutineContext()
        try {
            val data = Proto.MAGIC.toByteArray()
            val buf = ByteArray(512)
            while (cc.isActive) {
                for (addr in broadcastAddresses()) {
                    try {
                        sock.send(DatagramPacket(data, data.size, addr, Proto.UDP_PORT))
                    } catch (e: Exception) {
                        // this interface can't broadcast
                    }
                }
                val until = System.currentTimeMillis() + 3000
                while (cc.isActive && System.currentTimeMillis() < until) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val o = JSONObject(String(p.data, 0, p.length))
                        if (o.optString("app") == Proto.APP) {
                            p.address?.hostAddress?.let { report(it, o.optInt("port", Proto.HTTP_PORT)) }
                        }
                    } catch (e: SocketTimeoutException) {
                        // keep listening
                    } catch (e: Exception) {
                        if (sock.isClosed) return
                    }
                }
            }
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ 3. subnet sweep

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun toIp(i: Int): String =
        "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    private fun candidates(): List<String> {
        val own = HashSet<String>()
        val out = LinkedHashSet<String>()
        val nis = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) {
            null
        } ?: emptyList()

        for (ni in nis) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isPointToPoint) continue
            } catch (e: Exception) {
                continue
            }
            for (ia in ni.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress) continue
                val ip = toInt(a.address)
                own.add(toIp(ip))
                var prefix = ia.networkPrefixLength.toInt()
                if (prefix < 22) prefix = 24
                if (prefix > 30) continue
                val size = 1 shl (32 - prefix)
                val net = ip and (-1 shl (32 - prefix))
                for (h in 1 until size - 1) out.add(toIp(net + h))
            }
        }
        out.removeAll(own)
        return out.toList()
    }

    private fun probe(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 600); true }
    } catch (e: Exception) {
        false
    }

    /** Full TCP sweep of the local subnet(s) for the TvBeam port. */
    fun scan() {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            val ips = candidates()
            withContext(Dispatchers.Main) { onScan(0, ips.size) }
            val done = AtomicInteger(0)
            val gate = Semaphore(64)
            kotlinx.coroutines.coroutineScope {
                for (ip in ips) {
                    launch {
                        gate.withPermit {
                            if (probe(ip, Proto.HTTP_PORT)) report(ip, Proto.HTTP_PORT)
                        }
                        val d = done.incrementAndGet()
                        if (d % 16 == 0 || d == ips.size) {
                            withContext(Dispatchers.Main) { onScan(d, ips.size) }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { onScan(ips.size, -1) }
        }
    }
}
