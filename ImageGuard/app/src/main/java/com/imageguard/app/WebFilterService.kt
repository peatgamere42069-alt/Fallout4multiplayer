package com.imageguard.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local "VPN" that only handles website lookups (DNS). Nothing else is routed through it.
 *
 *  - Search engines (Google, Bing, DuckDuckGo, YouTube) are forced into SafeSearch.
 *  - Adult, ad, tracker and malware domains are blocked using a bundled blocklist.
 *  - Everything else is looked up through a family-safe DNS service, which filters again.
 */
class WebFilterService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startFilter()
        return START_STICKY
    }

    @Synchronized
    private fun startFilter() {
        if (running) return
        running = true
        isRunning = true
        pool = Executors.newFixedThreadPool(8)
        reader = Thread({ run() }, "dns-filter").also { it.start() }
    }

    private fun run() {
        val rules = loadRules(this)
        val builder = Builder()
            .setSession(getString(R.string.vpn_session))
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
        // Apps that hard-code public DNS servers are pulled into the filter too. Their plain DNS
        // (port 53) is filtered; their encrypted DNS (DoH/DoT) is dropped so they fall back.
        HIJACKED_DNS.forEach { builder.addRoute(it, 32) }
        // ImageGuard's own lookups (resolving the SafeSearch hosts) bypass the filter.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Could not exclude self", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (fd == null) { // VPN permission was revoked
            running = false
            isRunning = false
            stopSelf()
            return
        }
        tun = fd

        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                break
            }
            if (n <= 0) continue
            val packet = Ipv4Udp.parse(buf, n) ?: continue
            if (packet.dstPort != 53) continue // e.g. encrypted DNS to 8.8.8.8: dropped
            pool?.execute { handle(packet, rules, output) }
        }
    }

    private fun handle(p: Ipv4Udp.Packet, rules: FilterRules, output: FileOutputStream) {
        val q = Dns.parseQuestion(p.payload) ?: return
        val response = when (val d = rules.decide(q.name)) {
            FilterRules.Decision.Block -> Dns.nxdomain(p.payload, q)
            is FilterRules.Decision.SafeSearch ->
                if (q.type == Dns.TYPE_A) {
                    val ips = safeSearchAddresses(d.host)
                    if (ips.isEmpty()) Dns.servfail(p.payload, q) else Dns.answerA(p.payload, q, ips)
                } else {
                    // No IPv6/HTTPS records, so browsers use the SafeSearch IPv4 address.
                    Dns.noRecords(p.payload, q)
                }
            FilterRules.Decision.Forward -> forward(p.payload) ?: Dns.servfail(p.payload, q)
        }
        val reply = Ipv4Udp.build(p.dstIp, p.srcIp, 53, p.srcPort, response)
        try {
            synchronized(output) { output.write(reply) }
        } catch (e: IOException) {
            // Tunnel closed.
        }
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (server in UPSTREAM_DNS) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 3000
                    val addr = InetAddress.getByName(server)
                    socket.send(DatagramPacket(query, query.size, addr, 53))
                    val resp = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(resp)
                    return resp.data.copyOf(resp.length)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Upstream $server failed: ${e.message}")
            }
        }
        return null
    }

    private fun safeSearchAddresses(host: String): List<ByteArray> {
        val now = System.currentTimeMillis()
        safeCache[host]?.let { (time, ips) -> if (now - time < 10 * 60_000) return ips }
        val ips = try {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().map { it.address }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { STATIC_SAFE[host] ?: emptyList() }
        if (ips.isNotEmpty()) safeCache[host] = now to ips
        return ips
    }

    private fun shutdown() {
        running = false
        isRunning = false
        try {
            tun?.close()
        } catch (e: IOException) {
        }
        tun = null
        reader?.interrupt()
        reader = null
        pool?.shutdownNow()
        pool = null
    }

    override fun onRevoke() {
        // The user switched the VPN off in Settings, or another VPN app took over.
        Store.get(this).webFilter = false
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ImageGuardDns"
        const val ACTION_STOP = "com.imageguard.app.STOP_WEB_FILTER"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"

        /** AdGuard Family DNS: blocks adult sites and ads, and enforces SafeSearch. */
        private val UPSTREAM_DNS = listOf("94.140.14.15", "94.140.15.16")

        private val HIJACKED_DNS = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15",
        )

        /** Google publishes a fixed address for forced SafeSearch; used if lookup fails. */
        private val STATIC_SAFE = mapOf(
            FilterRules.GOOGLE_SAFE to listOf(byteArrayOf(216.toByte(), 239.toByte(), 38, 120))
        )

        private val safeCache = ConcurrentHashMap<String, Pair<Long, List<ByteArray>>>()

        @Volatile var isRunning = false
            private set

        @Volatile private var cachedRules: FilterRules? = null

        private fun loadRules(context: Context): FilterRules {
            cachedRules?.let { return it }
            val blocked = try {
                context.assets.open("blocklist.txt").bufferedReader().useLines {
                    FilterRules.parseList(it)
                }
            } catch (e: IOException) {
                Log.w(TAG, "No bundled blocklist; relying on family DNS only")
                HashSet()
            }
            Log.i(TAG, "Loaded ${blocked.size} blocked domains")
            return FilterRules(blocked).also { cachedRules = it }
        }

        fun start(context: Context) {
            context.startService(Intent(context, WebFilterService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WebFilterService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
