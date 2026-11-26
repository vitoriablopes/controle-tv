package br.vitoria.controletv.ui

import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import br.vitoria.controletv.R
import br.vitoria.controletv.core.PairingClient
import br.vitoria.controletv.core.RemoteClient
import br.vitoria.controletv.data.SavedTv
import br.vitoria.controletv.data.Store
import br.vitoria.controletv.databinding.ActivityPairBinding
import br.vitoria.controletv.databinding.DialogInputBinding
import br.vitoria.controletv.databinding.ItemTvBinding
import br.vitoria.controletv.tv.TvConnection
import br.vitoria.controletv.tv.TvDiscovery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Procura as TVs da rede e faz o pareamento com o código mostrado na tela. */
class PairActivity : AppCompatActivity() {
    private lateinit var b: ActivityPairBinding
    private lateinit var discovery: TvDiscovery
    private var found: List<TvDiscovery.FoundTv> = emptyList()
    private var pairing: PairingClient? = null
    private var busy = false
    private var hintJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPairBinding.inflate(layoutInflater)
        setContentView(b.root)

        discovery = TvDiscovery(this) { list ->
            found = list
            renderList()
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnManual.setOnClickListener { askIp() }
        b.btnRetry.setOnClickListener { restartSearch() }
        renderList()
    }

    override fun onStart() {
        super.onStart()
        restartSearch()
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
        hintJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        closePairing()
    }

    // ------------------------------------------------------------ busca

    private fun restartSearch() {
        discovery.stop()
        found = emptyList()
        renderList()
        discovery.start()
        b.notFoundHint.isVisible = false
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(10_000)
            if (found.isEmpty()) b.notFoundHint.isVisible = true
        }
    }

    private fun renderList() {
        b.list.removeAllViews()
        b.searchStatus.setText(if (found.isEmpty()) R.string.searching else R.string.tap_your_tv)
        b.searchProgress.isVisible = found.isEmpty() && !busy
        if (found.isNotEmpty()) b.notFoundHint.isVisible = false
        for (tv in found) {
            val item = ItemTvBinding.inflate(LayoutInflater.from(this), b.list, false)
            item.name.text = tv.name
            item.address.text = tv.host
            item.root.isEnabled = !busy
            item.root.setOnClickListener { startPairing(tv.name, tv.host, tv.port) }
            b.list.addView(item.root)
        }
    }

    private fun setBusy(message: String?) {
        busy = message != null
        b.busyRow.isVisible = busy
        b.busyText.text = message ?: ""
        b.btnManual.isEnabled = !busy
        b.btnRetry.isEnabled = !busy
        renderList()
    }

    // ------------------------------------------------------------ IP manual

    private fun askIp() {
        val d = DialogInputBinding.inflate(LayoutInflater.from(this))
        d.message.setText(R.string.manual_ip_help)
        d.field1Layout.hint = getString(R.string.manual_ip_hint)
        d.field1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_ip_title)
            .setView(d.root)
            .setPositiveButton(R.string.connect, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val ip = d.field1.text?.toString()?.trim().orEmpty()
            if (!ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
                d.field1Layout.error = getString(R.string.error_ip)
            } else {
                dialog.dismiss()
                startPairing(getString(R.string.tv_with_ip, ip), ip, RemoteClient.DEFAULT_PORT)
            }
        }
    }

    // ------------------------------------------------------------ pareamento

    private fun startPairing(name: String, host: String, remotePort: Int) {
        if (busy) return
        discovery.stop()
        setBusy(getString(R.string.connecting_to, name))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    closePairing()
                    val identity = Store.identity(applicationContext)
                    val client = PairingClient(identity, getString(R.string.client_name, Build.MODEL ?: "Android"))
                    pairing = client
                    client.start(host, PairingClient.DEFAULT_PORT)
                }
            }
            setBusy(null)
            result
                .onSuccess { askCode(name, host, remotePort) }
                .onFailure { e ->
                    closePairing()
                    showError(friendlyError(e))
                    discovery.start()
                }
        }
    }

    private fun askCode(name: String, host: String, remotePort: Int) {
        val d = DialogInputBinding.inflate(LayoutInflater.from(this))
        d.message.text = getString(R.string.code_help, name)
        d.field1Layout.hint = getString(R.string.code_hint)
        d.field1.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        d.field1.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
        d.field1.letterSpacing = 0.3f
        d.field1.textSize = 24f
        d.field1.textAlignment = View.TEXT_ALIGNMENT_CENTER

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.code_title)
            .setView(d.root)
            .setCancelable(false)
            .setPositiveButton(R.string.pair, null)
            .setNegativeButton(R.string.cancel) { _, _ ->
                closePairing()
                discovery.start()
            }
            .show()
        val positive = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        d.field1.requestFocus()
        positive.setOnClickListener {
            val code = d.field1.text?.toString().orEmpty()
            val client = pairing
            if (client == null) {
                dialog.dismiss()
                showError(getString(R.string.error_connection_lost))
                return@setOnClickListener
            }
            positive.isEnabled = false
            d.field1Layout.error = null
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { client.finish(code) } }
                positive.isEnabled = true
                r.onSuccess {
                    dialog.dismiss()
                    closePairing()
                    TvConnection.setTv(SavedTv(name, host, remotePort))
                    Toast.makeText(this@PairActivity, getString(R.string.paired_with, name), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { e ->
                    if (e is PairingClient.WrongCodeException) {
                        d.field1Layout.error = e.message
                    } else {
                        dialog.dismiss()
                        closePairing()
                        showError(getString(R.string.error_connection_lost))
                        discovery.start()
                    }
                }
            }
        }
    }

    private fun closePairing() {
        val p = pairing
        pairing = null
        if (p != null) Thread { p.close() }.start()
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is ConnectException, is SocketTimeoutException, is NoRouteToHostException, is UnknownHostException ->
            getString(R.string.error_unreachable)
        else -> getString(R.string.error_pairing, e.message ?: e.javaClass.simpleName)
    }

    private fun showError(message: String) {
        if (isFinishing) return
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
