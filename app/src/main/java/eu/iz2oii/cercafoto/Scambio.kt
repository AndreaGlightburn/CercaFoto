package eu.iz2oii.cercafoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import eu.iz2oii.cercafoto.ScambioFormato.FotoX
import eu.iz2oii.cercafoto.ScambioFormato.PersonaX
import eu.iz2oii.cercafoto.ScambioFormato.VoltoX

/** Esportazione/importazione del file .cercafoto (persone con nome e correzioni manuali) */
object Scambio {

    data class Esito(val persone: Int, val nuove: Int, val foto: Int, val fotoNonTrovate: Int, val voltiRiconosciuti: Int)

    private fun dimensione(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun sha(ctx: Context, db: Db, f: FotoIdx): String? {
        val dbw = db.writableDatabase
        dbw.rawQuery("SELECT sha FROM foto WHERE id = ?", arrayOf(f.id.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        val h = runCatching { ctx.contentResolver.openInputStream(f.uri)?.let { ScambioFormato.sha256(it) } }.getOrNull() ?: return null
        dbw.update("foto", ContentValues().apply { put("sha", h) }, "id=?", arrayOf(f.id.toString()))
        return h
    }

    // ------------------------------------------------------------ esporta

    fun esporta(ctx: Context, out: OutputStream, avanzamento: (String) -> Unit): Pair<Int, Int> {
        val db = Db.get(ctx)
        val r = db.readableDatabase
        val w = db.writableDatabase
        // persone con nome (con uid stabile)
        val uidDi = HashMap<Long, String>()
        val persone = ArrayList<PersonaX>()
        r.rawQuery("SELECT id, nome, nascosta, uid FROM persona WHERE nome IS NOT NULL AND nome <> ''", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val uid = c.getString(3) ?: UUID.randomUUID().toString().also {
                    w.update("persona", ContentValues().apply { put("uid", it) }, "id=?", arrayOf(id.toString()))
                }
                uidDi[id] = uid
                val campioni = ArrayList<FloatArray>()
                r.rawQuery("SELECT emb FROM volto WHERE persona_id = ? AND emb IS NOT NULL ORDER BY area DESC LIMIT 24", arrayOf(id.toString())).use { e ->
                    while (e.moveToNext()) campioni += e.getBlob(0).toFloats()
                }
                persone += PersonaX(uid, c.getString(1), c.getInt(2) == 1, campioni)
            }
        }
        // foto con correzioni o volti assegnati a persone con nome
        val tutte = db.caricaFoto().associateBy { it.id }
        val ids = LinkedHashSet<Long>()
        r.rawQuery("SELECT id FROM foto WHERE (manuali IS NOT NULL AND manuali <> '') OR (rimosse IS NOT NULL AND rimosse <> '')", null).use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        r.rawQuery("SELECT DISTINCT foto_id FROM volto WHERE persona_id = ${Db.ESCLUSO} OR manuale = 1 OR persona_id IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')", null).use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        val foto = ArrayList<FotoX>()
        for ((i, id) in ids.withIndex()) {
            val f = tutte[id] ?: continue
            if (i % 20 == 0) avanzamento("Preparo le foto: ${i + 1} di ${ids.size}")
            val volti = ArrayList<VoltoX>()
            r.rawQuery("SELECT l, t, r, b, persona_id, manuale FROM volto WHERE foto_id = ?", arrayOf(id.toString())).use { c ->
                while (c.moveToNext()) {
                    val p = c.getLong(4)
                    val uid = uidDi[p]
                    if (uid != null || p == Db.ESCLUSO)
                        volti += VoltoX(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), uid, p == Db.ESCLUSO, c.getInt(5) == 1)
                }
            }
            foto += FotoX(f.nome, dimensione(ctx, f.uri), f.data, sha(ctx, db, f), f.manuali, f.rimosse.toList(), volti)
        }
        avanzamento("Scrittura del file…")
        ScambioFormato.scrivi(ScambioFormato.Pacchetto("android", System.currentTimeMillis(), persone, foto), out)
        return persone.size to foto.size
    }

    // ------------------------------------------------------------ importa

    fun importa(ctx: Context, input: InputStream, avanzamento: (String) -> Unit): Esito {
        val pacchetto = ScambioFormato.leggi(input)
        val db = Db.get(ctx)
        val w = db.writableDatabase

        // 1) persone: per uid, poi per nome; altrimenti nuova
        avanzamento("Persone…")
        val locali = db.caricaPersone()
        val uidLocali = HashMap<String, Long>()
        w.rawQuery("SELECT id, uid FROM persona WHERE uid IS NOT NULL", null).use { c -> while (c.moveToNext()) uidLocali[c.getString(1)] = c.getLong(0) }
        val mappa = HashMap<String, Long>()   // uid importato -> persona locale
        var nuove = 0
        for (p in pacchetto.persone) {
            val loc = uidLocali[p.uid]
                ?: locali.firstOrNull { it.nome != null && Testo.normalizza(it.nome) == Testo.normalizza(p.nome) }?.id
            val id = loc ?: w.insert("persona", null, ContentValues().apply { put("nome", p.nome); put("nascosta", if (p.nascosta) 1 else 0) }).also { nuove++ }
            w.update("persona", ContentValues().apply { put("uid", p.uid) }, "id=? AND (uid IS NULL OR uid = '')", arrayOf(id.toString()))
            w.execSQL("UPDATE persona SET nome = ? WHERE id = ? AND (nome IS NULL OR nome = '')", arrayOf<Any>(p.nome, id))
            mappa[p.uid] = id
        }

        // 2) correzioni per foto: abbinamento per nome file (+ dimensione / impronta)
        val perNome = db.caricaFoto().groupBy { it.nome.lowercase() }
        var abbinate = 0; var mancanti = 0
        for ((i, fx) in pacchetto.foto.withIndex()) {
            if (i % 20 == 0) avanzamento("Foto: ${i + 1} di ${pacchetto.foto.size}")
            val cand = perNome[fx.nome.lowercase()].orEmpty()
            val f = when {
                cand.isEmpty() -> null
                cand.size == 1 && (fx.dimensione <= 0 || dimensione(ctx, cand[0].uri).let { it <= 0 || it == fx.dimensione }) -> cand[0]
                fx.sha256 != null -> cand.firstOrNull { sha(ctx, db, it) == fx.sha256 }
                else -> cand.firstOrNull { dimensione(ctx, it.uri) == fx.dimensione }
            }
            if (f == null) { mancanti++; continue }
            abbinate++
            if (fx.etichette.isNotEmpty() || fx.rimosse.isNotEmpty())
                db.etichetteManuali(f.id, (f.manuali + fx.etichette).distinct(), f.rimosse + fx.rimosse)
            val locVolti = db.voltiFoto(f.id).filter { !it.manuale }
            for (v in fx.volti) {
                val pid = v.uid?.let { mappa[it] }
                if (v.manuale) { if (pid != null) db.associa(listOf(f.id), pid); continue }
                val target = if (v.escluso) Db.ESCLUSO else pid ?: continue
                val best = locVolti.maxByOrNull { ScambioFormato.iou(it.l, it.t, it.r, it.b, v.l, v.t, v.r, v.b) } ?: continue
                if (ScambioFormato.iou(best.l, best.t, best.r, best.b, v.l, v.t, v.r, v.b) >= 0.3f)
                    w.update("volto", ContentValues().apply { put("persona_id", target) }, "id=?", arrayOf(best.id.toString()))
            }
        }

        // 3) riconoscimento: volti non ancora assegnati a una persona con nome, confrontati con i campioni importati
        avanzamento("Riconosco i volti…")
        val soglia = Impostazioni.soglia(ctx)
        val conCampioni = pacchetto.persone.filter { it.campioni.isNotEmpty() && mappa[it.uid] != null }
        var riconosciuti = 0
        if (conCampioni.isNotEmpty()) {
            val upd = w.compileStatement("UPDATE volto SET persona_id = ? WHERE id = ?")
            w.beginTransaction()
            try {
                w.rawQuery(
                    "SELECT id, emb FROM volto WHERE emb IS NOT NULL AND manuale = 0 AND persona_id <> ${Db.ESCLUSO} AND " +
                        "persona_id NOT IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')", null
                ).use { c ->
                    while (c.moveToNext()) {
                        val e = c.getBlob(1).toFloats()
                        var best: PersonaX? = null; var bs = soglia
                        for (p in conCampioni) { val s = ScambioFormato.somiglianza(e, p.campioni); if (s >= bs) { bs = s; best = p } }
                        if (best != null) { upd.bindLong(1, mappa[best.uid]!!); upd.bindLong(2, c.getLong(0)); upd.executeUpdateDelete(); riconosciuti++ }
                    }
                }
                db.ricontaInterno(w)
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
        }
        avanzamento("Raggruppo i volti…")
        Raggruppa.esegui(ctx)
        return Esito(pacchetto.persone.size, nuove, abbinate, mancanti, riconosciuti)
    }
}
