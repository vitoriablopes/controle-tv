package br.vitoria.controletv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/**
 * Procura TVs Android/Google TV na rede Wi-Fi (elas se anunciam como
 * "_androidtvremote2._tcp"). Os resultados chegam na thread principal.
 */
class TvDiscovery(
    context: Context,
    private val onChange: (List<FoundTv>) -> Unit,
) {
    data class FoundTv(val name: String, val host: String, val port: Int)

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundTv>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    val isRunning get() = listener != null

    fun start() {
        if (listener != null) return
        multicastLock = runCatching {
            (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("controletv-busca").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    if (listener == null) return@post
                    queue.addLast(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) onChange(found.values.toList())
                }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        queue.clear()
        runCatching { nsd.stopServiceDiscovery(l) }
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            queue.addLast(info)
                            main.postDelayed({ resolveNext() }, 300)
                        } else {
                            resolveNext()
                        }
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val address = serviceInfo.host
                    main.post {
                        resolving = false
                        if (address != null && listener != null) {
                            val host = address.hostAddress ?: ""
                            val existing = found[serviceInfo.serviceName]
                            // prefere IPv4, que é o mais confiável na rede de casa
                            if (existing == null || address is Inet4Address) {
                                found[serviceInfo.serviceName] =
                                    FoundTv(serviceInfo.serviceName, host, serviceInfo.port.takeIf { it > 0 } ?: 6466)
                                onChange(found.values.toList())
                            }
                        }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
            main.post { resolveNext() }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"

        /** Procura uma TV específica pelo nome (usado quando o IP dela muda). */
        suspend fun findByName(context: Context, name: String, timeoutMs: Long): FoundTv? {
            val result = CompletableDeferred<FoundTv>()
            var discovery: TvDiscovery? = null
            withContext(Dispatchers.Main) {
                discovery = TvDiscovery(context) { list ->
                    list.firstOrNull { it.name == name }?.let { result.complete(it) }
                }.also { it.start() }
            }
            return try {
                withTimeoutOrNull(timeoutMs) { result.await() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { discovery?.stop() }
            }
        }
    }
}
