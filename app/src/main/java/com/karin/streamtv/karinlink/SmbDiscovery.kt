package com.karin.streamtv.karinlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * Descubrimiento automático de servidores de archivos en la red local:
 * PCs con Windows y NAS (Synology, QNAP, TrueNAS…).
 *
 * Combina dos técnicas porque ninguna cubre todo:
 *  - mDNS `_smb._tcp`: lo anuncian casi todos los NAS, macOS y Linux con
 *    Samba. Rápido y con nombre. Los Windows no lo anuncian por defecto.
 *  - Barrido TCP al puerto 445 de la /24 local: encuentra Windows y
 *    cualquier NAS con SMB activo aunque no anuncie nada.
 *
 * Sin bloqueo por tipo de red a propósito: las Smart TV por cable también
 * tienen LAN y deben poder escanear.
 */
object SmbDiscovery {

    private const val TAG = "SmbDiscovery"

    /** Tipo mDNS que anuncian los NAS para SMB. */
    const val SERVICE_TYPE = "_smb._tcp."
    const val SMB_PORT = 445

    private const val MDNS_WINDOW_MS = 4500L
    private const val TCP_TIMEOUT_MS = 350
    private const val MAX_PARALLEL = 64
    private const val MAX_TARGETS = 1024
    private const val MAX_MDNS_RESOLVES = 48

    enum class Kind { MDNS, SCAN }

    data class Server(
        val host: String,
        val port: Int = SMB_PORT,
        val name: String,
        val kind: Kind
    )

    data class DiscoveryResult(
        /** Servidores únicos por IP (mDNS primero). */
        val servers: List<Server>,
        /** Subredes barridas, ej. "192.168.1.0/24". Vacío = sin red local. */
        val subnets: List<String>
    )

    /**
     * Escanea la LAN. Corre en IO por su cuenta; cancelable (al salir de la
     * pantalla el scope llamante lo cancela y se sueltan NSD y sockets).
     */
    suspend fun discover(context: Context): DiscoveryResult = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mcastLock = try {
            wifi?.createMulticastLock("karinflix-smb-discovery")?.apply {
                setReferenceCounted(true)
            }
        } catch (_: Exception) {
            null
        }
        try {
            mcastLock?.acquire()
        } catch (_: Exception) {
        }
        try {
            val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val mdns = async { discoverMdns(nsd) }
            val sweepJob = async { sweepLan() }
            val mdnsHits = try { mdns.await() } catch (_: Exception) { emptyList() }
            val sweep = try {
                sweepJob.await()
            } catch (_: Exception) {
                Sweep(emptyList(), emptyList())
            }
            merge(mdnsHits, sweep)
        } finally {
            runCatching { if (mcastLock?.isHeld == true) mcastLock.release() }
        }
    }

    // ---------- mDNS ----------

    private suspend fun discoverMdns(manager: NsdManager?): List<Server> {
        if (manager == null) return emptyList()
        val found = Collections.synchronizedMap(LinkedHashMap<String, NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val key = info.serviceName ?: return
                synchronized(found) { if (!found.containsKey(key)) found[key] = info }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices: ${t.message}")
            return emptyList()
        }
        try {
            delay(MDNS_WINDOW_MS)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        // Android solo permite una resolución en vuelo: en serie.
        val snapshot: List<NsdServiceInfo> =
            synchronized(found) { found.values.toList().take(MAX_MDNS_RESOLVES) }
        val out = mutableListOf<Server>()
        for (info in snapshot) {
            val resolved = resolveOne(manager, info) ?: continue
            val ip = resolved.host?.hostAddress ?: continue
            val port = if (resolved.port > 0) resolved.port else SMB_PORT
            val rawName = resolved.serviceName ?: info.serviceName ?: ip
            val name = rawName.substringBefore("._smb").ifBlank { ip }
            out.add(Server(host = ip, port = port, name = name, kind = Kind.MDNS))
        }
        return out
    }

    private suspend fun resolveOne(
        manager: NsdManager,
        info: NsdServiceInfo,
        timeoutMs: Long = 2500L
    ): NsdServiceInfo? = withTimeoutOrNull(timeoutMs) {
        val done = CompletableDeferred<NsdServiceInfo?>()
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                done.complete(resolved)
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                done.complete(null)
            }
        }
        try {
            manager.resolveService(info, listener)
        } catch (t: Throwable) {
            Log.w(TAG, "resolveService: ${t.message}")
            done.complete(null)
        }
        done.await()
    }

    // ---------- Barrido 445 ----------

    private data class Sweep(val hits: List<String>, val subnets: List<String>)

    private suspend fun sweepLan(): Sweep = supervisorScope {
        val targets = LinkedHashSet<String>()
        val subnets = LinkedHashSet<String>()
        try {
            val ifs = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in ifs) {
                try {
                    if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                    for (ifAddr in nif.interfaceAddresses) {
                        val ip = ifAddr.address as? Inet4Address ?: continue
                        if (ip.isLoopbackAddress || ip.isMulticastAddress) continue
                        val b = ip.address
                        val base = "${b[0].toUByte()}.${b[1].toUByte()}.${b[2].toUByte()}"
                        subnets.add("$base.0/24")
                        val ownLast = b[3].toUByte().toInt()
                        for (i in 1..254) {
                            if (i == ownLast) continue
                            targets.add("$base.$i")
                            if (targets.size >= MAX_TARGETS) break
                        }
                        if (targets.size >= MAX_TARGETS) break
                    }
                } catch (_: Exception) {
                }
                if (targets.size >= MAX_TARGETS) break
            }
        } catch (t: Exception) {
            Log.w(TAG, "interfaces: ${t.message}")
        }

        val hits = Collections.synchronizedList(mutableListOf<String>())
        val sem = Semaphore(MAX_PARALLEL)
        for (ip in targets) {
            launch(Dispatchers.IO) {
                sem.withPermit {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, SMB_PORT), TCP_TIMEOUT_MS)
                            hits.add(ip)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        // supervisorScope espera a todos los hijos.
        Sweep(hits.sortedBy(::ipToLong), subnets.toList())
    }

    private fun ipToLong(ip: String): Long {
        var acc = 0L
        for (part in ip.split('.')) {
            acc = acc * 256 + (part.toIntOrNull()?.and(0xFF) ?: 0)
        }
        return acc
    }

    // ---------- Fusión ----------

    private suspend fun merge(
        mdnsHits: List<Server>,
        sweep: Sweep
    ): DiscoveryResult {
        val byIp = LinkedHashMap<String, Server>()
        for (s in mdnsHits) byIp[s.host] = s
        val pending = sweep.hits.filterNot { byIp.containsKey(it) }
        // Resolución inversa en paralelo (corta): nombre bonito si hay DNS
        // local; si no, la IP.
        val named: List<Pair<String, String?>> = supervisorScope {
            pending.map { ip ->
                async(Dispatchers.IO) { ip to reverseName(ip) }
            }.awaitAll()
        }
        for ((ip, name) in named) {
            byIp[ip] = Server(host = ip, port = SMB_PORT, name = name ?: ip, kind = Kind.SCAN)
        }
        val sorted = byIp.values.sortedWith(
            compareBy({ it.kind != Kind.MDNS }, { ipToLong(it.host) })
        )
        return DiscoveryResult(servers = sorted, subnets = sweep.subnets)
    }

    private suspend fun reverseName(ip: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(700L) {
            try {
                val h = InetAddress.getByName(ip).hostName
                if (h != null && h != ip) h.substringBefore('.').takeIf { it.isNotBlank() }
                else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
