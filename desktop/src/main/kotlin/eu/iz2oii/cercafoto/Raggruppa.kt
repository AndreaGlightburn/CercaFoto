package eu.iz2oii.cercafoto

/** Raggruppamento dei volti per persona: stessa logica dell'app Android */
object Raggruppa {

    private class Volto(val id: Long, val area: Float, val emb: FloatArray, var gruppo: Int = -1)

    private class Gruppo(val personaId: Long, dim: Int) {
        val somma = FloatArray(dim)
        val centro = FloatArray(dim)
        val membri = ArrayList<Volto>()
        fun aggiungi(v: Volto) {
            membri += v
            for (i in somma.indices) somma[i] += v.emb[i]
            System.arraycopy(somma, 0, centro, 0, somma.size); normalizza(centro)
        }
    }

    @Synchronized
    fun esegui() {
        val soglia = Impostazioni.soglia
        val nominate = HashSet<Long>()
        Db.q("SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> ''") { nominate += it.getLong(1) }
        val lista = if (nominate.isEmpty()) "-999" else nominate.joinToString(",")
        Db.tx {
            Db.u("DELETE FROM persona WHERE id NOT IN ($lista)")
            Db.u("UPDATE volto SET persona_id = 0 WHERE persona_id > 0 AND persona_id NOT IN ($lista)")
        }
        val gruppi = ArrayList<Gruppo>()
        val perPersona = HashMap<Long, Int>()
        val liberi = ArrayList<Volto>()
        Db.q("SELECT id, area, emb, persona_id FROM volto WHERE emb IS NOT NULL AND persona_id >= 0 AND manuale = 0") { r ->
            val v = Volto(r.getLong(1), r.getFloat(2), r.getBytes(3).toFloats())
            val p = r.getLong(4)
            if (p > 0) {
                val gi = perPersona.getOrPut(p) { gruppi += Gruppo(p, v.emb.size); gruppi.size - 1 }
                v.gruppo = gi; gruppi[gi].aggiungi(v)
            } else liberi += v
        }
        liberi.sortByDescending { it.area }
        for (v in liberi) {
            var best = -1; var bs = soglia
            for ((i, g) in gruppi.withIndex()) { val s = dot(v.emb, g.centro); if (s >= bs) { bs = s; best = i } }
            if (best >= 0) { v.gruppo = best; gruppi[best].aggiungi(v) }
            else { gruppi += Gruppo(0, v.emb.size); v.gruppo = gruppi.size - 1; gruppi.last().aggiungi(v) }
        }
        for (v in liberi) {
            var best = v.gruppo; var bs = dot(v.emb, gruppi[v.gruppo].centro)
            for ((i, g) in gruppi.withIndex()) {
                if (g.membri.size < 2 || i == v.gruppo) continue
                val s = dot(v.emb, g.centro)
                if (s > bs + 0.02f && s >= soglia) { bs = s; best = i }
            }
            v.gruppo = best
        }
        Db.tx {
            val membri = HashMap<Int, MutableList<Volto>>()
            for (g in gruppi) for (v in g.membri) membri.getOrPut(v.gruppo) { mutableListOf() }.add(v)
            for ((gi, vs) in membri) {
                val g = gruppi[gi]
                val pid = if (g.personaId > 0) g.personaId else Db.ins("INSERT INTO persona(copertina) VALUES (?)", vs.maxByOrNull { it.area }!!.id)
                for (v in vs) Db.u("UPDATE volto SET persona_id = ? WHERE id = ?", pid, v.id)
            }
            Db.ricontaInterno()
        }
    }

    /** Sostituisce i volti rilevati di una foto mantenendo assegnazioni a persone con nome ed esclusioni */
    fun sostituisciVolti(fotoId: Long, volti: List<Db.VoltoNuovo>) = Db.tx {
        class Vecchio(val l: Float, val t: Float, val r: Float, val b: Float, val p: Long)
        val tenere = ArrayList<Vecchio>()
        Db.q("SELECT l, t, r, b, persona_id FROM volto WHERE foto_id = ? AND manuale = 0 AND (persona_id = ${Db.ESCLUSO} OR persona_id IN " +
            "(SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> ''))", fotoId) { tenere += Vecchio(it.getFloat(1), it.getFloat(2), it.getFloat(3), it.getFloat(4), it.getLong(5)) }
        Db.q("SELECT id FROM volto WHERE foto_id = ? AND manuale = 0", fotoId) { Percorsi.volto(it.getLong(1)).delete() }
        Db.u("DELETE FROM volto WHERE foto_id = ? AND manuale = 0", fotoId)
        val usati = HashSet<Vecchio>()
        for (v in volti) {
            val m = tenere.filter { it !in usati }.maxByOrNull { ScambioFormato.iou(it.l, it.t, it.r, it.b, v.l, v.t, v.r, v.b) }
                ?.takeIf { ScambioFormato.iou(it.l, it.t, it.r, it.b, v.l, v.t, v.r, v.b) >= 0.35f }
            m?.let { usati += it }
            val vid = Db.ins("INSERT INTO volto(foto_id, l, t, r, b, area, emb, persona_id, eta, maschio, capelli, abiti) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                fotoId, v.l, v.t, v.r, v.b, (v.r - v.l) * (v.b - v.t), v.emb?.toBlob(), m?.p ?: 0L, v.attr?.eta, v.attr?.maschio, v.attr?.capelli, v.attr?.abiti)
            v.ritaglio?.let { runCatching { Percorsi.volto(vid).writeBytes(it) } }
        }
        for (o in tenere) if (o !in usati)
            Db.ins("INSERT INTO volto(foto_id, l, t, r, b, area, persona_id) VALUES (?,?,?,?,?,?,?)", fotoId, o.l, o.t, o.r, o.b, (o.r - o.l) * (o.b - o.t), o.p)
        Db.u("UPDATE foto SET volti = ? WHERE id = ?", maxOf(volti.size, tenere.size), fotoId)
    }
}
