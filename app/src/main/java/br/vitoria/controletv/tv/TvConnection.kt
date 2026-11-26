package br.vitoria.controletv.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import br.vitoria.controletv.core.RemoteClient
import br.vitoria.controletv.data.SavedTv
import br.vitoria.controletv.data.Store
import br.vitoria.controletv.data.Streamings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Mantém a conexão com a TV pareada. Enquanto existir uma TV salva, tenta
 * ficar conectado: se a conexão cair (TV em espera, Wi-Fi oscilou, IP mudou),
 * reconecta sozinho. Só para de vez quando a pessoa "esquece" a TV.
 *
 * Toda a rede roda fora da thread principal, então a tela nunca trava.
 */
object TvConnection {
    enum class Status { NO_TV, CONNECTING, CONNECTED, OFFLINE, NEEDS_PAIRING }

    data class State(
        val status: Status = Status.NO_TV,
        val tvName: String? = null,
        val host: String? = null,
        val isOn: Boolean? = null,
        val volume: Int? = null,
        val volumeMax: Int? = null,
        val muted: Boolean = false,
    )

    private const val TAG = "TvConnection"

    /** Depois de sair do app, a conexão fica aberta mais um tempo antes de descansar. */
    private const val BACKGROUND_GRACE_MS = 5 * 60_000L
    private const val READY_TIMEOUT_MS = 8_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile private var client: RemoteClient? = null
    private var loopJob: Job? = null
    private var idleJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        val tv = Store.tv(app)
        _state.value = if (tv == null) State() else State(Status.CONNECTING, tv.name, tv.host)
        watchNetwork()
    }

    // ------------------------------------------------------------ ciclo de vida

    /** App apareceu na tela: conecta (ou reconecta) na hora. */
    fun onAppVisible() {
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = null
        }
        ensureRunning()
        kick.trySend(Unit)
    }

    /** App saiu da tela: depois de alguns minutos a conexão descansa (a TV continua pareada). */
    fun onAppHidden() {
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(BACKGROUND_GRACE_MS)
                stopLoop()
                _state.update { if (it.status == Status.NO_TV) it else it.copy(status = Status.CONNECTING) }
            }
        }
    }

    /** Salva uma TV recém-pareada e conecta nela. */
    fun setTv(tv: SavedTv) {
        stopLoop()
        Store.saveTv(app, tv)
        _state.value = State(Status.CONNECTING, tv.name, tv.host)
        ensureRunning()
    }

    /** Desconecta e esquece a TV (só pelas configurações). */
    fun forget() {
        stopLoop()
        Store.clearTv(app)
        _state.value = State()
    }

    private fun ensureRunning() {
        synchronized(lock) {
            if (Store.tv(app) == null) {
                _state.value = State()
                return
            }
            if (loopJob?.isActive == true) return
            loopJob = scope.launch { runLoop() }
        }
    }

    private fun stopLoop() {
        synchronized(lock) {
            loopJob?.cancel()
            loopJob = null
        }
        client?.close()
        client = null
    }

    // ------------------------------------------------------------ comandos

    /** Aperta uma tecla. Retorna false se ainda não está conectado. */
    fun sendKey(keyCode: Int, direction: Int = RemoteClient.DIRECTION_SHORT): Boolean =
        submit { it.sendKey(keyCode, direction) }

    /** Abre um app na TV pelo nome do pacote. */
    fun launchApp(packageName: String): Boolean =
        submit { it.launch(Streamings.launchLink(packageName)) }

    private fun submit(action: (RemoteClient) -> Unit): Boolean {
        val c = client
        if (c == null || !c.isReady) {
            onAppVisible()
            return false
        }
        sender.execute {
            try {
                action(c)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao enviar comando", e)
            }
        }
        return true
    }

    // ------------------------------------------------------------ conexão

    private enum class Outcome { WAS_CONNECTED, AUTH, FAILED }

    private suspend fun runLoop() {
        var failures = 0
        var authFailures = 0
        while (currentCoroutineContext().isActive) {
            val tv = Store.tv(app)
            if (tv == null) {
                _state.value = State()
                return
            }
            _state.update {
                it.copy(
                    tvName = tv.name,
                    host = tv.host,
                    status = when {
                        it.status == Status.NEEDS_PAIRING -> it.status
                        failures >= 2 -> Status.OFFLINE
                        else -> Status.CONNECTING
                    },
                )
            }
            when (connectAndWait(tv)) {
                Outcome.WAS_CONNECTED -> {
                    failures = 0
                    authFailures = 0
                    _state.update { it.copy(status = Status.CONNECTING) }
                    waitKick(500)
                }
                Outcome.AUTH -> {
                    authFailures++
                    failures++
                    if (authFailures >= 2) _state.update { it.copy(status = Status.NEEDS_PAIRING, isOn = null) }
                    waitKick(if (authFailures >= 2) 20_000 else 1_000)
                }
                Outcome.FAILED -> {
                    failures++
                    authFailures = 0
                    if (failures >= 2) {
                        _state.update {
                            if (it.status == Status.NEEDS_PAIRING) it
                            else it.copy(status = Status.OFFLINE, isOn = null)
                        }
                    }
                    // A TV pode ter ganhado outro IP do roteador: procura pelo nome.
                    if (failures % 3 == 2 && rediscover(tv)) continue
                    waitKick(backoff(failures))
                }
            }
        }
    }

    private fun backoff(failures: Int): Long =
        (1_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(15_000L)

    private suspend fun waitKick(ms: Long) {
        withTimeoutOrNull(ms) { kick.receive() }
    }

    private suspend fun rediscover(tv: SavedTv): Boolean {
        val found = try {
            TvDiscovery.findByName(app, tv.name, 6_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        if (found.host == tv.host) return false
        Store.updateTvHost(app, found.host)
        _state.update { it.copy(host = found.host) }
        return true
    }

    private suspend fun connectAndWait(tv: SavedTv): Outcome {
        val identity = try {
            Store.identity(app)
        } catch (e: Exception) {
            Log.e(TAG, "Sem certificado", e)
            return Outcome.FAILED
        }
        val ready = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Boolean>()
        val listener = object : RemoteClient.Listener {
            override fun onReady() {
                ready.complete(Unit)
            }

            override fun onPowerChanged(on: Boolean) {
                _state.update { it.copy(isOn = on) }
            }

            override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) {
                _state.update { it.copy(volume = level, volumeMax = max, muted = muted) }
            }

            override fun onCurrentApp(packageName: String) {}

            override fun onClosed(cause: Exception?, authProblem: Boolean) {
                if (cause != null) Log.i(TAG, "Conexão encerrada: $cause")
                closed.complete(authProblem)
            }
        }
        val c = RemoteClient(identity, Build.MODEL ?: "Android", Build.MANUFACTURER ?: "Android", listener)
        try {
            try {
                c.connect(tv.host, tv.port, 5_000)
            } catch (e: RemoteClient.AuthException) {
                return Outcome.AUTH
            } catch (e: Exception) {
                return Outcome.FAILED
            }
            currentCoroutineContext().let { if (!it.isActive) return Outcome.FAILED }

            val becameReady = withTimeoutOrNull(READY_TIMEOUT_MS) {
                select<Boolean> {
                    ready.onAwait { true }
                    closed.onAwait { false }
                }
            }
            if (becameReady != true) {
                c.close()
                return if (closed.await()) Outcome.AUTH else Outcome.FAILED
            }

            client = c
            _state.update { it.copy(status = Status.CONNECTED, tvName = tv.name, host = tv.host) }
            closed.await()
            return Outcome.WAS_CONNECTED
        } finally {
            if (client === c) client = null
            c.close()
        }
    }

    // ------------------------------------------------------------ rede

    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    kick.trySend(Unit)
                }
            })
        }
    }
}
