package eu.iz2oii.cercafoto

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Avanzamento(val attivo: Boolean, val fase: String, val fatte: Int, val totale: Int)

@OptIn(FlowPreview::class)
class AppVM(app: Application) : AndroidViewModel(app) {
    private val db = Db.get(app)

    val foto = MutableStateFlow<List<FotoIdx>>(emptyList())
    val persone = MutableStateFlow<List<Persona>>(emptyList())
    val conteggi = MutableStateFlow(Triple(0, 0, 0))
    val query = MutableStateFlow("")
    val avanzamento = MutableStateFlow(Avanzamento(false, "", 0, 0))
    val ricalcolo = MutableStateFlow(false)
    val caricato = MutableStateFlow(false)

    val risultati: StateFlow<List<FotoIdx>> =
        combine(query.debounce(250), foto, persone) { q, f, p -> Ricerca.cerca(q, f, p) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var ultimoRicarico = 0

    init {
        ricarica()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Indicizzatore.NOME).collect { lista ->
                val w = lista.firstOrNull()
                val attivo = w?.state == WorkInfo.State.RUNNING || w?.state == WorkInfo.State.ENQUEUED
                val fase = w?.progress?.getString("fase") ?: ""
                val fatte = w?.progress?.getInt("fatte", 0) ?: 0
                val tot = w?.progress?.getInt("totale", 0) ?: 0
                val prima = avanzamento.value
                avanzamento.value = Avanzamento(attivo, fase, fatte, tot)
                // aggiorna i risultati man mano e alla fine
                if (fase != prima.fase) ultimoRicarico = 0
                if ((prima.attivo && !attivo) || fatte - ultimoRicarico >= 100 || (fase != prima.fase && prima.fase.isNotEmpty())) {
                    ultimoRicarico = fatte
                    ricarica()
                }
                if (!attivo) ultimoRicarico = 0
            }
        }
    }

    fun ricarica() {
        viewModelScope.launch {
            val (f, p, c) = withContext(Dispatchers.IO) { Triple(db.caricaFoto(), db.caricaPersone(), db.conteggi()) }
            foto.value = f; persone.value = p; conteggi.value = c; caricato.value = true
        }
    }

    private fun op(blocco: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { blocco() }
            ricarica()
        }
    }

    fun rinomina(id: Long, nome: String) = op { db.rinomina(id, nome) }
    fun nascondi(id: Long, s: Boolean) = op { db.nascondi(id, s) }
    fun unisci(dest: Long, src: Long) = op { db.unisci(dest, src) }
    fun escludi(fotoId: Long, personaId: Long) = op { db.escludi(fotoId, personaId) }
    fun salvaEtichette(fotoId: Long, manuali: List<String>, rimosse: Set<String>) = op { db.etichetteManuali(fotoId, manuali, rimosse) }

    val cartelle = MutableStateFlow(Impostazioni.cartelle(app))

    fun aggiungiCartella(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val nuove = Impostazioni.cartelle(ctx) + uri.toString()
        Impostazioni.setCartelle(ctx, nuove); cartelle.value = nuove
        Indicizzatore.avvia(ctx, sostituisci = true)
    }

    fun rimuoviCartella(tree: String) {
        val ctx = getApplication<Application>()
        val nuove = Impostazioni.cartelle(ctx) - tree
        Impostazioni.setCartelle(ctx, nuove); cartelle.value = nuove
        runCatching { ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(tree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        op { db.rimuovi(db.fotoEsterne().filterValues { it == tree }.keys) }
    }

    fun ricalcolaGruppi() {
        if (ricalcolo.value) return
        ricalcolo.value = true
        viewModelScope.launch {
            withContext(Dispatchers.Default) { Raggruppa.esegui(getApplication()) }
            ricalcolo.value = false
            ricarica()
        }
    }

    fun azzeraEReindicizza() {
        val ctx = getApplication<Application>()
        Indicizzatore.ferma(ctx)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.azzera() }
            ricarica()
            Indicizzatore.avvia(ctx, sostituisci = true)
        }
    }
}
