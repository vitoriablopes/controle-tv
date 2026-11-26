package br.vitoria.controletv

import android.app.Application
import br.vitoria.controletv.tv.TvConnection

class ControleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TvConnection.init(this)
    }
}
