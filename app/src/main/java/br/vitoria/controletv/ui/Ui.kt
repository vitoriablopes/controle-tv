package br.vitoria.controletv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import br.vitoria.controletv.R
import br.vitoria.controletv.data.Shortcut
import br.vitoria.controletv.data.Store
import br.vitoria.controletv.data.Streamings
import br.vitoria.controletv.databinding.DialogInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Vibração curtinha ao tocar, se estiver ligada nas configurações. */
fun View.tapFeedback() {
    if (Store.vibrate(context)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/**
 * Executa [action] ao apertar e repete enquanto o dedo estiver no botão
 * (bom para setas e volume).
 */
@SuppressLint("ClickableViewAccessibility")
fun View.onPressRepeat(action: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var repeater: Runnable? = null
    fun stop() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.drawableHotspotChanged(e.x, e.y)
                v.isPressed = true
                action()
                val r = object : Runnable {
                    override fun run() {
                        action()
                        handler.postDelayed(this, 110)
                    }
                }
                repeater = r
                handler.postDelayed(r, 450)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                stop()
            }
        }
        true
    }
    // para leitores de tela (que "clicam" sem tocar)
    setOnClickListener { action() }
}

private var lastToast = 0L

/** Toast que não se repete em sequência. */
fun Activity.toastOnce(text: String) {
    val now = System.currentTimeMillis()
    if (now - lastToast < 2_000) return
    lastToast = now
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** Escolha do app de um dos 5 botões de streaming. */
object ShortcutEditor {
    fun show(activity: Activity, index: Int, onSaved: () -> Unit) {
        val names = Streamings.all.map { it.name } + activity.getString(R.string.other_app)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.shortcut_title, index + 1))
            .setItems(names.toTypedArray()) { _, which ->
                if (which < Streamings.all.size) {
                    val app = Streamings.all[which]
                    Store.saveShortcut(activity, index, Shortcut(app.name, app.packageName))
                    onSaved()
                } else {
                    showCustom(activity, index, onSaved)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustom(activity: Activity, index: Int, onSaved: () -> Unit) {
        val b = DialogInputBinding.inflate(LayoutInflater.from(activity))
        b.message.setText(R.string.custom_app_help)
        b.field1Layout.hint = activity.getString(R.string.custom_app_name)
        b.field2Layout.hint = activity.getString(R.string.custom_app_package)
        b.field2Layout.visibility = View.VISIBLE
        b.field2.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_URI
        b.field1.filters = arrayOf(InputFilter.LengthFilter(20))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.other_app)
            .setView(b.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = b.field1.text?.toString()?.trim().orEmpty()
            var pkg = b.field2.text?.toString()?.trim().orEmpty()
            // aceita também o link inteiro da Play Store
            Regex("id=([A-Za-z0-9_.]+)").find(pkg)?.let { pkg = it.groupValues[1] }
            b.field1Layout.error = if (name.isEmpty()) activity.getString(R.string.error_name) else null
            val pkgOk = pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
            b.field2Layout.error = if (!pkgOk) activity.getString(R.string.error_package) else null
            if (name.isNotEmpty() && pkgOk) {
                Store.saveShortcut(activity, index, Shortcut(name, pkg))
                onSaved()
                dialog.dismiss()
            }
        }
    }
}
