package br.vitoria.controletv.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.vitoria.controletv.R
import br.vitoria.controletv.data.Store
import br.vitoria.controletv.databinding.ActivityMainBinding
import br.vitoria.controletv.databinding.ItemRoundButtonBinding
import br.vitoria.controletv.tv.TvConnection
import br.vitoria.controletv.tv.TvConnection.Status
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var current = TvConnection.State()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupButtons()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TvConnection.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvConnection.onAppVisible()
        renderShortcuts()
    }

    override fun onStop() {
        super.onStop()
        TvConnection.onAppHidden()
    }

    // ------------------------------------------------------------ botões

    private fun setupButtons() {
        b.btnAppSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnPair.setOnClickListener { openPairing() }
        b.header.setOnClickListener {
            if (current.status == Status.NEEDS_PAIRING) openPairing()
        }

        round(b.power, R.drawable.ic_power, R.string.btn_power) { key(KEY_POWER) }
        round(b.inputs, R.drawable.ic_input, R.string.btn_inputs) { key(KEY_TV_INPUT) }
        round(b.tvSettings, R.drawable.ic_settings, R.string.btn_tv_settings) { key(KEY_SETTINGS) }
        round(b.back, R.drawable.ic_back, R.string.btn_back) { key(KEY_BACK) }
        round(b.home, R.drawable.ic_home, R.string.btn_home) { key(KEY_HOME) }
        round(b.menu, R.drawable.ic_menu, R.string.btn_menu) { key(KEY_MENU) }
        round(b.mute, R.drawable.ic_mute, R.string.btn_mute) { key(KEY_MUTE) }
        round(b.playPause, R.drawable.ic_play_pause, R.string.btn_play_pause) { key(KEY_PLAY_PAUSE) }

        b.dpadUp.onPressRepeat { key(KEY_UP, b.dpadUp) }
        b.dpadDown.onPressRepeat { key(KEY_DOWN, b.dpadDown) }
        b.dpadLeft.onPressRepeat { key(KEY_LEFT, b.dpadLeft) }
        b.dpadRight.onPressRepeat { key(KEY_RIGHT, b.dpadRight) }
        b.dpadOk.setOnClickListener { key(KEY_OK, it) }
        b.volUp.onPressRepeat { key(KEY_VOLUME_UP, b.volUp) }
        b.volDown.onPressRepeat { key(KEY_VOLUME_DOWN, b.volDown) }
    }

    private fun round(item: ItemRoundButtonBinding, icon: Int, label: Int, action: () -> Unit) {
        item.button.setImageResource(icon)
        item.button.contentDescription = getString(label)
        item.label.setText(label)
        item.button.setOnClickListener {
            it.tapFeedback()
            action()
        }
    }

    private fun key(code: Int, view: android.view.View? = null) {
        view?.tapFeedback()
        if (!TvConnection.sendKey(code)) explainNotConnected()
    }

    private fun explainNotConnected() {
        toastOnce(
            getString(
                if (current.status == Status.NEEDS_PAIRING) R.string.toast_needs_pairing
                else R.string.toast_connecting
            )
        )
    }

    private fun openPairing() {
        startActivity(Intent(this, PairActivity::class.java))
    }

    // ------------------------------------------------------------ atalhos

    private fun renderShortcuts() {
        val row = b.shortcutRow
        row.removeAllViews()
        val font = ResourcesCompat.getFont(this, R.font.open_sans_semibold)
        Store.shortcuts(this).forEachIndexed { index, shortcut ->
            val tile = TextView(this).apply {
                text = shortcut.label
                typeface = font
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.text))
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                setBackgroundResource(R.drawable.bg_tile)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.open_app, shortcut.label)
                setOnClickListener {
                    it.tapFeedback()
                    if (!TvConnection.launchApp(shortcut.packageName)) explainNotConnected()
                }
                setOnLongClickListener {
                    ShortcutEditor.show(this@MainActivity, index) { renderShortcuts() }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(60), 1f)
            lp.marginStart = if (index == 0) 0 else dp(4)
            lp.marginEnd = if (index == Store.SHORTCUT_COUNT - 1) 0 else dp(4)
            row.addView(tile, lp)
        }
    }

    // ------------------------------------------------------------ estado

    private fun render(s: TvConnection.State) {
        current = s
        val hasTv = s.status != Status.NO_TV
        b.emptyState.isVisible = !hasTv
        b.remoteScroll.isVisible = hasTv
        if (!hasTv) return

        b.tvName.text = s.tvName ?: getString(R.string.tv)
        b.statusText.text = when (s.status) {
            Status.CONNECTED -> getString(if (s.isOn == false) R.string.status_standby else R.string.status_connected)
            Status.CONNECTING -> getString(R.string.status_connecting)
            Status.OFFLINE -> getString(R.string.status_offline)
            Status.NEEDS_PAIRING -> getString(R.string.status_needs_pairing)
            Status.NO_TV -> ""
        }
        b.statusDot.setBackgroundResource(
            if (s.status == Status.CONNECTED) R.drawable.dot_on else R.drawable.dot_off
        )
        b.controls.alpha = if (s.status == Status.CONNECTED) 1f else 0.45f

        // botão de ligar fica "aceso" quando a TV está ligada
        val on = s.status == Status.CONNECTED && s.isOn == true
        b.power.button.setBackgroundResource(if (on) R.drawable.bg_round_button_light else R.drawable.bg_round_button)
        b.power.button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (on) R.color.bg else R.color.text)
        )
        b.power.label.setText(if (on) R.string.btn_power_off else R.string.btn_power)

        b.volumeText.text = when {
            s.status != Status.CONNECTED || s.volume == null -> getString(R.string.volume)
            s.muted -> getString(R.string.muted)
            else -> s.volume.toString()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        // códigos de tecla do Android
        const val KEY_HOME = 3
        const val KEY_BACK = 4
        const val KEY_UP = 19
        const val KEY_DOWN = 20
        const val KEY_LEFT = 21
        const val KEY_RIGHT = 22
        const val KEY_OK = 23
        const val KEY_VOLUME_UP = 24
        const val KEY_VOLUME_DOWN = 25
        const val KEY_POWER = 26
        const val KEY_MENU = 82
        const val KEY_PLAY_PAUSE = 85
        const val KEY_MUTE = 164
        const val KEY_SETTINGS = 176
        const val KEY_TV_INPUT = 178
    }
}
