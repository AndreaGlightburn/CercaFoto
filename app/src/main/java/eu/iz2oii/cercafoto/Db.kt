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
) {
    val uri: Uri get() = uriFoto(id)
}

data class Persona(
    val id: Long,
    val nome: String?,
    val foto: Int,
    val nascosta: Boolean,
    val coverVolto: Long,
    val coverFoto: Long,
    val l: Float, val t: Float, val r: Float, val b: Float,
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

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "indice.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
        const val ESCLUSO = -2L
    }

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE foto(id INTEGER PRIMARY KEY, data INTEGER, modificata INTEGER, nome TEXT, album TEXT, etichette TEXT, volti INTEGER, sorriso REAL, testo TEXT)")
        db.execSQL("CREATE TABLE volto(id INTEGER PRIMARY KEY AUTOINCREMENT, foto_id INTEGER, l REAL, t REAL, r REAL, b REAL, area REAL, emb BLOB, persona_id INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX volto_foto ON volto(foto_id)")
        db.execSQL("CREATE INDEX volto_persona ON volto(persona_id)")
        db.execSQL("CREATE TABLE persona(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT, copertina INTEGER, foto INTEGER DEFAULT 0, nascosta INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

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
            val cv = ContentValues().apply {
                put("id", m.id); put("data", m.data); put("modificata", m.modificata)
                put("nome", m.nome); put("album", m.album)
                put("etichette", r?.etichette?.joinToString(";") { "${it.first}:${it.second}" } ?: "")
                put("volti", r?.volti?.size ?: 0)
                put("sorriso", r?.sorriso ?: 0f)
                put("testo", r?.testo ?: "")
            }
            db.insertWithOnConflict("foto", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            r?.volti?.forEach { v ->
                db.insert("volto", null, ContentValues().apply {
                    put("foto_id", m.id); put("l", v.l); put("t", v.t); put("r", v.r); put("b", v.b)
                    put("area", (v.r - v.l) * (v.b - v.t))
                    if (v.emb != null) put("emb", v.emb.toBlob())
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun caricaFoto(): List<FotoIdx> {
        val db = readableDatabase
        val pers = HashMap<Long, MutableSet<Long>>()
        db.rawQuery("SELECT foto_id, persona_id FROM volto WHERE persona_id > 0", null).use { c ->
            while (c.moveToNext()) pers.getOrPut(c.getLong(0)) { mutableSetOf() }.add(c.getLong(1))
        }
        val out = ArrayList<FotoIdx>()
        db.rawQuery("SELECT id, data, nome, album, etichette, volti, sorriso, testo FROM foto ORDER BY data DESC", null).use { c ->
            while (c.moveToNext()) {
                val et = HashMap<String, Int>()
                c.getString(4)?.split(';')?.forEach { s ->
                    val i = s.lastIndexOf(':')
                    if (i > 0) et[s.substring(0, i)] = s.substring(i + 1).toIntOrNull() ?: 0
                }
                val testo = c.getString(7) ?: ""
                out += FotoIdx(
                    id = c.getLong(0), data = c.getLong(1), nome = c.getString(2) ?: "",
                    album = c.getString(3) ?: "", etichette = et, volti = c.getInt(5),
                    sorriso = c.getFloat(6), testo = Testo.normalizza(testo), testoOriginale = testo,
                    persone = pers[c.getLong(0)] ?: emptySet(),
                )
            }
        }
        return out
    }

    fun caricaPersone(): List<Persona> {
        val out = ArrayList<Persona>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.nome, p.foto, p.nascosta, v.id, v.foto_id, v.l, v.t, v.r, v.b FROM persona p LEFT JOIN volto v ON v.id = p.copertina ORDER BY (p.nome IS NULL OR p.nome = ''), p.foto DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out += Persona(
                c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() }, c.getInt(2), c.getInt(3) == 1,
                c.getLong(4), c.getLong(5), c.getFloat(6), c.getFloat(7), c.getFloat(8), c.getFloat(9)
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

    fun azzera() {
        val db = writableDatabase
        db.execSQL("DELETE FROM volto"); db.execSQL("DELETE FROM foto"); db.execSQL("DELETE FROM persona")
    }
}
