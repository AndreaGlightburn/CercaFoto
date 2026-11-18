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

enum class TipoOp { COPIA, SPOSTA, CESTINO, ELIMINA }

/** Richiesta di consenso di sistema da mostrare; [dopo] va chiamato se l'utente conferma */
class Consenso(val pi: android.app.PendingIntent, val dopo: () -> Unit, val annullato: () -> Unit = {})

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

    val filtri = MutableStateFlow(Filtri())

    val risultati: StateFlow<List<FotoIdx>> =
        combine(query.debounce(250), foto, persone, filtri) { q, f, p, fi -> fi.applica(Ricerca.cerca(q, f, p)) }
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
        val rw = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, rw) }
            .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
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

    // ------------------------------------------------------------ copia / sposta / elimina

    val consenso = MutableStateFlow<Consenso?>(null)
    val lavoro = MutableStateFlow<String?>(null)
    val messaggio = MutableStateFlow<String?>(null)
    val cartelleGalleria = MutableStateFlow<List<CartellaGalleria>>(emptyList())

    fun caricaCartelleGalleria() {
        viewModelScope.launch {
            cartelleGalleria.value = withContext(Dispatchers.IO) { runCatching { Operazioni.cartelleGalleria(getApplication()) }.getOrDefault(emptyList()) }
        }
    }

    private fun sfondo(testo: String, blocco: suspend () -> String?) {
        viewModelScope.launch {
            lavoro.value = testo
            val m = withContext(Dispatchers.IO) { runCatching { blocco() }.getOrElse { "Errore: ${it.message}" } }
            lavoro.value = null
            if (m != null) messaggio.value = m
            ricarica()
        }
    }

    private fun foto(n: Int) = if (n == 1) "1 foto" else "$n foto"

    fun esegui(tipo: TipoOp, lista: List<FotoIdx>, dest: Destinazione? = null) {
        val ctx = getApplication<Application>()
        if (lista.isEmpty()) return
        val gall = lista.filter { it.cartella == null }
        val est = lista.filter { it.cartella != null }
        when (tipo) {
            TipoOp.COPIA -> sfondo("Copia in corso…") {
                var ok = 0
                for (f in lista) if (runCatching { Operazioni.copia(ctx, db, f, dest!!) }.getOrNull() != null) ok++
                "Copiate ${foto(ok)} in ${dest!!.descrizione()}" + if (ok < lista.size) " (${lista.size - ok} non riuscite)" else ""
            }

            TipoOp.CESTINO, TipoOp.ELIMINA -> {
                if (est.isNotEmpty()) sfondo("Eliminazione…") {
                    val eliminate = est.filter { Operazioni.eliminaEsterna(ctx, it) }
                    db.rimuovi(eliminate.map { it.id })
                    val nota = if (eliminate.size < est.size) " (alcune unità sono in sola lettura: riaggiungi la cartella)" else ""
                    if (gall.isEmpty()) "Eliminate ${foto(eliminate.size)}$nota" else null
                }
                if (gall.isNotEmpty()) {
                    val pi = if (tipo == TipoOp.CESTINO) Operazioni.richiestaCestino(ctx, gall) else Operazioni.richiestaEliminazione(ctx, gall)
                    if (pi != null) consenso.value = Consenso(pi, {
                        op { db.rimuovi(gall.map { it.id }) }
                        messaggio.value = (if (tipo == TipoOp.CESTINO) "Spostate nel cestino " else "Eliminate ") + foto(lista.size)
                    })
                }
            }

            TipoOp.SPOSTA -> {
                val d = dest ?: return
                if (d is Destinazione.Galleria && gall.isNotEmpty()) {
                    // stesso telefono: si cambia solo la cartella, l'indice resta valido
                    val pi = Operazioni.richiestaScrittura(ctx, gall) ?: return
                    consenso.value = Consenso(pi, {
                        sfondo("Spostamento…") {
                            var ok = gall.count { runCatching { Operazioni.spostaInGalleria(ctx, db, it, d) }.getOrDefault(false) }
                            for (f in est) if (runCatching { Operazioni.copia(ctx, db, f, d) }.getOrNull() != null && Operazioni.eliminaEsterna(ctx, f)) {
                                db.rimuovi(listOf(f.id)); ok++
                            }
                            "Spostate ${foto(ok)} in ${d.descrizione()}"
                        }
                    })
                } else {
                    // verso un'altra unità: copia, poi elimina gli originali (per la galleria serve conferma)
                    sfondo("Spostamento…") {
                        val copiate = lista.filter { runCatching { Operazioni.copia(ctx, db, it, d) }.getOrNull() != null }
                        val estOk = copiate.filter { it.cartella != null && Operazioni.eliminaEsterna(ctx, it) }
                        db.rimuovi(estOk.map { it.id })
                        val gallCopiate = copiate.filter { it.cartella == null }
                        val pi = Operazioni.richiestaEliminazione(ctx, gallCopiate)
                        if (pi != null) {
                            consenso.value = Consenso(pi, {
                                op { db.rimuovi(gallCopiate.map { it.id }) }
                                messaggio.value = "Spostate ${foto(copiate.size)} in ${d.descrizione()}"
                            }, { messaggio.value = "Copiate in ${d.descrizione()}, ma gli originali non sono stati eliminati" })
                            null
                        } else "Spostate ${foto(copiate.size)} in ${d.descrizione()}"
                    }
                }
            }
        }
    }
}
