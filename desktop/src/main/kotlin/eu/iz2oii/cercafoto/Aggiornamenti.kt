package eu.iz2oii.cercafoto

import org.json.JSONArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Controllo aggiornamenti, SOLO quando l'utente preme il pulsante: è l'unico momento in cui l'app usa internet.
 * Chiede a GitHub l'elenco delle versioni pubblicate nel repository pubblico dei download; non invia nessun dato.
 */
object Aggiornamenti {
    const val REPO = "AndreaGlightburn/CercaFoto-download"
    const val PAGINA = "https://github.com/$REPO/releases"
    val attuale: String? = System.getProperty("jpackage.app-version")

    class Esito(val ultima: String?, val nuova: Boolean, val pagina: String, val errore: String? = null)

    private fun numeri(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }

    /** true se [a] è più recente di [b] */
    fun piuRecente(a: String, b: String): Boolean {
        val x = numeri(a); val y = numeri(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    fun controlla(): Esito = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$REPO/releases?per_page=30"))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "CercaFoto").timeout(Duration.ofSeconds(15)).GET().build()
        val r = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() == 404) return Esito(null, false, PAGINA, "Il repository dei download non è ancora disponibile")
        if (r.statusCode() != 200) return Esito(null, false, PAGINA, "GitHub ha risposto ${r.statusCode()}")
        val a = JSONArray(r.body())
        var migliore: Pair<String, String>? = null
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val tag = o.optString("tag_name")
            if (!tag.startsWith("windows-")) continue
            val v = tag.removePrefix("windows-")
            if (migliore == null || piuRecente(v, migliore.first)) migliore = v to o.optString("html_url", PAGINA)
        }
        val m = migliore ?: return Esito(null, false, PAGINA, "Nessuna versione Windows pubblicata")
        Esito(m.first, attuale == null || piuRecente(m.first, attuale), m.second)
    }.getOrElse { Esito(null, false, PAGINA, "Nessuna connessione o GitHub non raggiungibile") }
}
