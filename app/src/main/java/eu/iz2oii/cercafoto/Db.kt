package eu.iz2oii.cercafoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Foto indicizzata, in forma leggera per la ricerca in memoria. */
/** Attributi stimati di un volto (età, sesso apparente, colore capelli e abiti) */
data class VoltoAttr(val persona: Long, val eta: Float, val maschio: Float, val capelli: String?, val abiti: String?, val area: Float = 1f) {
    /** Volto abbastanza grande (circa 70 px su 1280) perché età e sesso siano attendibili */
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
    val testo: String,          // testo letto, normalizzato (minuscolo, senza accenti)
    val testoOriginale: String,
    val persone: Set<Long>,
    val colori: Map<String, Int> = emptyMap(),
    val attr: List<VoltoAttr> = emptyList(),
    val uri: Uri = uriFoto(id),
    val cartella: String? = null,   // null = galleria di Android, altrimenti URI della cartella esterna
    val manuali: List<String> = emptyList(),   // etichette aggiunte a mano (testo libero)
    val rimosse: Set<String> = emptySet(),     // etichette automatiche tolte a mano (nomi EN)
) {
    val testoManuali: String = Testo.normalizza(manuali.joinToString(" "))
}

data class Persona(
    val id: Long,
    val nome: String?,
    val foto: Int,
    val nascosta: Boolean,
    val coverVolto: Long,
    val coverFoto: Long,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val coverUri: Uri,
)

/** Un volto di una foto, per il visore */
data class VoltoInfo(val id: Long, val persona: Long, val l: Float, val t: Float, val r: Float, val b: Float, val conVettore: Boolean, val manuale: Boolean = false)

fun uriFoto(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

fun FloatArray.toBlob(): ByteArray {
    val bb = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in this) bb.putFloat(v)
    return bb.array()
}

fun ByteArray.toFloats(): FloatArray {
    val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { bb.getFloat() }
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "indice.db", null, 3) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        const val ESCLUSO = -2L
        const val VERSIONE = 2

        fun uriDi(id: Long, s: String?): Uri = if (s.isNullOrEmpty()) uriFoto(id) else Uri.parse(s)

        fun codificaColori(m: Map<String, Int>) = m.entries.joinToString(";") { "${it.key}:${it.value}" }
        fun decodifica(s: String?): Map<String, Int> {
            val m = HashMap<String, Int>()
            s?.split(';')?.forEach { x ->
                val i = x.lastIndexOf(':')
                if (i > 0) m[x.substring(0, i)] = x.substring(i + 1).toIntOrNull() ?: 0
            }
            return m
        }
    }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE foto(id INTEGER PRIMARY KEY, data INTEGER, modificata INTEGER, nome TEXT, album TEXT, etichette TEXT, volti INTEGER, sorriso REAL, testo TEXT)")
        db.execSQL("CREATE TABLE volto(id INTEGER PRIMARY KEY AUTOINCREMENT, foto_id INTEGER, l REAL, t REAL, r REAL, b REAL, area REAL, emb BLOB, persona_id INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX volto_foto ON volto(foto_id)")
        db.execSQL("CREATE INDEX volto_persona ON volto(persona_id)")
        db.execSQL("CREATE TABLE persona(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT, copertina INTEGER, foto INTEGER DEFAULT 0, nascosta INTEGER DEFAULT 0)")
        onUpgrade(db, 1, 3)
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE foto ADD COLUMN colori TEXT")
            db.execSQL("ALTER TABLE foto ADD COLUMN versione INTEGER DEFAULT 1")
            db.execSQL("ALTER TABLE volto ADD COLUMN eta REAL")
            db.execSQL("ALTER TABLE volto ADD COLUMN maschio REAL")
            db.execSQL("ALTER TABLE volto ADD COLUMN capelli TEXT")
            db.execSQL("ALTER TABLE volto ADD COLUMN abiti TEXT")
            db.execSQL("ALTER TABLE foto ADD COLUMN uri TEXT")
            db.execSQL("ALTER TABLE foto ADD COLUMN cartella TEXT")
            db.execSQL("ALTER TABLE foto ADD COLUMN manuali TEXT")
            db.execSQL("ALTER TABLE foto ADD COLUMN rimosse TEXT")
        }
        if (o < 3) {
            // associazioni foto-persona fatte a mano, senza volto visibile
            db.execSQL("ALTER TABLE volto ADD COLUMN manuale INTEGER DEFAULT 0")
        }
    }

    /** Foto analizzate con una versione precedente: mancano colori e attributi dei volti */
    fun daCompletare(): List<Pair<Long, Uri>> {
        val out = ArrayList<Pair<Long, Uri>>()
        readableDatabase.rawQuery("SELECT id, uri FROM foto WHERE versione < $VERSIONE ORDER BY data DESC", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to uriDi(c.getLong(0), c.getString(1))
        }
        return out
    }

    /** id -> cartella (solo foto di cartelle esterne) */
    fun fotoEsterne(): HashMap<Long, String> {
        val m = HashMap<Long, String>()
        readableDatabase.rawQuery("SELECT id, cartella FROM foto WHERE cartella IS NOT NULL", null).use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getString(1)
        }
        return m
    }

    class Box(val id: Long, val l: Float, val t: Float, val r: Float, val b: Float)

    fun voltiDi(fotoId: Long): List<Box> {
        val out = ArrayList<Box>()
        readableDatabase.rawQuery("SELECT id, l, t, r, b FROM volto WHERE foto_id = ?", arrayOf(fotoId.toString())).use { c ->
            while (c.moveToNext()) out += Box(c.getLong(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4))
        }
        return out
    }

    fun completa(fotoId: Long, colori: Map<String, Int>?, attr: List<Pair<Long, Analizzatore.Attributi>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("foto", ContentValues().apply {
                put("colori", colori?.let { codificaColori(it) } ?: "")
                put("versione", VERSIONE)
            }, "id=?", arrayOf(fotoId.toString()))
            for ((id, a) in attr) db.update("volto", a.valori(), "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** id -> data modifica, per sapere cosa è già indicizzato */
    fun modifiche(): HashMap<Long, Long> {
        val m = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT id, modificata FROM foto", null).use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getLong(1)
        }
        return m
    }

    fun rimuovi(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("volto", "foto_id=?", arrayOf(id.toString()))
                db.delete("foto", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun salva(m: MediaFoto, r: Analizzatore.Risultato?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val associate = ArrayList<Long>()
            db.rawQuery("SELECT persona_id FROM volto WHERE foto_id = ? AND manuale = 1", arrayOf(m.id.toString())).use { c ->
                while (c.moveToNext()) associate += c.getLong(0)
            }
            db.delete("volto", "foto_id=?", arrayOf(m.id.toString()))
            for (p in associate) inserisciManuale(db, m.id, p)
            var manuali: String? = null; var rimosse: String? = null
            db.rawQuery("SELECT manuali, rimosse FROM foto WHERE id = ?", arrayOf(m.id.toString())).use { c ->
                if (c.moveToFirst()) { manuali = c.getString(0); rimosse = c.getString(1) }
            }
            val cv = ContentValues().apply {
                put("manuali", manuali); put("rimosse", rimosse)
                put("id", m.id); put("data", r?.dataExif ?: m.data); put("modificata", m.modificata)
                put("uri", m.uri?.toString()); put("cartella", m.cartella)
                put("nome", m.nome); put("album", m.album)
                put("etichette", r?.etichette?.joinToString(";") { "${it.first}:${it.second}" } ?: "")
                put("volti", r?.volti?.size ?: 0)
                put("sorriso", r?.sorriso ?: 0f)
                put("testo", r?.testo ?: "")
                put("colori", r?.colori?.let { codificaColori(it) } ?: "")
                put("versione", VERSIONE)
            }
            db.insertWithOnConflict("foto", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            r?.volti?.forEach { v ->
                db.insert("volto", null, ContentValues().apply {
                    put("foto_id", m.id); put("l", v.l); put("t", v.t); put("r", v.r); put("b", v.b)
                    put("area", (v.r - v.l) * (v.b - v.t))
                    if (v.emb != null) put("emb", v.emb.toBlob())
                    v.attr?.let { putAll(it.valori()) }
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun caricaFoto(): List<FotoIdx> {
        val db = readableDatabase
        val pers = HashMap<Long, MutableSet<Long>>()
        val attr = HashMap<Long, MutableList<VoltoAttr>>()
        db.rawQuery("SELECT foto_id, persona_id, eta, maschio, capelli, abiti, area FROM volto", null).use { c ->
            while (c.moveToNext()) {
                val f = c.getLong(0); val p = c.getLong(1)
                if (p > 0) pers.getOrPut(f) { mutableSetOf() }.add(p)
                if (!c.isNull(2)) attr.getOrPut(f) { mutableListOf() }.add(
                    VoltoAttr(p, c.getFloat(2), c.getFloat(3), c.getString(4), c.getString(5), c.getFloat(6))
                )
            }
        }
        val out = ArrayList<FotoIdx>()
        db.rawQuery("SELECT id, data, nome, album, etichette, volti, sorriso, testo, colori, uri, cartella, manuali, rimosse FROM foto ORDER BY data DESC", null).use { c ->
            while (c.moveToNext()) {
                val rimosse = c.getString(12)?.split(';')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val et = decodifica(c.getString(4)).filterKeys { it !in rimosse }
                val testo = c.getString(7) ?: ""
                out += FotoIdx(
                    id = c.getLong(0), data = c.getLong(1), nome = c.getString(2) ?: "",
                    album = c.getString(3) ?: "", etichette = et, volti = c.getInt(5),
                    sorriso = c.getFloat(6), testo = Testo.normalizza(testo), testoOriginale = testo,
                    persone = pers[c.getLong(0)] ?: emptySet(),
                    colori = decodifica(c.getString(8)),
                    attr = attr[c.getLong(0)] ?: emptyList(),
                    uri = uriDi(c.getLong(0), c.getString(9)),
                    cartella = c.getString(10),
                    manuali = c.getString(11)?.split(';')?.filter { it.isNotBlank() } ?: emptyList(),
                    rimosse = rimosse,
                )
            }
        }
        return out
    }

    fun caricaPersone(): List<Persona> {
        val out = ArrayList<Persona>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.nome, p.foto, p.nascosta, v.id, v.foto_id, v.l, v.t, v.r, v.b, f.uri FROM persona p LEFT JOIN volto v ON v.id = p.copertina LEFT JOIN foto f ON f.id = v.foto_id ORDER BY (p.nome IS NULL OR p.nome = ''), p.foto DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out += Persona(
                c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() }, c.getInt(2), c.getInt(3) == 1,
                c.getLong(4), c.getLong(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9),
                uriDi(c.getLong(5), c.getString(10))
            )
        }
        return out
    }

    fun conteggi(): Triple<Int, Int, Int> {
        val db = readableDatabase
        fun n(q: String) = db.rawQuery(q, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Triple(
            n("SELECT COUNT(*) FROM foto"),
            n("SELECT COUNT(*) FROM volto"),
            n("SELECT COUNT(*) FROM persona WHERE foto >= 2 OR (nome IS NOT NULL AND nome <> '')")
        )
    }

    /** Foto che contengono almeno un volto non assegnato a una persona con nome (esclusi quelli tolti a mano) */
    fun fotoConVoltiSenzaNome(): List<Pair<Long, Uri>> {
        val out = ArrayList<Pair<Long, Uri>>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT f.id, f.uri FROM volto v JOIN foto f ON f.id = v.foto_id WHERE v.manuale = 0 AND v.persona_id <> $ESCLUSO AND " +
                "v.persona_id NOT IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '') ORDER BY f.data DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) to uriDi(c.getLong(0), c.getString(1)) }
        return out
    }

    /**
     * Sostituisce i volti di una foto con quelli di una nuova analisi.
     * Le assegnazioni a persone con nome e le esclusioni manuali passano al nuovo volto nella stessa posizione.
     */
    fun sostituisciVolti(fotoId: Long, volti: List<Analizzatore.Volto>, sorriso: Float) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            class Vecchio(val l: Float, val t: Float, val r: Float, val b: Float, val p: Long)
            val tenere = ArrayList<Vecchio>()
            db.rawQuery(
                "SELECT l, t, r, b, persona_id FROM volto WHERE foto_id = ? AND manuale = 0 AND (persona_id = $ESCLUSO OR persona_id IN " +
                    "(SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> ''))", arrayOf(fotoId.toString())
            ).use { c -> while (c.moveToNext()) tenere += Vecchio(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getLong(4)) }
            fun iou(a: Vecchio, v: Analizzatore.Volto): Float {
                val il = max(a.l, v.l); val itop = max(a.t, v.t); val ir = min(a.r, v.r); val ib = min(a.b, v.b)
                val inter = max(0f, ir - il) * max(0f, ib - itop)
                val uni = (a.r - a.l) * (a.b - a.t) + (v.r - v.l) * (v.b - v.t) - inter
                return if (uni > 0f) inter / uni else 0f
            }
            db.delete("volto", "foto_id=? AND manuale = 0", arrayOf(fotoId.toString()))
            val usati = HashSet<Vecchio>()
            for (v in volti) {
                val match = tenere.filter { it !in usati }.maxByOrNull { iou(it, v) }?.takeIf { iou(it, v) >= 0.35f }
                match?.let { usati += it }
                db.insert("volto", null, ContentValues().apply {
                    put("foto_id", fotoId); put("l", v.l); put("t", v.t); put("r", v.r); put("b", v.b)
                    put("area", (v.r - v.l) * (v.b - v.t))
                    if (v.emb != null) put("emb", v.emb.toBlob())
                    v.attr?.let { putAll(it.valori()) }
                    put("persona_id", match?.p ?: 0L)
                })
            }
            // volti con nome non ritrovati dalla nuova analisi: si tengono com'erano
            for (o in tenere) if (o !in usati) db.insert("volto", null, ContentValues().apply {
                put("foto_id", fotoId); put("l", o.l); put("t", o.t); put("r", o.r); put("b", o.b)
                put("area", (o.r - o.l) * (o.b - o.t)); put("persona_id", o.p)
            })
            db.update("foto", ContentValues().apply {
                put("volti", volti.size.coerceAtLeast(tenere.size)); put("sorriso", sorriso)
            }, "id=?", arrayOf(fotoId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun voltiFoto(fotoId: Long): List<VoltoInfo> {
        val out = ArrayList<VoltoInfo>()
        readableDatabase.rawQuery("SELECT id, persona_id, l, t, r, b, emb IS NOT NULL, manuale FROM volto WHERE foto_id = ? ORDER BY manuale, l", arrayOf(fotoId.toString())).use { c ->
            while (c.moveToNext()) out += VoltoInfo(c.getLong(0), c.getLong(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getInt(6) == 1, c.getInt(7) == 1)
        }
        return out
    }

    fun vettoreVolto(voltoId: Long): FloatArray? =
        readableDatabase.rawQuery("SELECT emb FROM volto WHERE id = ?", arrayOf(voltoId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0).toFloats() else null
        }

    /** Assegna un singolo volto a una persona (o ESCLUSO per toglierlo da ogni gruppo) */
    fun assegnaVolto(voltoId: Long, personaId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("volto", ContentValues().apply { put("persona_id", personaId) }, "id=?", arrayOf(voltoId.toString()))
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Crea una nuova persona con nome a partire da un volto */
    fun nuovaPersonaDaVolto(voltoId: Long, nome: String): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insert("persona", null, ContentValues().apply { put("nome", nome.trim()); put("copertina", voltoId) })
            db.update("volto", ContentValues().apply { put("persona_id", id) }, "id=?", arrayOf(voltoId.toString()))
            ricontaInterno(db)
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    /** Centro (media normalizzata dei vettori dei volti) di ogni persona */
    fun centri(): HashMap<Long, FloatArray> {
        val somme = HashMap<Long, FloatArray>()
        readableDatabase.rawQuery("SELECT persona_id, emb FROM volto WHERE persona_id > 0 AND emb IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val e = c.getBlob(1).toFloats()
                val s = somme.getOrPut(c.getLong(0)) { FloatArray(e.size) }
                for (i in e.indices) s[i] += e[i]
            }
        }
        somme.values.forEach { normalizza(it) }
        return somme
    }

    fun rinomina(id: Long, nome: String) {
        writableDatabase.update("persona", ContentValues().apply { put("nome", nome.trim().ifBlank { null }) }, "id=?", arrayOf(id.toString()))
    }

    fun nascondi(id: Long, s: Boolean) {
        writableDatabase.update("persona", ContentValues().apply { put("nascosta", if (s) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    /** Sposta tutti i volti di [src] dentro [dest]. */
    fun unisci(dest: Long, src: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var nomeSrc: String? = null
            var nomeDest: String? = null
            db.rawQuery("SELECT id, nome FROM persona WHERE id IN (?, ?)", arrayOf(dest.toString(), src.toString())).use { c ->
                while (c.moveToNext()) if (c.getLong(0) == src) nomeSrc = c.getString(1) else nomeDest = c.getString(1)
            }
            db.execSQL("UPDATE volto SET persona_id = ? WHERE persona_id = ?", arrayOf(dest, src))
            if (nomeDest.isNullOrBlank() && !nomeSrc.isNullOrBlank())
                db.update("persona", ContentValues().apply { put("nome", nomeSrc) }, "id=?", arrayOf(dest.toString()))
            db.delete("persona", "id=?", arrayOf(src.toString()))
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** "Non è questa persona": toglie il volto della foto dal gruppo e lo esclude dai raggruppamenti. */
    fun escludi(fotoId: Long, personaId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("volto", "foto_id = ? AND persona_id = ? AND manuale = 1", arrayOf(fotoId.toString(), personaId.toString()))
            db.execSQL("UPDATE volto SET persona_id = ? WHERE foto_id = ? AND persona_id = ?", arrayOf(ESCLUSO, fotoId, personaId))
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun inserisciManuale(db: SQLiteDatabase, fotoId: Long, personaId: Long) {
        db.insert("volto", null, ContentValues().apply {
            put("foto_id", fotoId); put("l", 0.05f); put("t", 0.05f); put("r", 0.95f); put("b", 0.95f)
            put("area", 0f); put("persona_id", personaId); put("manuale", 1)
        })
    }

    /** Collega a mano delle foto a una persona (anche senza volto visibile). Ritorna quante sono state aggiunte. */
    fun associa(fotoIds: List<Long>, personaId: Long): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (f in fotoIds) {
                val gia = db.rawQuery("SELECT 1 FROM volto WHERE foto_id = ? AND persona_id = ? LIMIT 1",
                    arrayOf(f.toString(), personaId.toString())).use { it.moveToFirst() }
                if (!gia) { inserisciManuale(db, f, personaId); n++ }
            }
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    fun nuovaPersonaConFoto(fotoIds: List<Long>, nome: String): Long {
        val id = writableDatabase.insert("persona", null, ContentValues().apply { put("nome", nome.trim()) })
        associa(fotoIds, id)
        return id
    }

    /** Sposta i volti (e i collegamenti a mano) di [da] nelle foto indicate verso la persona [a] */
    fun spostaFoto(fotoIds: List<Long>, da: Long, a: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in fotoIds) {
                // se la foto è già collegata a mano alla destinazione, il collegamento doppio si toglie
                db.delete("volto", "foto_id = ? AND persona_id = ? AND manuale = 1 AND EXISTS (SELECT 1 FROM volto x WHERE x.foto_id = ? AND x.persona_id = ?)",
                    arrayOf(f.toString(), da.toString(), f.toString(), a.toString()))
                db.execSQL("UPDATE volto SET persona_id = ? WHERE foto_id = ? AND persona_id = ?", arrayOf<Any>(a, f, da))
            }
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun nuovaPersonaDaFoto(fotoIds: List<Long>, da: Long, nome: String) {
        val id = writableDatabase.insert("persona", null, ContentValues().apply { put("nome", nome.trim()) })
        spostaFoto(fotoIds, da, id)
    }

    /** Vettori dei volti di [personaId] nelle foto indicate */
    fun vettoriFotoPersona(fotoIds: List<Long>, personaId: Long): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (f in fotoIds) readableDatabase.rawQuery("SELECT emb FROM volto WHERE foto_id = ? AND persona_id = ? AND emb IS NOT NULL",
            arrayOf(f.toString(), personaId.toString())).use { c -> while (c.moveToNext()) out += c.getBlob(0).toFloats() }
        return out
    }

    fun rimuoviVolto(voltoId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("volto", "id=?", arrayOf(voltoId.toString()))
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun ricontaInterno(db: SQLiteDatabase) {
        db.execSQL("UPDATE persona SET foto = (SELECT COUNT(DISTINCT foto_id) FROM volto WHERE persona_id = persona.id)")
        db.execSQL("UPDATE persona SET copertina = (SELECT id FROM volto WHERE persona_id = persona.id ORDER BY manuale, area DESC LIMIT 1) WHERE copertina IS NULL OR copertina NOT IN (SELECT id FROM volto WHERE persona_id = persona.id)")
        db.execSQL("DELETE FROM persona WHERE foto = 0")
    }

    /** Salva le correzioni manuali delle etichette di una foto */
    fun etichetteManuali(fotoId: Long, manuali: List<String>, rimosse: Set<String>) {
        writableDatabase.update("foto", ContentValues().apply {
            put("manuali", manuali.map { it.replace(";", ",").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(";"))
            put("rimosse", rimosse.joinToString(";"))
        }, "id=?", arrayOf(fotoId.toString()))
    }

    /** Dopo una copia nella galleria: duplica la voce dell'indice (niente nuova analisi) */
    fun duplica(vecchio: Long, nuovo: Long, modificata: Long, album: String, nome: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO foto(id, data, modificata, nome, album, etichette, volti, sorriso, testo, colori, versione, uri, cartella, manuali, rimosse) " +
                    "SELECT ?, data, ?, ?, ?, etichette, volti, sorriso, testo, colori, versione, NULL, NULL, manuali, rimosse FROM foto WHERE id = ?",
                arrayOf<Any>(nuovo, modificata, nome, album, vecchio)
            )
            db.execSQL(
                "INSERT INTO volto(foto_id, l, t, r, b, area, emb, persona_id, eta, maschio, capelli, abiti) " +
                    "SELECT ?, l, t, r, b, area, emb, persona_id, eta, maschio, capelli, abiti FROM volto WHERE foto_id = ?",
                arrayOf<Any>(nuovo, vecchio)
            )
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun aggiornaAlbum(id: Long, album: String) {
        writableDatabase.update("foto", ContentValues().apply { put("album", album) }, "id=?", arrayOf(id.toString()))
    }

    fun azzera() {
        val db = writableDatabase
        db.execSQL("DELETE FROM volto"); db.execSQL("DELETE FROM foto"); db.execSQL("DELETE FROM persona")
    }
}
