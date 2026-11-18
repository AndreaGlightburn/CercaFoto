package eu.iz2oii.cercafoto

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/** Cartella dei dati dell'app: %LOCALAPPDATA%\CercaFoto (o ~/.cercafoto) */
object Percorsi {
    val base: File = (System.getenv("LOCALAPPDATA")?.let { File(it, "CercaFoto") } ?: File(System.getProperty("user.home"), ".cercafoto")).apply { mkdirs() }
    val miniature = File(base, "miniature").apply { mkdirs() }
    val volti = File(base, "volti").apply { mkdirs() }
    fun miniatura(id: Long) = File(miniature, "$id.jpg")
    fun volto(id: Long) = File(volti, "$id.jpg")
}

data class VoltoAttr(val persona: Long, val eta: Float, val maschio: Float, val capelli: String?, val abiti: String?, val area: Float = 1f) {
    val affidabile: Boolean get() = area >= 0.003f
}

data class FotoIdx(
    val id: Long,
    val data: Long,
    val nome: String,
    val album: String,
    val etichette: Map<String, Int>,
    val volti: Int,
    val sorriso: Float,
    val testo: String,
    val testoOriginale: String,
    val persone: Set<Long>,
    val colori: Map<String, Int> = emptyMap(),
    val attr: List<VoltoAttr> = emptyList(),
    val percorso: String = "",
    val cartella: String? = null,
    val manuali: List<String> = emptyList(),
    val rimosse: Set<String> = emptySet(),
) {
    val testoManuali: String = Testo.normalizza(manuali.joinToString(" "))
    val file: File get() = File(percorso)
}

data class Persona(val id: Long, val nome: String?, val foto: Int, val nascosta: Boolean, val coverVolto: Long, val coverFoto: Long)

data class VoltoInfo(val id: Long, val persona: Long, val l: Float, val t: Float, val r: Float, val b: Float, val conVettore: Boolean, val manuale: Boolean)

fun FloatArray.toBlob(): ByteArray {
    val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in this) bb.putFloat(v)
    return bb.array()
}

fun ByteArray.toFloats(): FloatArray {
    val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { bb.getFloat() }
}

fun normalizza(v: FloatArray): FloatArray {
    var n = 0f
    for (x in v) n += x * x
    n = kotlin.math.sqrt(n)
    if (n > 0f) for (i in v.indices) v[i] /= n
    return v
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** Indice SQLite (stesso schema logico dell'app Android) */
object Db {
    const val ESCLUSO = -2L
    private val c: Connection = DriverManager.getConnection("jdbc:sqlite:" + File(Percorsi.base, "indice.db").absolutePath)

    init {
        c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE IF NOT EXISTS foto(id INTEGER PRIMARY KEY AUTOINCREMENT, percorso TEXT UNIQUE, cartella TEXT, nome TEXT, album TEXT, data INTEGER, modificata INTEGER, dimensione INTEGER, volti INTEGER, sorriso REAL, colori TEXT, manuali TEXT, rimosse TEXT, sha TEXT)")
            s.execute("CREATE TABLE IF NOT EXISTS volto(id INTEGER PRIMARY KEY AUTOINCREMENT, foto_id INTEGER, l REAL, t REAL, r REAL, b REAL, area REAL, emb BLOB, persona_id INTEGER DEFAULT 0, eta REAL, maschio REAL, capelli TEXT, abiti TEXT, manuale INTEGER DEFAULT 0)")
            s.execute("CREATE INDEX IF NOT EXISTS volto_foto ON volto(foto_id)")
            s.execute("CREATE INDEX IF NOT EXISTS volto_persona ON volto(persona_id)")
            s.execute("CREATE TABLE IF NOT EXISTS persona(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT, copertina INTEGER, foto INTEGER DEFAULT 0, nascosta INTEGER DEFAULT 0, uid TEXT)")
            // v2: ricerca libera (vettore CLIP) ed etichette automatiche
            for (col in listOf("clip BLOB", "etichette TEXT")) runCatching { s.execute("ALTER TABLE foto ADD COLUMN $col") }
        }
    }

    // ------------------------------------------------------------ utilità JDBC

    private fun PreparedStatement.lega(a: Array<out Any?>) {
        a.forEachIndexed { i, v ->
            when (v) {
                null -> setObject(i + 1, null)
                is Long -> setLong(i + 1, v); is Int -> setInt(i + 1, v)
                is Float -> setFloat(i + 1, v); is Double -> setDouble(i + 1, v)
                is Boolean -> setInt(i + 1, if (v) 1 else 0)
                is ByteArray -> setBytes(i + 1, v)
                else -> setString(i + 1, v.toString())
            }
        }
    }

    @Synchronized fun q(sql: String, vararg a: Any?, riga: (ResultSet) -> Unit) {
        c.prepareStatement(sql).use { p -> p.lega(a); p.executeQuery().use { r -> while (r.next()) riga(r) } }
    }

    @Synchronized fun u(sql: String, vararg a: Any?): Int = c.prepareStatement(sql).use { p -> p.lega(a); p.executeUpdate() }

    @Synchronized fun ins(sql: String, vararg a: Any?): Long =
        c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { p ->
            p.lega(a); p.executeUpdate(); p.generatedKeys.use { k -> if (k.next()) k.getLong(1) else -1L }
        }

    fun n(sql: String, vararg a: Any?): Long { var x = 0L; q(sql, *a) { x = it.getLong(1) }; return x }

    @Synchronized fun <T> tx(blocco: () -> T): T {
        c.autoCommit = false
        try { val r = blocco(); c.commit(); return r }
        catch (e: Exception) { c.rollback(); throw e }
        finally { c.autoCommit = true }
    }

    private fun ResultSet.floatN(i: Int): Float? { val v = getFloat(i); return if (wasNull()) null else v }

    fun decodifica(s: String?): Map<String, Int> {
        val m = HashMap<String, Int>()
        s?.split(';')?.forEach { x -> val i = x.lastIndexOf(':'); if (i > 0) m[x.substring(0, i)] = x.substring(i + 1).toIntOrNull() ?: 0 }
        return m
    }

    // ------------------------------------------------------------ indicizzazione

    class Stato(val id: Long, val modificata: Long, val dimensione: Long, val cartella: String?)

    fun stati(): HashMap<String, Stato> {
        val m = HashMap<String, Stato>()
        q("SELECT percorso, id, modificata, dimensione, cartella FROM foto") { m[it.getString(1)] = Stato(it.getLong(2), it.getLong(3), it.getLong(4), it.getString(5)) }
        return m
    }

    fun rimuovi(ids: Collection<Long>) = tx {
        for (id in ids) {
            q("SELECT id FROM volto WHERE foto_id = ?", id) { Percorsi.volto(it.getLong(1)).delete() }
            u("DELETE FROM volto WHERE foto_id = ?", id); u("DELETE FROM foto WHERE id = ?", id)
            Percorsi.miniatura(id).delete()
        }
        ricontaInterno()
    }

    class VoltoNuovo(val l: Float, val t: Float, val r: Float, val b: Float, val emb: FloatArray?, val attr: Attributi?, val ritaglio: ByteArray?)

    /** Salva (o aggiorna) una foto analizzata; mantiene etichette manuali e collegamenti a mano. Ritorna l'id. */
    fun salva(percorso: String, cartella: String, nome: String, album: String, data: Long, modificata: Long, dimensione: Long,
              volti: List<VoltoNuovo>, sorriso: Float, colori: Map<String, Int>, clip: FloatArray? = null, etichette: Map<String, Int> = emptyMap()): Long = tx {
        var id = -1L
        q("SELECT id FROM foto WHERE percorso = ?", percorso) { id = it.getLong(1) }
        val colStr = colori.entries.joinToString(";") { "${it.key}:${it.value}" }
        if (id < 0) id = ins("INSERT INTO foto(percorso, cartella, nome, album, data, modificata, dimensione, volti, sorriso, colori) VALUES (?,?,?,?,?,?,?,?,?,?)",
            percorso, cartella, nome, album, data, modificata, dimensione, volti.size, sorriso, colStr)
        else {
            u("UPDATE foto SET cartella=?, nome=?, album=?, data=?, modificata=?, dimensione=?, volti=?, sorriso=?, colori=?, sha=NULL WHERE id=?",
                cartella, nome, album, data, modificata, dimensione, volti.size, sorriso, colStr, id)
            q("SELECT id FROM volto WHERE foto_id = ? AND manuale = 0", id) { Percorsi.volto(it.getLong(1)).delete() }
            u("DELETE FROM volto WHERE foto_id = ? AND manuale = 0", id)
        }
        if (clip != null) u("UPDATE foto SET clip = ?, etichette = ? WHERE id = ?", clip.toBlob(), etichette.entries.joinToString(";") { "${it.key}:${it.value}" }, id)
        for (v in volti) {
            val vid = ins("INSERT INTO volto(foto_id, l, t, r, b, area, emb, persona_id, eta, maschio, capelli, abiti) VALUES (?,?,?,?,?,?,?,0,?,?,?,?)",
                id, v.l, v.t, v.r, v.b, (v.r - v.l) * (v.b - v.t), v.emb?.toBlob(), v.attr?.eta, v.attr?.maschio, v.attr?.capelli, v.attr?.abiti)
            v.ritaglio?.let { runCatching { Percorsi.volto(vid).writeBytes(it) } }
        }
        id
    }

    // ------------------------------------------------------------ lettura

    fun caricaFoto(): List<FotoIdx> {
        val pers = HashMap<Long, MutableSet<Long>>()
        val attr = HashMap<Long, MutableList<VoltoAttr>>()
        q("SELECT foto_id, persona_id, eta, maschio, capelli, abiti, area FROM volto") { r ->
            val f = r.getLong(1); val p = r.getLong(2)
            if (p > 0) pers.getOrPut(f) { mutableSetOf() }.add(p)
            val eta = r.floatN(3)
            if (eta != null) attr.getOrPut(f) { mutableListOf() }.add(VoltoAttr(p, eta, r.getFloat(4), r.getString(5), r.getString(6), r.getFloat(7)))
        }
        val out = ArrayList<FotoIdx>()
        q("SELECT id, data, nome, album, volti, sorriso, colori, percorso, cartella, manuali, rimosse, etichette FROM foto ORDER BY data DESC") { r ->
            val id = r.getLong(1)
            val rimosse = r.getString(11)?.split(';')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            out += FotoIdx(
                id = id, data = r.getLong(2), nome = r.getString(3) ?: "", album = r.getString(4) ?: "",
                etichette = decodifica(r.getString(12)).filterKeys { it !in rimosse },
                volti = r.getInt(5), sorriso = r.getFloat(6), testo = "", testoOriginale = "", persone = pers[id] ?: emptySet(),
                colori = decodifica(r.getString(7)), attr = attr[id] ?: emptyList(), percorso = r.getString(8), cartella = r.getString(9),
                manuali = r.getString(10)?.split(';')?.filter { it.isNotBlank() } ?: emptyList(),
                rimosse = rimosse,
            )
        }
        return out
    }

    /** Vettori CLIP di tutte le foto (per la ricerca libera) */
    fun vettoriClip(): HashMap<Long, FloatArray> {
        val m = HashMap<Long, FloatArray>()
        q("SELECT id, clip FROM foto WHERE clip IS NOT NULL AND length(clip) > 0") { m[it.getLong(1)] = it.getBytes(2).toFloats() }
        return m
    }

    fun senzaClip(): List<Pair<Long, String>> {
        val l = ArrayList<Pair<Long, String>>()
        q("SELECT id, percorso FROM foto WHERE clip IS NULL ORDER BY data DESC") { l += it.getLong(1) to it.getString(2) }
        return l
    }

    fun salvaClip(id: Long, clip: FloatArray?, etichette: Map<String, Int>) {
        // senza vettore (file illeggibile) si salva un vettore vuoto per non riprovare ogni volta
        u("UPDATE foto SET clip = ?, etichette = ? WHERE id = ?", (clip ?: FloatArray(0)).toBlob(), etichette.entries.joinToString(";") { "${it.key}:${it.value}" }, id)
    }

    fun caricaPersone(): List<Persona> {
        val out = ArrayList<Persona>()
        q("SELECT p.id, p.nome, p.foto, p.nascosta, v.id, v.foto_id FROM persona p LEFT JOIN volto v ON v.id = p.copertina ORDER BY (p.nome IS NULL OR p.nome = ''), p.foto DESC") { r ->
            out += Persona(r.getLong(1), r.getString(2)?.takeIf { it.isNotBlank() }, r.getInt(3), r.getInt(4) == 1, r.getLong(5), r.getLong(6))
        }
        return out
    }

    fun conteggi() = Triple(n("SELECT COUNT(*) FROM foto").toInt(), n("SELECT COUNT(*) FROM volto WHERE manuale = 0").toInt(),
        n("SELECT COUNT(*) FROM persona WHERE foto >= 2 OR (nome IS NOT NULL AND nome <> '')").toInt())

    fun voltiFoto(fotoId: Long): List<VoltoInfo> {
        val out = ArrayList<VoltoInfo>()
        q("SELECT id, persona_id, l, t, r, b, emb IS NOT NULL, manuale FROM volto WHERE foto_id = ? ORDER BY manuale, l", fotoId) { r ->
            out += VoltoInfo(r.getLong(1), r.getLong(2), r.getFloat(3), r.getFloat(4), r.getFloat(5), r.getFloat(6), r.getInt(7) == 1, r.getInt(8) == 1)
        }
        return out
    }

    fun vettoreVolto(id: Long): FloatArray? { var v: FloatArray? = null; q("SELECT emb FROM volto WHERE id = ? AND emb IS NOT NULL", id) { v = it.getBytes(1).toFloats() }; return v }

    fun centri(): HashMap<Long, FloatArray> {
        val somme = HashMap<Long, FloatArray>()
        q("SELECT persona_id, emb FROM volto WHERE persona_id > 0 AND emb IS NOT NULL") { r ->
            val e = r.getBytes(2).toFloats()
            val s = somme.getOrPut(r.getLong(1)) { FloatArray(e.size) }
            for (i in e.indices) s[i] += e[i]
        }
        somme.values.forEach { normalizza(it) }
        return somme
    }

    fun vettoriFotoPersona(fotoIds: List<Long>, personaId: Long): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (f in fotoIds) q("SELECT emb FROM volto WHERE foto_id = ? AND persona_id = ? AND emb IS NOT NULL", f, personaId) { out += it.getBytes(1).toFloats() }
        return out
    }

    // ------------------------------------------------------------ persone

    fun ricontaInterno() {
        u("UPDATE persona SET foto = (SELECT COUNT(DISTINCT foto_id) FROM volto WHERE persona_id = persona.id)")
        u("UPDATE persona SET copertina = (SELECT id FROM volto WHERE persona_id = persona.id ORDER BY manuale, area DESC LIMIT 1) WHERE copertina IS NULL OR copertina NOT IN (SELECT id FROM volto WHERE persona_id = persona.id)")
        u("DELETE FROM persona WHERE foto = 0 AND (nome IS NULL OR nome = '')")
    }

    fun rinomina(id: Long, nome: String) { u("UPDATE persona SET nome = ? WHERE id = ?", nome.trim().ifBlank { null }, id) }
    fun nascondi(id: Long, s: Boolean) { u("UPDATE persona SET nascosta = ? WHERE id = ?", s, id) }

    fun unisci(dest: Long, src: Long) = tx {
        var nomeSrc: String? = null; var nomeDest: String? = null; var uidSrc: String? = null
        q("SELECT id, nome, uid FROM persona WHERE id IN (?, ?)", dest, src) { if (it.getLong(1) == src) { nomeSrc = it.getString(2); uidSrc = it.getString(3) } else nomeDest = it.getString(2) }
        u("UPDATE volto SET persona_id = ? WHERE persona_id = ?", dest, src)
        if (nomeDest.isNullOrBlank() && !nomeSrc.isNullOrBlank()) u("UPDATE persona SET nome = ? WHERE id = ?", nomeSrc, dest)
        if (uidSrc != null) u("UPDATE persona SET uid = ? WHERE id = ? AND (uid IS NULL OR uid = '')", uidSrc, dest)
        u("DELETE FROM persona WHERE id = ?", src)
        ricontaInterno()
    }

    fun escludi(fotoId: Long, personaId: Long) = tx {
        u("DELETE FROM volto WHERE foto_id = ? AND persona_id = ? AND manuale = 1", fotoId, personaId)
        u("UPDATE volto SET persona_id = ? WHERE foto_id = ? AND persona_id = ?", ESCLUSO, fotoId, personaId)
        ricontaInterno()
    }

    fun assegnaVolto(voltoId: Long, personaId: Long) = tx { u("UPDATE volto SET persona_id = ? WHERE id = ?", personaId, voltoId); ricontaInterno() }

    fun nuovaPersona(nome: String): Long = ins("INSERT INTO persona(nome) VALUES (?)", nome.trim())

    fun nuovaPersonaDaVolto(voltoId: Long, nome: String) = tx {
        val id = nuovaPersona(nome)
        u("UPDATE volto SET persona_id = ? WHERE id = ?", id, voltoId); u("UPDATE persona SET copertina = ? WHERE id = ?", voltoId, id)
        ricontaInterno()
    }

    private fun inserisciManuale(fotoId: Long, personaId: Long) {
        ins("INSERT INTO volto(foto_id, l, t, r, b, area, persona_id, manuale) VALUES (?, 0.05, 0.05, 0.95, 0.95, 0, ?, 1)", fotoId, personaId)
    }

    fun associa(fotoIds: List<Long>, personaId: Long): Int = tx {
        var k = 0
        for (f in fotoIds) if (n("SELECT COUNT(*) FROM volto WHERE foto_id = ? AND persona_id = ?", f, personaId) == 0L) { inserisciManuale(f, personaId); k++ }
        ricontaInterno(); k
    }

    fun rimuoviVolto(voltoId: Long) = tx { u("DELETE FROM volto WHERE id = ?", voltoId); ricontaInterno() }

    fun spostaFoto(fotoIds: List<Long>, da: Long, a: Long) = tx {
        for (f in fotoIds) {
            if (n("SELECT COUNT(*) FROM volto WHERE foto_id = ? AND persona_id = ?", f, a) > 0)
                u("DELETE FROM volto WHERE foto_id = ? AND persona_id = ? AND manuale = 1", f, da)
            u("UPDATE volto SET persona_id = ? WHERE foto_id = ? AND persona_id = ?", a, f, da)
        }
        ricontaInterno()
    }

    fun etichetteManuali(fotoId: Long, manuali: List<String>, rimosse: Set<String>) {
        u("UPDATE foto SET manuali = ?, rimosse = ? WHERE id = ?",
            manuali.map { it.replace(";", ",").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(";"), rimosse.joinToString(";"), fotoId)
    }

    fun azzera() = tx {
        u("DELETE FROM volto"); u("DELETE FROM foto"); u("DELETE FROM persona")
        Percorsi.miniature.listFiles()?.forEach { it.delete() }; Percorsi.volti.listFiles()?.forEach { it.delete() }
    }
}
