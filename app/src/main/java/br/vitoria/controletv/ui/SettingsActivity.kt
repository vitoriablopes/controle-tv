package br.vitoria.controletv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.vitoria.controletv.BuildConfig
import br.vitoria.controletv.R
import br.vitoria.controletv.data.Store
import br.vitoria.controletv.databinding.ActivitySettingsBinding
import br.vitoria.controletv.databinding.ItemShortcutBinding
import br.vitoria.controletv.tv.TvConnection
import br.vitoria.controletv.tv.TvConnection.Status
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnPairOther.setOnClickListener { startActivity(Intent(this, PairActivity::class.java)) }
        b.btnForget.setOnClickListener { confirmForget() }
        b.switchVibrate.isChecked = Store.vibrate(this)
        b.switchVibrate.setOnCheckedChangeListener { _, checked -> Store.setVibrate(this, checked) }
        b.version.text = getString(R.string.version, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TvConnection.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        renderShortcuts()
    }

    private fun render(s: TvConnection.State) {
        val hasTv = s.status != Status.NO_TV
        b.tvCard.isVisible = hasTv
        b.btnForget.isVisible = hasTv
        b.noTvText.isVisible = !hasTv
        b.btnPairOther.setText(if (hasTv) R.string.pair_other_tv else R.string.search_tvs)
        b.tvName.text = s.tvName ?: getString(R.string.tv)
        b.tvAddress.text = s.host ?: ""
        b.tvStatus.text = when (s.status) {
            Status.CONNECTED -> getString(R.string.status_connected)
            Status.CONNECTING -> getString(R.string.status_connecting)
            Status.OFFLINE -> getString(R.string.status_offline)
            Status.NEEDS_PAIRING -> getString(R.string.status_needs_pairing_short)
            Status.NO_TV -> ""
        }
    }

    private fun renderShortcuts() {
        b.shortcutList.removeAllViews()
        Store.shortcuts(this).forEachIndexed { i, sc ->
            val item = ItemShortcutBinding.inflate(LayoutInflater.from(this), b.shortcutList, false)
            item.number.text = (i + 1).toString()
            item.name.text = sc.label
            item.pkg.text = sc.packageName
            item.root.setOnClickListener { ShortcutEditor.show(this, i) { renderShortcuts() } }
            b.shortcutList.addView(item.root)
        }
    }

    private fun confirmForget() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.forget_title)
            .setMessage(R.string.forget_message)
            .setPositiveButton(R.string.forget_confirm) { _, _ ->
                TvConnection.forget()
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
