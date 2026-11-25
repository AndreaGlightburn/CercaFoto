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
    /** null = tutto, "foto" = solo foto, "video" = solo video */
    val tipo = MutableStateFlow<String?>(null)
    val nVideo = MutableStateFlow(0)
    @Volatile private var vettori: Map<Long, FloatArray> = emptyMap()

    // ------------------------------------------------------------ filtri personalizzati
    val filtriUtente = MutableStateFlow<List<FiltroUtente>>(emptyList())
    /** filtro personalizzato applicato alla ricerca (id) */
    val filtroAttivo = MutableStateFlow<Long?>(null)
    /** se impostato, la selezione in Cerca può aggiungere esempi a questo filtro */
    val aggiuntaEsempi = MutableStateFlow<Long?>(null)
    fun vettoriClip() = vettori
    @Volatile private var zoneM: Map<Long, Zone> = emptyMap()
    /** zone (dettagli) delle foto, se calcolate */
    fun zone() = zoneM

    private data class Richiesta(val q: String, val descr: Boolean, val filtro: Long?, val tipo: String?)

    private val PAROLE_VIDEO = setOf("video", "filmato", "filmati", "filmino", "filmini", "clip")

    /** Ricerca + filtri: i nomi dei filtri personalizzati scritti nella ricerca funzionano come parole chiave */
    private fun calcola(r: Richiesta, f: List<FotoIdx>, p: List<Persona>, fi: Filtri, fu: List<FiltroUtente>): List<FotoIdx> {
        var q = r.q
        val insiemi = ArrayList<Set<Long>>()
        fu.firstOrNull { it.id == r.filtro }?.let { insiemi += FiltriUtente.membri(it, vettori).map { m -> m.first }.toSet() }
        var tipo = r.tipo
        if (!r.descr) {
            var qn = " " + Testo.normalizza(q) + " "
            for (w in PAROLE_VIDEO) if (qn.contains(" $w ")) { tipo = "video"; qn = qn.replace(" $w ", " ") }
            for (x in fu.sortedByDescending { it.nome.length }) {
                val n = Testo.normalizza(x.nome)
                if (n.isNotEmpty() && qn.contains(" $n ")) { insiemi += FiltriUtente.membri(x, vettori).map { it.first }.toSet(); qn = qn.replace(" $n ", " ") }
            }
            q = qn.trim()
        }
        val base = if (r.descr && q.isNotBlank() && Clip.disponibile) {
            val perId = f.associateBy { it.id }
            runCatching { Clip.cerca(q.trim(), vettori, zoneM) }.getOrDefault(emptyList()).mapNotNull { perId[it.first] }
        } else Ricerca.cerca(q, f, p)
        val filtrate = fi.applica(base.filter { x -> insiemi.all { x.id in it } && (tipo == null || (tipo == "video") == x.isVideo) })
        return comprimiVideo(filtrate)
    }

    /** Ogni video compare una volta sola: la prima voce rappresenta tutti i suoi fotogrammi presenti nella lista */
    fun comprimiVideo(l: List<FotoIdx>): List<FotoIdx> {
        val gruppi = LinkedHashMap<String, MutableList<FotoIdx>>()
        val pos = HashMap<String, Int>()
        val out = ArrayList<FotoIdx>(l.size)
        for (f in l) {
            val v = f.video
            if (v == null) { out += f; continue }
            val g = gruppi[v]
            if (g == null) { gruppi[v] = mutableListOf(f); pos[v] = out.size; out += f } else g += f
        }
        for ((v, g) in gruppi) out[pos[v]!!] = g[0].copy(momenti = g.sortedBy { it.tempo }.map { it.id })
        return out
    }

    /** Dagli id delle voci (anche raggruppate) a tutti gli id dei fotogrammi/foto che rappresentano */
    fun espandi(ids: Collection<Long>, lista: List<FotoIdx>): List<Long> {
        val per = lista.associateBy { it.id }
        return ids.flatMap { per[it]?.momenti?.takeIf { m -> m.isNotEmpty() } ?: listOf(it) }.distinct()
    }

    val risultati: StateFlow<List<FotoIdx>> =
        combine(combine(query, descrizione, filtroAttivo, tipo) { q, d, fa, t -> Richiesta(q, d, fa, t) }.debounce(300), foto, persone, filtri, filtriUtente) { r, f, p, fi, fu ->
            calcola(r, f, p, fi, fu)
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
            zoneM = if (Impostazioni.zone) Db.zone() else emptyMap()
            filtriUtente.value = FiltriUtente.tutti()
            foto.value = Db.caricaFoto(); persone.value = Db.caricaPersone(); conteggi.value = Db.conteggi(); nVideo.value = Db.contaVideo()
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

    /** Scrive subito l'indice portatile nella radice della cartella */
    fun scriviPortatile(c: String) = sfondo("Scrivo l'indice sul disco…") {
        if (!File(c).isDirectory) "Cartella non raggiungibile: collega il disco"
        else { Indicizzatore.aggiornaPortatili(listOf(c), forza = true); Indicizzatore.stato.value = Avanzamento()
            "Indice salvato in ${Portatile.file(File(c)).absolutePath}" }
    }

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

    // ------------------------------------------------------------ filtri personalizzati: operazioni
    fun creaFiltroDaEsempi(nome: String, ids: List<Long>, dopo: (Long) -> Unit = {}) = sfondo("Imparo «${nome.trim()}» dagli esempi…") {
        val id = FiltriUtente.daEsempi(0, nome, ids.toSet(), emptySet(), vettori)
        filtriUtente.value = FiltriUtente.tutti(); dopo(id)
        "Filtro «${nome.trim()}» creato da ${ids.size} esempi: controlla i risultati ed escludi quelli sbagliati"
    }

    fun creaFiltroDaDescrizione(nome: String, frasi: List<String>, dopo: (Long) -> Unit = {}) = sfondo("Creo il filtro…") {
        val id = FiltriUtente.daDescrizione(0, nome, frasi)
        filtriUtente.value = FiltriUtente.tutti(); dopo(id)
        "Filtro «${nome.trim()}» creato"
    }

    /** Zona di una foto scelta nel visore come esempio giusto o sbagliato di un filtro (o per crearne uno nuovo se [nuovo] non è null) */
    fun esempioZona(f: FotoIdx, l: Float, t: Float, r: Float, b: Float, filtro: Long?, giusto: Boolean, nuovo: String? = null) = sfondo("Imparo dalla zona scelta…") {
        val img = Immagini.leggi(f.file, 2400) ?: error("foto non leggibile")
        val v = Clip.immagine(Zone.ritaglio(img, l, t, r, b))
        val primaVolta = !Impostazioni.zone
        Impostazioni.zone = true
        val msg = if (nuovo != null) {
            val id = FiltriUtente.nuovoDaZona(nuovo, f.id, l, t, r, b, v, vettori)
            filtroAperto.value = id
            "Filtro «${nuovo.trim()}» creato dalla zona"
        } else {
            val fu = FiltriUtente.tutti().first { it.id == filtro }
            Db.nuovoEsempioZona(fu.id, f.id, l, t, r, b, giusto, v)
            FiltriUtente.impostaDettagli(fu.id, true)
            if (fu.esempi) FiltriUtente.daEsempi(fu.id, fu.nome, fu.positivi, fu.negativi, vettori, fu.soglia, dettagli = true)
            else FiltriUtente.daEsempi(fu.id, fu.nome, FiltriUtente.membri(fu, vettori).take(30).map { it.first }.toSet(), emptySet(), vettori, dettagli = true)
            (if (giusto) "Zona aggiunta come esempio di «${fu.nome}»" else "Zona esclusa da «${fu.nome}»")
        }
        filtriUtente.value = FiltriUtente.tutti()
        // le zone di tutte le foto servono per trovare il dettaglio: si calcolano in sottofondo
        if (Db.senzaZone().isNotEmpty()) { indicizza(); "$msg. Calcolo i dettagli delle foto in sottofondo" + (if (primaVolta) " (la prima volta richiede tempo)" else "") + ": i risultati migliorano man mano." }
        else msg
    }

    fun dettagliFiltro(id: Long, v: Boolean) = sfondo("Riaddestro il filtro…") {
        val f = FiltriUtente.tutti().first { it.id == id }
        if (v) Impostazioni.zone = true
        if (f.esempi) FiltriUtente.daEsempi(id, f.nome, f.positivi, f.negativi, vettori, f.soglia, dettagli = v) else FiltriUtente.impostaDettagli(id, v)
        filtriUtente.value = FiltriUtente.tutti()
        if (v && Db.senzaZone().isNotEmpty()) { indicizza(); "Cerco anche nei dettagli: calcolo le zone delle foto in sottofondo" } else null
    }

    /** filtro da aprire nella scheda Filtri (impostato dopo averlo creato dal visore) */
    val filtroAperto = MutableStateFlow<Long?>(null)

    /** Aggiunge esempi giusti (positivi) o sbagliati (negativi) e riaddestra */
    fun correggiFiltro(id: Long, ids: Collection<Long>, positivi: Boolean) = sfondo("Riaddestro il filtro…") {
        val f = FiltriUtente.tutti().first { it.id == id }
        val pos = if (positivi) f.positivi + ids else f.positivi - ids.toSet()
        val neg = if (positivi) f.negativi - ids.toSet() else f.negativi + ids
        if (f.esempi) FiltriUtente.daEsempi(id, f.nome, pos, neg, vettori, f.soglia)
        else {   // filtro da descrizione: le correzioni lo trasformano in filtro da esempi, partendo dalle foto che già trova
            val partenza = FiltriUtente.membri(f, vettori).take(30).map { it.first }.toSet()
            FiltriUtente.daEsempi(id, f.nome, partenza + pos - neg, neg, vettori)
        }
        filtriUtente.value = FiltriUtente.tutti()
        if (positivi) "Aggiunte ${foto(ids.size)} come esempi" else "Escluse ${foto(ids.size)}: il filtro ha imparato la differenza"
    }

    fun sogliaFiltro(id: Long, s: Float) {
        filtriUtente.value = filtriUtente.value.map { if (it.id == id) it.copy(soglia = s) else it }
        scope.launch { FiltriUtente.impostaSoglia(id, s) }
    }

    fun rinominaFiltro(id: Long, nome: String) = op { FiltriUtente.rinomina(id, nome) }
    fun eliminaFiltro(id: Long) = op { FiltriUtente.elimina(id); if (filtroAttivo.value == id) filtroAttivo.value = null }
    fun esportaFiltro(f: FiltroUtente, file: File) = sfondo("Esportazione…") { FiltriUtente.esporta(f, file); "Filtro salvato in ${file.name}" }
    fun importaFiltro(file: File) = sfondo("Importazione…") { FiltriUtente.importa(file); "Filtro importato da ${file.name}" }
}
