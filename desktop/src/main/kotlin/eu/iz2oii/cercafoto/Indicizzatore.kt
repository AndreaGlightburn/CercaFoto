package eu.iz2oii.cercafoto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Impostazioni salvate in %LOCALAPPDATA%\CercaFoto\impostazioni.properties */
object Impostazioni {
    private val file = File(Percorsi.base, "impostazioni.properties")
    private val p = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    private fun salva() = file.outputStream().use { p.store(it, "Cerca Foto") }

    var cartelle: List<String>
        get() = p.getProperty("cartelle", "").split('|').filter { it.isNotBlank() }
        set(v) { p.setProperty("cartelle", v.joinToString("|")); salva() }
    var lato: Int
        get() = p.getProperty("lato", "150").toIntOrNull() ?: 150
        set(v) { p.setProperty("lato", v.toString()); salva() }
    /** indicizza anche i video (fotogrammi chiave) */
    var video: Boolean
        get() = p.getProperty("video", "true") == "true"
        set(v) { p.setProperty("video", v.toString()); salva() }
    /** identificativo di questo PC (per riconoscere il proprio indice portatile) */
    val dispositivo: String
        get() = p.getProperty("dispositivo") ?: java.util.UUID.randomUUID().toString().also { p.setProperty("dispositivo", it); salva() }
    /** salva l'indice portatile nella radice della cartella: di norma sì per dischi esterni/altre unità, no per il disco di sistema */
    fun portatile(c: String): Boolean = p.getProperty("portatile|$c")?.toBoolean() ?: !sulDiscoDiSistema(c)
    fun setPortatile(c: String, v: Boolean) { p.setProperty("portatile|$c", v.toString()); salva() }
    fun firmaPortatile(c: String): String? = p.getProperty("firma|$c")
    fun setFirmaPortatile(c: String, v: String) { p.setProperty("firma|$c", v); salva() }
    private fun sulDiscoDiSistema(c: String): Boolean {
        val sys = (System.getenv("SystemDrive") ?: return false).trimEnd('\\', '/').lowercase()
        return c.lowercase().startsWith(sys)
    }
    var soglia: Float
        get() = p.getProperty("soglia", "0.5").toFloatOrNull() ?: 0.5f
        set(v) { p.setProperty("soglia", v.toString()); salva() }
}

data class Avanzamento(val attivo: Boolean = false, val fase: String = "", val fatte: Int = 0, val totale: Int = 0, val dettaglio: String = "")

object Indicizzatore {
    private val ESTENSIONI = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
    val stato = MutableStateFlow(Avanzamento())
    private var lavoro: Job? = null
    private val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2)).asCoroutineDispatcher()

    class FileFoto(val file: File, val cartella: String)

    /** Elenca foto (e video in [video], se richiesto) nelle cartelle; ritorna anche le cartelle raggiungibili */
    fun elenca(cartelle: List<String>, video: MutableList<FileFoto>? = null): Pair<List<FileFoto>, Set<String>> {
        val out = ArrayList<FileFoto>()
        val raggiungibili = HashSet<String>()
        for (c in cartelle) {
            val radice = File(c)
            if (!radice.isDirectory) continue
            raggiungibili += c
            radice.walkTopDown().onEnter { !it.name.startsWith(".") && !it.isHidden || it == radice }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach {
                    val e = it.extension.lowercase()
                    if (e in ESTENSIONI) out += FileFoto(it, c)
                    else if (video != null && e in Video.ESTENSIONI) video += FileFoto(it, c)
                }
        }
        return out to raggiungibili
    }

    fun avvia(scope: CoroutineScope, alla_fine: () -> Unit, rianalisiVolti: Boolean = false) {
        if (lavoro?.isActive == true) return
        lavoro = scope.launch(Dispatchers.Default) {
            try {
                if (rianalisiVolti) rianalizzaVolti() else indicizza()
            } finally {
                stato.value = Avanzamento()
                alla_fine()
            }
        }
    }

    fun ferma() { lavoro?.cancel() }

    private suspend fun indicizza() {
        stato.value = Avanzamento(true, "lettura")
        val cartelle = Impostazioni.cartelle
        val video = ArrayList<FileFoto>()
        val conVideo = Impostazioni.video && Video.disponibile
        val (file, raggiungibili) = elenca(cartelle, if (conVideo) video else null)
        val noti = Db.stati()
        val presenti = file.mapTo(HashSet()) { it.file.absolutePath }
        // via solo le foto sparite da cartelle raggiungibili (o da cartelle tolte dall'elenco)
        val rimosse = noti.filter { (p, s) -> p !in presenti && (s.cartella == null || s.cartella in raggiungibili || s.cartella !in cartelle) }.map { it.value.id }
        Db.rimuovi(rimosse)
        val daFare = file.filter { f -> noti[f.file.absolutePath].let { it == null || it.modificata != f.file.lastModified() || it.dimensione != f.file.length() } }
            .sortedByDescending { it.file.lastModified() }
        // indici portatili trovati sulle cartelle (scritti dal telefono o da un altro PC): niente rianalisi
        val pacchetti = HashMap<String, IndicePortatile.Pacchetto>()
        val mappe = HashMap<String, Map<String, Long>>()
        for (c in daFare.map { it.cartella }.toSet()) runCatching { Portatile.leggi(c) }.getOrNull()?.let { pk -> pacchetti[c] = pk; mappe[c] = Portatile.mappaPersone(pk) }
        val dalIndice = AtomicInteger(0)
        val fatte = AtomicInteger(0)
        stato.value = Avanzamento(true, "analisi", 0, daFare.size)
        withContext(pool) {
            daFare.chunked(64).forEach { blocco ->
                if (!isActive) return@withContext
                blocco.map { ff ->
                    async {
                        val f = ff.file
                        val pk = pacchetti[ff.cartella]
                        val x = pk?.let { k -> Portatile.relativo(java.io.File(ff.cartella), f.absolutePath)?.let { rel -> k.foto(rel, f.length()) } }
                        if (x != null && runCatching { Portatile.usa(ff, x, mappe[ff.cartella] ?: emptyMap()) }.getOrDefault(false)) dalIndice.incrementAndGet()
                        else runCatching {
                            val r = Analisi.analizza(f)
                            val data = Immagini.dataScatto(f) ?: f.lastModified()
                            val id = Db.salva(f.absolutePath, ff.cartella, f.name, f.parentFile?.name ?: "", data, f.lastModified(), f.length(),
                                r?.volti ?: emptyList(), r?.sorriso ?: 0f, r?.colori ?: emptyMap(), r?.clip ?: (if (Clip.disponibile) FloatArray(0) else null), r?.etichette ?: emptyMap())
                            r?.miniatura?.let { Percorsi.miniatura(id).writeBytes(it) }
                        }
                        val n = fatte.incrementAndGet()
                        if (n % 4 == 0 || n == daFare.size) stato.value = Avanzamento(true, "analisi", n, daFare.size)
                    }
                }.awaitAll()
            }
        }
        // video: tolti quelli spariti, analizzati i nuovi o modificati
        val notiV = Db.statiVideo()
        val presentiV = video.mapTo(HashSet()) { it.file.absolutePath }
        val rimossiV = notiV.filter { (p, s) -> p !in presentiV && (!conVideo || s.cartella == null || s.cartella in raggiungibili || s.cartella !in cartelle) }.map { it.value.id }
        Db.rimuoviVideo(rimossiV)
        val daFareV = video.filter { f -> notiV[f.file.absolutePath].let { it == null || it.modificata != f.file.lastModified() || it.dimensione != f.file.length() } }
            .sortedByDescending { it.file.lastModified() }
        for ((i, fv) in daFareV.withIndex()) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            stato.value = Avanzamento(true, "video", i, daFareV.size, fv.file.name)
            runCatching { analizzaVideo(fv) }
        }
        if (daFare.isNotEmpty() || rimosse.isNotEmpty() || daFareV.isNotEmpty() || rimossiV.isNotEmpty()) {
            stato.value = Avanzamento(true, "volti")
            Raggruppa.esegui()
        }
        // foto indicizzate con la versione precedente: aggiunge ricerca libera ed etichette oggetti
        if (Clip.disponibile) {
            val mancanti = Db.senzaClip()
            val n = AtomicInteger(0)
            stato.value = Avanzamento(true, "clip", 0, mancanti.size)
            withContext(pool) {
                mancanti.chunked(64).forEach { blocco ->
                    if (!isActive) return@withContext
                    blocco.map { (id, percorso) ->
                        async {
                            val e = runCatching { Immagini.leggi(File(percorso), 640)?.let { Clip.immagine(it) } }.getOrNull()
                            Db.salvaClip(id, e, e?.let { runCatching { Clip.etichette(it) }.getOrNull() } ?: emptyMap())
                            val k = n.incrementAndGet()
                            if (k % 4 == 0 || k == mancanti.size) stato.value = Avanzamento(true, "clip", k, mancanti.size)
                        }
                    }.awaitAll()
                }
            }
        }
        ultimoDalIndice = dalIndice.get()
        // indice sul disco aggiornato per l'altro dispositivo
        if (kotlinx.coroutines.currentCoroutineContext().isActive) aggiornaPortatili(raggiungibili)
    }

    /** foto riprese dall'indice sul disco nell'ultimo aggiornamento */
    @Volatile var ultimoDalIndice = 0

    /** Aggiorna l'indice portatile delle cartelle raggiungibili, solo se qualcosa è cambiato */
    fun aggiornaPortatili(raggiungibili: Collection<String>, forza: Boolean = false): Int {
        var n = 0
        for (c in raggiungibili) {
            if (!forza && !Impostazioni.portatile(c)) continue
            val firma = Portatile.firma(c)
            if (!forza && firma == Impostazioni.firmaPortatile(c) && Portatile.file(java.io.File(c)).isFile) continue
            stato.value = Avanzamento(true, "portatile", 0, 0, java.io.File(c).name)
            runCatching { Portatile.scrivi(c); Impostazioni.setFirmaPortatile(c, firma); n++ }
        }
        return n
    }

    /** Estrae i fotogrammi chiave di un video, li analizza come foto e scarta quelli quasi uguali al precedente */
    private suspend fun analizzaVideo(fv: FileFoto) {
        val f = fv.file
        val info = Video.info(f) ?: return
        val tmp = java.nio.file.Files.createTempDirectory("cercafoto_video").toFile()
        try {
            val quadri = Video.fotogrammi(f, tmp, info.durataMs, Video.intervallo(info.durataMs))
            val inizio = info.creazione ?: f.lastModified()
            val vid = Db.salvaVideo(f.absolutePath, fv.cartella, f.name, f.parentFile?.name ?: "", inizio, f.lastModified(), f.length(), info.durataMs, info.larg, info.alt)
            if (quadri.isEmpty()) return
            val risultati = withContext(pool) {
                quadri.map { (img, ms) -> async { Triple(img, ms, runCatching { Analisi.analizza(img, 1600) }.getOrNull()) } }.awaitAll()
            }
            var precedente: Analisi.Risultato? = null
            var salvati = 0
            for ((img, ms, r) in risultati) {
                if (r == null) continue
                val pc = precedente?.clip; val c = r.clip
                // scena praticamente identica alla precedente e stesso numero di volti: non serve tenerla
                if (pc != null && c != null && pc.size == c.size && c.isNotEmpty() && dot(pc, c) > 0.95f && r.volti.size == precedente!!.volti.size) continue
                val id = Db.salva("${f.absolutePath}#t=$ms", fv.cartella, f.name, f.parentFile?.name ?: "", inizio + ms, f.lastModified(), f.length(),
                    r.volti, r.sorriso, r.colori, r.clip ?: (if (Clip.disponibile) FloatArray(0) else null), r.etichette)
                Db.segnaFotogramma(id, vid, ms)
                Percorsi.miniatura(id).writeBytes(r.miniatura)
                runCatching { img.copyTo(Percorsi.fotogramma(id), overwrite = true) }
                precedente = r; salvati++
            }
            Db.chiudiVideo(vid, salvati)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Rianalisi in alta risoluzione delle foto con volti non assegnati a persone con nome */
    private suspend fun rianalizzaVolti() {
        val lista = ArrayList<Pair<Long, String>>()
        Db.q("SELECT DISTINCT f.id, f.percorso, f.video_id FROM volto v JOIN foto f ON f.id = v.foto_id WHERE v.manuale = 0 AND v.persona_id <> ${Db.ESCLUSO} AND " +
            "v.persona_id NOT IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')") {
            val id = it.getLong(1); lista += id to (if (it.getLong(3) > 0) Percorsi.fotogramma(id).absolutePath else it.getString(2))
        }
        val fatte = AtomicInteger(0)
        stato.value = Avanzamento(true, "volti_hd", 0, lista.size)
        withContext(pool) {
            lista.chunked(32).forEach { blocco ->
                if (!isActive) return@withContext
                blocco.map { (id, percorso) ->
                    async {
                        runCatching {
                            val img = Immagini.leggi(File(percorso), 2560) ?: return@runCatching
                            val volti = Modelli.rileva(img, 0.6f).mapNotNull { b -> Analisi.volto(img, b[0], b[1], b[2], b[3]) }
                            Raggruppa.sostituisciVolti(id, volti)
                        }
                        val n = fatte.incrementAndGet()
                        if (n % 4 == 0 || n == lista.size) stato.value = Avanzamento(true, "volti_hd", n, lista.size)
                    }
                }.awaitAll()
            }
        }
        stato.value = Avanzamento(true, "volti")
        Raggruppa.esegui()
    }
}
