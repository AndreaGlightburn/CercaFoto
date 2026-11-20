package eu.iz2oii.cercafoto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Stato dell'app (equivalente del ViewModel Android) */
@OptIn(FlowPreview::class)
object Stato {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val foto = MutableStateFlow<List<FotoIdx>>(emptyList())
    val persone = MutableStateFlow<List<Persona>>(emptyList())
    val conteggi = MutableStateFlow(Triple(0, 0, 0))
    val query = MutableStateFlow("")
    val filtri = MutableStateFlow(Filtri())
    val cartelle = MutableStateFlow(Impostazioni.cartelle)
    /** dimensione delle anteprime (dp), regolabile con lo zoom */
    val lato = MutableStateFlow(Impostazioni.lato)
    fun zoom(nuovo: Int) { val v = nuovo.coerceIn(70, 420); lato.value = v; Impostazioni.lato = v }
    val lavoro = MutableStateFlow<String?>(null)
    val messaggio = MutableStateFlow<String?>(null)
    val avanzamento get() = Indicizzatore.stato

    /** false = parole chiave (come sul telefono), true = descrizione libera (CLIP) */
    val descrizione = MutableStateFlow(false)
    @Volatile private var vettori: Map<Long, FloatArray> = emptyMap()

    private data class Richiesta(val q: String, val descr: Boolean)

    val risultati: StateFlow<List<FotoIdx>> =
        combine(combine(query, descrizione) { q, d -> Richiesta(q, d) }.debounce(300), foto, persone, filtri) { r, f, p, fi ->
            if (r.descr && r.q.isNotBlank() && Clip.disponibile) {
                val perId = f.associateBy { it.id }
                fi.applica(runCatching { Clip.cerca(r.q.trim(), vettori) }.getOrDefault(emptyList()).mapNotNull { perId[it.first] })
            } else fi.applica(Ricerca.cerca(r.q, f, p))
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        ricarica()
        // aggiorna i risultati durante l'indicizzazione
        scope.launch {
            var ultimo = 0; var fase = ""
            Indicizzatore.stato.collect { a ->
                if (a.fase != fase) { fase = a.fase; ultimo = 0 }
                if (a.attivo && a.fatte - ultimo >= 100) { ultimo = a.fatte; ricarica() }
            }
        }
    }

    fun ricarica() {
        scope.launch {
            vettori = Db.vettoriClip()
            foto.value = Db.caricaFoto(); persone.value = Db.caricaPersone(); conteggi.value = Db.conteggi()
        }
    }

    private fun op(blocco: () -> Unit) = scope.launch {
        runCatching(blocco).onFailure { messaggio.value = "Errore: ${it.message}" }
        ricarica()
    }

    private fun sfondo(testo: String, blocco: () -> String?) = scope.launch {
        lavoro.value = testo
        val m = runCatching(blocco).getOrElse { "Errore: ${it.message}" }
        lavoro.value = null
        if (m != null) messaggio.value = m
        ricarica()
    }

    private fun foto(n: Int) = if (n == 1) "1 foto" else "$n foto"

    // ------------------------------------------------------------ indice
    fun indicizza(rianalisiVolti: Boolean = false) = Indicizzatore.avvia(scope, { ricarica() }, rianalisiVolti)
    fun ferma() = Indicizzatore.ferma()

    fun aggiungiCartella(c: File) {
        val l = (Impostazioni.cartelle + c.absolutePath).distinct()
        Impostazioni.cartelle = l; cartelle.value = l
        indicizza()
    }

    fun rimuoviCartella(c: String) {
        val l = Impostazioni.cartelle - c
        Impostazioni.cartelle = l; cartelle.value = l
        op { val ids = ArrayList<Long>(); Db.q("SELECT id FROM foto WHERE cartella = ?", c) { ids += it.getLong(1) }; Db.rimuovi(ids) }
    }

    fun ricalcolaGruppi() = sfondo("Raggruppo i volti…") { Raggruppa.esegui(); null }

    fun azzera() { Indicizzatore.ferma(); op { Db.azzera(); indicizza() } }

    // ------------------------------------------------------------ persone
    fun rinomina(id: Long, nome: String) = op { Db.rinomina(id, nome) }
    fun nascondi(id: Long, s: Boolean) = op { Db.nascondi(id, s) }
    fun unisci(dest: Long, src: Long) = op { Db.unisci(dest, src) }
    fun escludiFoto(lista: List<Long>, persona: Long) = op { lista.forEach { Db.escludi(it, persona) }; messaggio.value = "Tolte ${foto(lista.size)} dal gruppo" }

    fun spostaFoto(lista: List<Long>, da: Long, a: Persona) = op {
        Db.spostaFoto(lista, da, a.id)
        messaggio.value = "Spostate ${foto(lista.size)} in «${a.nome ?: "gruppo senza nome"}»"
    }

    fun nuovaPersonaDaFoto(lista: List<Long>, da: Long, nome: String) = op {
        val id = Db.nuovaPersona(nome); Db.spostaFoto(lista, da, id); messaggio.value = "Creata «${nome.trim()}»"
    }

    fun assegnaVolto(voltoId: Long, personaId: Long, nome: String?) = op {
        Db.assegnaVolto(voltoId, personaId)
        messaggio.value = if (personaId == Db.ESCLUSO) "Volto tolto dal gruppo" else "Volto spostato in «${nome ?: "gruppo senza nome"}»"
    }

    fun nuovaPersonaDaVolto(voltoId: Long, nome: String) = op { Db.nuovaPersonaDaVolto(voltoId, nome) }
    fun associa(lista: List<Long>, p: Persona) = op { val n = Db.associa(lista, p.id); messaggio.value = "Aggiunte ${foto(n)} a «${p.nome}»" }
    fun nuovaPersonaConFoto(lista: List<Long>, nome: String) = op { val id = Db.nuovaPersona(nome); Db.associa(lista, id) }
    fun rimuoviAssociazione(voltoId: Long) = op { Db.rimuoviVolto(voltoId) }
    fun salvaEtichette(id: Long, man: List<String>, rim: Set<String>) = op { Db.etichetteManuali(id, man, rim) }

    fun somiglianze(id: Long): Map<Long, Float> {
        val c = Db.centri(); val mio = c[id] ?: return emptyMap()
        return c.filterKeys { it != id }.mapValues { dot(mio, it.value).coerceIn(0f, 1f) }
    }

    fun somiglianzeVolto(voltoId: Long): Map<Long, Float> {
        val v = Db.vettoreVolto(voltoId) ?: return emptyMap()
        return Db.centri().mapValues { dot(v, it.value).coerceIn(0f, 1f) }
    }

    fun somiglianzeFoto(lista: List<Long>, persona: Long): Map<Long, Float> {
        val v = Db.vettoriFotoPersona(lista, persona)
        if (v.isEmpty()) return emptyMap()
        val m = FloatArray(v[0].size); for (e in v) for (i in e.indices) m[i] += e[i]; normalizza(m)
        return Db.centri().filterKeys { it != persona }.mapValues { dot(m, it.value).coerceIn(0f, 1f) }
    }

    // ------------------------------------------------------------ scambio
    fun esporta(f: File) = sfondo("Esportazione…") {
        val (p, n) = Scambio.esporta(f) { lavoro.value = it }
        "Esportate $p persone e le correzioni di ${foto(n)} in ${f.name}"
    }

    fun importa(f: File) = sfondo("Importazione…") {
        val e = Scambio.importa(f) { lavoro.value = it }
        "Importate ${e.persone} persone (${e.nuove} nuove), correzioni su ${foto(e.foto)}" +
            (if (e.fotoNonTrovate > 0) " (${e.fotoNonTrovate} non presenti qui)" else "") + ", ${e.voltiRiconosciuti} volti riconosciuti"
    }
}
