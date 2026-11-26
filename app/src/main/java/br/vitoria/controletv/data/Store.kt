package br.vitoria.controletv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import br.vitoria.controletv.core.ClientIdentity

/** TV pareada e guardada no celular. */
data class SavedTv(
    val name: String,
    val host: String,
    val port: Int,
)

/** Um dos 5 botões de streaming. */
data class Shortcut(
    val label: String,
    val packageName: String,
)

/** Tudo o que o app guarda no celular (SharedPreferences). */
object Store {
    private const val FILE = "controle_tv"
    const val SHORTCUT_COUNT = 5

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ TV

    fun tv(ctx: Context): SavedTv? {
        val p = prefs(ctx)
        val host = p.getString("tv_host", null) ?: return null
        return SavedTv(
            name = p.getString("tv_name", null) ?: "TV",
            host = host,
            port = p.getInt("tv_port", 6466),
        )
    }

    fun saveTv(ctx: Context, tv: SavedTv) {
        prefs(ctx).edit()
            .putString("tv_name", tv.name)
            .putString("tv_host", tv.host)
            .putInt("tv_port", tv.port)
            .apply()
    }

    fun updateTvHost(ctx: Context, host: String) {
        prefs(ctx).edit().putString("tv_host", host).apply()
    }

    fun clearTv(ctx: Context) {
        prefs(ctx).edit().remove("tv_name").remove("tv_host").remove("tv_port").apply()
    }

    // ------------------------------------------------------------ identidade

    private val identityLock = Any()
    @Volatile private var cachedIdentity: ClientIdentity? = null

    /**
     * Certificado do celular. É gerado uma única vez (demora ~1 s) e reaproveitado
     * para sempre — é ele que a TV reconhece depois do pareamento.
     * Chame fora da thread principal.
     */
    fun identity(ctx: Context): ClientIdentity {
        cachedIdentity?.let { return it }
        synchronized(identityLock) {
            cachedIdentity?.let { return it }
            val p = prefs(ctx)
            val key = p.getString("id_key", null)
            val cert = p.getString("id_cert", null)
            val loaded = if (key != null && cert != null) {
                runCatching {
                    ClientIdentity.load(Base64.decode(key, Base64.NO_WRAP), Base64.decode(cert, Base64.NO_WRAP))
                }.getOrNull()
            } else null
            val id = loaded ?: ClientIdentity.generate("controletv").also {
                p.edit()
                    .putString("id_key", Base64.encodeToString(it.encodedKey(), Base64.NO_WRAP))
                    .putString("id_cert", Base64.encodeToString(it.encodedCertificate(), Base64.NO_WRAP))
                    .commit()
            }
            cachedIdentity = id
            return id
        }
    }

    // ------------------------------------------------------------- atalhos

    private val defaults = listOf("Netflix", "YouTube", "Prime Video", "Disney+", "Globoplay")

    fun shortcuts(ctx: Context): List<Shortcut> {
        val p = prefs(ctx)
        return (0 until SHORTCUT_COUNT).map { i ->
            val label = p.getString("sc_label_$i", null)
            val pkg = p.getString("sc_pkg_$i", null)
            if (label != null && pkg != null) {
                Shortcut(label, pkg)
            } else {
                val preset = Streamings.byName(defaults[i])!!
                Shortcut(preset.name, preset.packageName)
            }
        }
    }

    fun saveShortcut(ctx: Context, index: Int, shortcut: Shortcut) {
        prefs(ctx).edit()
            .putString("sc_label_$index", shortcut.label)
            .putString("sc_pkg_$index", shortcut.packageName)
            .apply()
    }

    // -------------------------------------------------------------- outras

    fun vibrate(ctx: Context): Boolean = prefs(ctx).getBoolean("vibrate", true)

    fun setVibrate(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("vibrate", value).apply()
    }
}
