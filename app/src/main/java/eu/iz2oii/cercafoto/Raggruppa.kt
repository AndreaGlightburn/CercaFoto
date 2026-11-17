package eu.iz2oii.cercafoto

import android.content.ContentValues
import android.content.Context

/**
 * Raggruppa i volti per persona confrontando i vettori FaceNet (similarità coseno).
 * I gruppi a cui l'utente ha dato un nome restano fissi e "attirano" i volti nuovi;
 * i gruppi senza nome vengono ricalcolati ogni volta.
 */
object Raggruppa {

    private class Volto(val id: Long, val foto: Long, val area: Float, val emb: FloatArray, var gruppo: Int = -1)

    private class Gruppo(val personaId: Long, dim: Int) {
        val somma = FloatArray(dim)
        val centro = FloatArray(dim)
        val membri = ArrayList<Volto>()
        fun aggiungi(v: Volto) {
            membri += v
            for (i in somma.indices) somma[i] += v.emb[i]
            ricalcola()
        }
        fun ricalcola() {
            System.arraycopy(somma, 0, centro, 0, somma.size)
            normalizza(centro)
        }
    }

    @Synchronized
    fun esegui(ctx: Context) {
        val soglia = Impostazioni.soglia(ctx)
        val db = Db.get(ctx).writableDatabase

        val nominate = HashSet<Long>()
        db.rawQuery("SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> ''", null).use { c ->
            while (c.moveToNext()) nominate += c.getLong(0)
        }
        db.beginTransaction()
        try {
            val lista = if (nominate.isEmpty()) "-999" else nominate.joinToString(",")
            db.execSQL("DELETE FROM persona WHERE id NOT IN ($lista)")
            db.execSQL("UPDATE volto SET persona_id = 0 WHERE persona_id > 0 AND persona_id NOT IN ($lista)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        val gruppi = ArrayList<Gruppo>()
        val perPersona = HashMap<Long, Gruppo>()
        val liberi = ArrayList<Volto>()
        db.rawQuery("SELECT id, foto_id, area, emb, persona_id FROM volto WHERE emb IS NOT NULL AND persona_id >= 0", null).use { c ->
            while (c.moveToNext()) {
                val v = Volto(c.getLong(0), c.getLong(1), c.getFloat(2), c.getBlob(3).toFloats())
                val p = c.getLong(4)
                if (p > 0) {
                    val g = perPersona.getOrPut(p) { Gruppo(p, v.emb.size).also { gruppi += it } }
                    v.gruppo = gruppi.indexOf(g)
                    g.aggiungi(v)
                } else liberi += v
            }
        }

        // 1° passaggio: assegnazione incrementale, partendo dai volti più grandi (più affidabili)
        liberi.sortByDescending { it.area }
        for (v in liberi) {
            var best = -1; var bestSim = soglia
            for ((i, g) in gruppi.withIndex()) {
                val s = dot(v.emb, g.centro)
                if (s >= bestSim) { bestSim = s; best = i }
            }
            if (best >= 0) { v.gruppo = best; gruppi[best].aggiungi(v) }
            else { val g = Gruppo(0, v.emb.size); gruppi += g; v.gruppo = gruppi.size - 1; g.aggiungi(v) }
        }

        // 2° passaggio: riassegna i volti liberi al centro migliore (affina i gruppi)
        for (v in liberi) {
            var best = v.gruppo; var bestSim = dot(v.emb, gruppi[v.gruppo].centro)
            for ((i, g) in gruppi.withIndex()) {
                if (g.membri.size < 2 || i == v.gruppo) continue
                val s = dot(v.emb, g.centro)
                if (s > bestSim + 0.02f && s >= soglia) { bestSim = s; best = i }
            }
            v.gruppo = best
        }

        // Scrittura
        db.beginTransaction()
        try {
            val membri = HashMap<Int, MutableList<Volto>>()
            for (g in gruppi) for (v in g.membri) membri.getOrPut(v.gruppo) { mutableListOf() }.add(v)
            val upd = db.compileStatement("UPDATE volto SET persona_id = ? WHERE id = ?")
            for ((gi, vs) in membri) {
                val g = gruppi[gi]
                val pid = if (g.personaId > 0) g.personaId else {
                    val cover = vs.maxByOrNull { it.area }!!.id
                    db.insert("persona", null, ContentValues().apply { put("copertina", cover) })
                }
                for (v in vs) { upd.bindLong(1, pid); upd.bindLong(2, v.id); upd.executeUpdateDelete() }
            }
            Db.get(ctx).ricontaInterno(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
