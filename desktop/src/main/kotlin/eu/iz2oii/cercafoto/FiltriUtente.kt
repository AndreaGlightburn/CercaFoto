package eu.iz2oii.cercafoto

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * Filtri personalizzati, calcolati in locale sui vettori CLIP delle foto:
 *  - "esempi": classificatore addestrato sulle foto scelte (positive) e su quelle escluse (negative);
 *  - "descrizione": somiglianza con una o più frasi.
 * Un filtro è solo una manciata di numeri (512 pesi): si esporta e importa senza foto.
 */
data class FiltroUtente(
    val id: Long,
    val nome: String,
    val tipo: String,              // "esempi" | "descrizione"
    val soglia: Float,             // esempi: probabilità 0..1; descrizione: somiglianza
    val pesi: FloatArray,          // 512 (esempi: pesi del classificatore; descrizione: vettore medio delle frasi)
    val bias: Float,
    val frasi: List<String>,
    val positivi: Set<Long>,
    val negativi: Set<Long>,
    /** cerca anche nelle zone delle foto (oggetti piccoli) */
    val dettagli: Boolean = false,
    val zoneGiuste: Int = 0,
    val zoneSbagliate: Int = 0,
) {
    val esempi get() = tipo == "esempi"
    /** punteggio considerando anche la zona migliore della foto */
    fun punteggio(v: FloatArray, z: Zone?): Float {
        val intero = punteggio(v)
        if (!dettagli || z == null || z.n == 0 || pesi.size != 512) return intero
        val m = z.massimo(pesi) + bias
        val zona = if (esempi) (1f / (1f + exp(-m))) else m
        return maxOf(intero, zona)
    }
    fun punteggio(v: FloatArray): Float {
        if (v.size != pesi.size) return 0f
        val s = dot(pesi, v) + bias
        return if (esempi) (1f / (1f + exp(-s))) else s
    }
    /** intervallo del cursore di sensibilità */
    val minimo get() = if (esempi) 0.05f else 0.15f
    val massimo get() = if (esempi) 0.98f else 0.35f
}

object FiltriUtente {
    const val MODELLO = "clip-vit-b32-multilingual-v1"

    init {
        Db.u("CREATE TABLE IF NOT EXISTS filtro(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT, tipo TEXT, soglia REAL, pesi BLOB, bias REAL, frasi TEXT, positivi TEXT, negativi TEXT)")
        runCatching { Db.u("ALTER TABLE filtro ADD COLUMN dettagli INTEGER DEFAULT 0") }
    }

    private fun ids(s: String?) = s?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()

    fun tutti(): List<FiltroUtente> {
        val out = ArrayList<FiltroUtente>()
        val zg = HashMap<Long, Int>(); val zs = HashMap<Long, Int>()
        Db.q("SELECT filtro_id, giusto, COUNT(*) FROM esempio_zona GROUP BY filtro_id, giusto") { r -> (if (r.getInt(2) == 1) zg else zs)[r.getLong(1)] = r.getInt(3) }
        Db.q("SELECT id, nome, tipo, soglia, pesi, bias, frasi, positivi, negativi, dettagli FROM filtro ORDER BY nome COLLATE NOCASE") { r ->
            val id = r.getLong(1)
            out += FiltroUtente(id, r.getString(2), r.getString(3), r.getFloat(4), r.getBytes(5)?.toFloats() ?: FloatArray(0), r.getFloat(6),
                r.getString(7)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList(), ids(r.getString(8)), ids(r.getString(9)),
                r.getInt(10) == 1, zg[id] ?: 0, zs[id] ?: 0)
        }
        return out
    }

    private fun salva(f: FiltroUtente): Long {
        val args = arrayOf<Any?>(f.nome.trim(), f.tipo, f.soglia, f.pesi.toBlob(), f.bias, f.frasi.joinToString("\n"), f.positivi.joinToString(","), f.negativi.joinToString(","), f.dettagli)
        return if (f.id > 0) { Db.u("UPDATE filtro SET nome=?, tipo=?, soglia=?, pesi=?, bias=?, frasi=?, positivi=?, negativi=?, dettagli=? WHERE id=?", *args, f.id); f.id }
        else Db.ins("INSERT INTO filtro(nome, tipo, soglia, pesi, bias, frasi, positivi, negativi, dettagli) VALUES (?,?,?,?,?,?,?,?,?)", *args)
    }

    fun elimina(id: Long) { Db.u("DELETE FROM filtro WHERE id = ?", id); Db.eliminaEsempiZona(id) }
    fun impostaDettagli(id: Long, v: Boolean) { Db.u("UPDATE filtro SET dettagli = ? WHERE id = ?", v, id) }
    fun rinomina(id: Long, nome: String) { Db.u("UPDATE filtro SET nome = ? WHERE id = ?", nome.trim(), id) }
    fun impostaSoglia(id: Long, s: Float) { Db.u("UPDATE filtro SET soglia = ? WHERE id = ?", s, id) }

    // ------------------------------------------------------------ addestramento

    /**
     * Regressione logistica sui vettori CLIP (512).
     * Negativi = foto escluse a mano (peso alto) + un campione casuale delle altre foto (peso basso: quasi tutte non c'entrano).
     */
    fun addestra(positivi: List<FloatArray>, negativiScelti: List<FloatArray>, sfondo: List<FloatArray>): Pair<FloatArray, Float> {
        val d = positivi.first().size
        val x = ArrayList<FloatArray>(); val y = ArrayList<Float>(); val pesoEs = ArrayList<Float>()
        val wp = 1f / positivi.size; val wn = if (negativiScelti.isEmpty()) 0f else 1f / negativiScelti.size; val ws = if (sfondo.isEmpty()) 0f else 1f / sfondo.size
        positivi.forEach { x += it; y += 1f; pesoEs += wp * 1.0f }
        negativiScelti.forEach { x += it; y += 0f; pesoEs += wn * 0.6f }
        sfondo.forEach { x += it; y += 0f; pesoEs += ws * (if (negativiScelti.isEmpty()) 1.0f else 0.4f) }
        // normalizza i pesi dei campioni in modo che positivi e negativi pesino uguale
        val totNeg = pesoEs.indices.filter { y[it] == 0f }.sumOf { pesoEs[it].toDouble() }.toFloat()
        if (totNeg > 0f) for (i in pesoEs.indices) if (y[i] == 0f) pesoEs[i] /= totNeg
        val w = FloatArray(d); var b = 0f
        val lr = 2.0f; val l2 = 0.002f
        repeat(400) {
            val gw = FloatArray(d); var gb = 0f
            for (i in x.indices) {
                val p = 1f / (1f + exp(-(dot(w, x[i]) + b)))
                val e = (p - y[i]) * pesoEs[i]
                val xi = x[i]
                for (k in 0 until d) gw[k] += e * xi[k]
                gb += e
            }
            for (k in 0 until d) w[k] -= lr * (gw[k] / 2f + l2 * w[k])
            b -= lr * gb / 2f
        }
        return w to b
    }

    /** Crea o riaddestra un filtro da esempi. [vettori] = vettori CLIP di tutte le foto. */
    fun daEsempi(id: Long, nome: String, positivi: Set<Long>, negativi: Set<Long>, vettori: Map<Long, FloatArray>, soglia: Float = 0.28f,
                 dettagli: Boolean? = null, zone: Map<Long, Zone> = Stato.zone()): Long {
        // esempi a zona (riquadri scelti nel visore) del filtro
        val ez = if (id > 0) Db.esempiZona(id) else emptyList()
        val det = dettagli ?: (ez.isNotEmpty() || (id > 0 && tutti().firstOrNull { it.id == id }?.dettagli == true))
        val pos = positivi.mapNotNull { vettori[it] } + ez.filter { it.giusto }.map { it.vettore }
        require(pos.size >= 2 || (ez.any { it.giusto } && pos.isNotEmpty())) { "Servono almeno 2 foto di esempio già analizzate (o una zona scelta nel visore)" }
        val neg = negativi.mapNotNull { vettori[it] } + ez.filter { !it.giusto }.map { it.vettore }
        val rnd = Random(42)
        val casuali = vettori.keys.filter { it !in positivi && it !in negativi }.shuffled(rnd)
        val altri = casuali.take(600).mapNotNull { vettori[it] }.toMutableList()
        // per un filtro di dettaglio anche le zone delle altre foto devono risultare "non è questo"
        if (det) casuali.take(600).forEach { k -> zone[k]?.takeIf { it.n > 0 }?.let { z -> altri += z.vettore(rnd.nextInt(z.n)) } }
        val (w, b) = addestra(pos, neg, altri)
        return salva(FiltroUtente(id, nome, "esempi", soglia, w, b, emptyList(), positivi, negativi, det))
    }

    /** Nuovo filtro partendo da una zona scelta nel visore */
    fun nuovoDaZona(nome: String, foto: Long, l: Float, t: Float, r: Float, b: Float, v: FloatArray, vettori: Map<Long, FloatArray>): Long {
        // sensibilità iniziale tarata su ImageNet con oggetti piccoli incollati (0.22: ~2/3 trovati, pochi intrusi)
        val id = salva(FiltroUtente(0, nome, "esempi", 0.22f, FloatArray(0), 0f, emptyList(), emptySet(), emptySet(), true))
        Db.nuovoEsempioZona(id, foto, l, t, r, b, true, v)
        return runCatching { daEsempi(id, nome, emptySet(), emptySet(), vettori, 0.22f, dettagli = true) }.getOrElse { elimina(id); throw it }
    }

    fun daDescrizione(id: Long, nome: String, frasi: List<String>, soglia: Float = 0.22f): Long {
        val f = frasi.map { it.trim() }.filter { it.isNotEmpty() }
        require(f.isNotEmpty()) { "Scrivi almeno una frase" }
        val v = FloatArray(512)
        Clip.testo(f).forEach { e -> for (i in v.indices) v[i] += e[i] }
        normalizza(v)
        return salva(FiltroUtente(id, nome, "descrizione", soglia, v, 0f, f, emptySet(), emptySet()))
    }

    /** Foto che rientrano nel filtro, dalla più pertinente */
    fun membri(f: FiltroUtente, vettori: Map<Long, FloatArray>, zone: Map<Long, Zone> = Stato.zone()): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        for ((id, v) in vettori) {
            if (id in f.negativi) continue
            val p = f.punteggio(v, zone[id])
            val s = if (id in f.positivi) 1f + p else p
            if (id in f.positivi || s >= f.soglia) out += id to s
        }
        out.sortByDescending { it.second }
        return out
    }

    // ------------------------------------------------------------ esporta / importa (.cercafiltro)

    fun esporta(f: FiltroUtente, file: File) {
        val j = JSONObject()
        j.put("formato", "cercafiltro"); j.put("versione", 1); j.put("modello", MODELLO)
        j.put("nome", f.nome); j.put("tipo", f.tipo); j.put("soglia", f.soglia.toDouble()); j.put("bias", f.bias.toDouble())
        j.put("pesi", ScambioFormato.vettore(f.pesi)); j.put("frasi", JSONArray(f.frasi)); j.put("dettagli", f.dettagli)
        file.writeText(j.toString(1), Charsets.UTF_8)
    }

    fun importa(file: File): Long {
        val j = JSONObject(file.readText(Charsets.UTF_8))
        require(j.optString("formato") == "cercafiltro") { "Non è un filtro di Cerca Foto" }
        require(j.optString("modello") == MODELLO) { "Filtro creato per un altro modello (${j.optString("modello")})" }
        val frasi = j.optJSONArray("frasi")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        var nome = j.getString("nome")
        val esistenti = tutti().map { it.nome.lowercase() }.toSet()
        var k = 2; val base = nome
        while (nome.lowercase() in esistenti) nome = "$base (${k++})"
        return salva(FiltroUtente(0, nome, j.getString("tipo"), j.getDouble("soglia").toFloat(), ScambioFormato.vettore(j.getString("pesi")),
            j.optDouble("bias", 0.0).toFloat(), frasi, emptySet(), emptySet(), j.optBoolean("dettagli")))
            .also { if (j.optBoolean("dettagli")) Impostazioni.zone = true }
    }
}
