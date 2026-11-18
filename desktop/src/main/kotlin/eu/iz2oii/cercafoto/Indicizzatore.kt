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
    var soglia: Float
        get() = p.getProperty("soglia", "0.5").toFloatOrNull() ?: 0.5f
        set(v) { p.setProperty("soglia", v.toString()); salva() }
}

data class Avanzamento(val attivo: Boolean = false, val fase: String = "", val fatte: Int = 0, val totale: Int = 0)

object Indicizzatore {
    private val ESTENSIONI = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
    val stato = MutableStateFlow(Avanzamento())
    private var lavoro: Job? = null
    private val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2)).asCoroutineDispatcher()

    class FileFoto(val file: File, val cartella: String)

    fun elenca(cartelle: List<String>): Pair<List<FileFoto>, Set<String>> {
        val out = ArrayList<FileFoto>()
        val raggiungibili = HashSet<String>()
        for (c in cartelle) {
            val radice = File(c)
            if (!radice.isDirectory) continue
            raggiungibili += c
            radice.walkTopDown().onEnter { !it.name.startsWith(".") && !it.isHidden || it == radice }
                .filter { it.isFile && it.extension.lowercase() in ESTENSIONI && !it.name.startsWith(".") }
                .forEach { out += FileFoto(it, c) }
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
        val (file, raggiungibili) = elenca(cartelle)
        val noti = Db.stati()
        val presenti = file.mapTo(HashSet()) { it.file.absolutePath }
        // via solo le foto sparite da cartelle raggiungibili (o da cartelle tolte dall'elenco)
        val rimosse = noti.filter { (p, s) -> p !in presenti && (s.cartella == null || s.cartella in raggiungibili || s.cartella !in cartelle) }.map { it.value.id }
        Db.rimuovi(rimosse)
        val daFare = file.filter { f -> noti[f.file.absolutePath].let { it == null || it.modificata != f.file.lastModified() || it.dimensione != f.file.length() } }
            .sortedByDescending { it.file.lastModified() }
        val fatte = AtomicInteger(0)
        stato.value = Avanzamento(true, "analisi", 0, daFare.size)
        withContext(pool) {
            daFare.chunked(64).forEach { blocco ->
                if (!isActive) return@withContext
                blocco.map { ff ->
                    async {
                        val f = ff.file
                        runCatching {
                            val r = Analisi.analizza(f)
                            val data = Immagini.dataScatto(f) ?: f.lastModified()
                            val id = Db.salva(f.absolutePath, ff.cartella, f.name, f.parentFile?.name ?: "", data, f.lastModified(), f.length(),
                                r?.volti ?: emptyList(), r?.sorriso ?: 0f, r?.colori ?: emptyMap())
                            r?.miniatura?.let { Percorsi.miniatura(id).writeBytes(it) }
                        }
                        val n = fatte.incrementAndGet()
                        if (n % 4 == 0 || n == daFare.size) stato.value = Avanzamento(true, "analisi", n, daFare.size)
                    }
                }.awaitAll()
            }
        }
        if (daFare.isNotEmpty() || rimosse.isNotEmpty()) {
            stato.value = Avanzamento(true, "volti")
            Raggruppa.esegui()
        }
    }

    /** Rianalisi in alta risoluzione delle foto con volti non assegnati a persone con nome */
    private suspend fun rianalizzaVolti() {
        val lista = ArrayList<Pair<Long, String>>()
        Db.q("SELECT DISTINCT f.id, f.percorso FROM volto v JOIN foto f ON f.id = v.foto_id WHERE v.manuale = 0 AND v.persona_id <> ${Db.ESCLUSO} AND " +
            "v.persona_id NOT IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')") { lista += it.getLong(1) to it.getString(2) }
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
