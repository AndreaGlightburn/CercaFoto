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

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "indice.db", null, 2) {

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
        onUpgrade(db, 1, 2)
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
            db.delete("volto", "foto_id=?", arrayOf(m.id.toString()))
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
            db.execSQL("UPDATE volto SET persona_id = ? WHERE foto_id = ? AND persona_id = ?", arrayOf(ESCLUSO, fotoId, personaId))
            ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun ricontaInterno(db: SQLiteDatabase) {
        db.execSQL("UPDATE persona SET foto = (SELECT COUNT(DISTINCT foto_id) FROM volto WHERE persona_id = persona.id)")
        db.execSQL("UPDATE persona SET copertina = (SELECT id FROM volto WHERE persona_id = persona.id ORDER BY area DESC LIMIT 1) WHERE copertina IS NULL OR copertina NOT IN (SELECT id FROM volto WHERE persona_id = persona.id)")
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
