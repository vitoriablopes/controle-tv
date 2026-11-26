package br.vitoria.controletv.data

/** Apps que dá para escolher nos botões de atalho (nome do pacote no Android TV). */
object Streamings {
    data class App(val name: String, val packageName: String)

    val all = listOf(
        App("Netflix", "com.netflix.ninja"),
        App("YouTube", "com.google.android.youtube.tv"),
        App("Prime Video", "com.amazon.amazonvideo.livingroom"),
        App("Disney+", "com.disney.disneyplus"),
        App("Globoplay", "com.globo.globotv"),
        App("HBO Max", "com.wbd.stream"),
        App("Apple TV", "com.apple.atve.androidtv.appletv"),
        App("Paramount+", "com.cbs.ott"),
        App("Crunchyroll", "com.crunchyroll.crunchyroid"),
        App("Spotify", "com.spotify.tv.android"),
        App("YouTube Music", "com.google.android.youtube.tvmusic"),
        App("Pluto TV", "tv.pluto.android"),
        App("Plex", "com.plexapp.android"),
        App("Play Store", "com.android.vending"),
    )

    fun byName(name: String) = all.firstOrNull { it.name == name }

    /** Link que a TV entende para abrir um app pelo nome do pacote. */
    fun launchLink(packageName: String) = "market://launch?id=$packageName"
}
