package eu.iz2oii.cercafoto

import eu.iz2oii.cercafoto.ScambioFormato.FotoX
import eu.iz2oii.cercafoto.ScambioFormato.PersonaX
import eu.iz2oii.cercafoto.ScambioFormato.VoltoX
import java.io.File
import java.util.UUID

/** Esportazione/importazione del file .cercafoto: stessa logica dell'app Android */
object Scambio {
    data class Esito(val persone: Int, val nuove: Int, val foto: Int, val fotoNonTrovate: Int, val voltiRiconosciuti: Int)

    private fun sha(id: Long, percorso: String): String? {
        var h: String? = null
        Db.q("SELECT sha FROM foto WHERE id = ?", id) { h = it.getString(1) }
        if (h != null) return h
        h = runCatching { File(percorso).inputStream().let { ScambioFormato.sha256(it) } }.getOrNull() ?: return null
        Db.u("UPDATE foto SET sha = ? WHERE id = ?", h, id)
        return h
    }

    fun esporta(file: File, avanzamento: (String) -> Unit): Pair<Int, Int> {
        val uidDi = HashMap<Long, String>()
        val persone = ArrayList<PersonaX>()
        val elenco = ArrayList<Triple<Long, String, Boolean>>()
        val uidEsistenti = HashMap<Long, String?>()
        Db.q("SELECT id, nome, nascosta, uid FROM persona WHERE nome IS NOT NULL AND nome <> ''") {
            elenco += Triple(it.getLong(1), it.getString(2), it.getInt(3) == 1); uidEsistenti[it.getLong(1)] = it.getString(4)
        }
        for ((id, nome, nascosta) in elenco) {
            val uid = uidEsistenti[id] ?: UUID.randomUUID().toString().also { Db.u("UPDATE persona SET uid = ? WHERE id = ?", it, id) }
            uidDi[id] = uid
            val campioni = ArrayList<FloatArray>()
            Db.q("SELECT emb FROM volto WHERE persona_id = ? AND emb IS NOT NULL ORDER BY area DESC LIMIT 24", id) { campioni += it.getBytes(1).toFloats() }
            // se qui ci sono pochi volti, aggiunge i campioni ricevuti in precedenza
            if (campioni.size < 24) Db.q("SELECT emb FROM riferimento WHERE persona_id = ? LIMIT ?", id, 24 - campioni.size) { campioni += it.getBytes(1).toFloats() }
            persone += PersonaX(uid, nome, nascosta, campioni)
        }
        val tutte = Db.caricaFoto().associateBy { it.id }
        val ids = LinkedHashSet<Long>()
        Db.q("SELECT id FROM foto WHERE (manuali IS NOT NULL AND manuali <> '') OR (rimosse IS NOT NULL AND rimosse <> '')") { ids += it.getLong(1) }
        Db.q("SELECT DISTINCT foto_id FROM volto WHERE persona_id = ${Db.ESCLUSO} OR manuale = 1 OR persona_id IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')") { ids += it.getLong(1) }
        val foto = ArrayList<FotoX>()
        for ((i, id) in ids.withIndex()) {
            val f = tutte[id] ?: continue
            if (i % 20 == 0) avanzamento("Preparo le foto: ${i + 1} di ${ids.size}")
            val volti = ArrayList<VoltoX>()
            Db.q("SELECT l, t, r, b, persona_id, manuale FROM volto WHERE foto_id = ?", id) { r ->
                val p = r.getLong(5); val uid = uidDi[p]
                if (uid != null || p == Db.ESCLUSO) volti += VoltoX(r.getFloat(1), r.getFloat(2), r.getFloat(3), r.getFloat(4), uid, p == Db.ESCLUSO, r.getInt(6) == 1)
            }
            var nota: String? = null
            Db.q("SELECT sha FROM foto WHERE id = ?", f.id) { nota = it.getString(1) }
            foto += FotoX(f.nome, File(f.percorso).length(), f.data, nota, f.manuali, f.rimosse.toList(), volti)
        }
        avanzamento("Scrittura del file…")
        file.outputStream().use { ScambioFormato.scrivi(ScambioFormato.Pacchetto("windows", System.currentTimeMillis(), persone, foto), it) }
        return persone.size to foto.size
    }

    fun importa(file: File, avanzamento: (String) -> Unit): Esito {
        val pacchetto = file.inputStream().use { ScambioFormato.leggi(it) }
        avanzamento("Persone…")
        val locali = Db.caricaPersone()
        val uidLocali = HashMap<String, Long>()
        Db.q("SELECT id, uid FROM persona WHERE uid IS NOT NULL") { uidLocali[it.getString(2)] = it.getLong(1) }
        val mappa = HashMap<String, Long>()
        var nuove = 0
        for (p in pacchetto.persone) {
            val loc = uidLocali[p.uid] ?: locali.firstOrNull { it.nome != null && Testo.normalizza(it.nome) == Testo.normalizza(p.nome) }?.id
            val id = loc ?: Db.ins("INSERT INTO persona(nome, nascosta) VALUES (?, ?)", p.nome, p.nascosta).also { nuove++ }
            Db.u("UPDATE persona SET uid = ? WHERE id = ? AND (uid IS NULL OR uid = '')", p.uid, id)
            Db.u("UPDATE persona SET nome = ? WHERE id = ? AND (nome IS NULL OR nome = '')", p.nome, id)
            if (p.campioni.isNotEmpty()) Db.salvaRiferimenti(id, p.campioni)
            mappa[p.uid] = id
        }
        val perNome = Db.caricaFoto().groupBy { it.nome.lowercase() }
        var abbinate = 0; var mancanti = 0; var voltiAbbinati = 0
        for ((i, fx) in pacchetto.foto.withIndex()) {
            if (i % 20 == 0) avanzamento("Foto: ${i + 1} di ${pacchetto.foto.size}")
            val cand = perNome[fx.nome.lowercase()].orEmpty()
            val f = ScambioFormato.scegliFoto(cand, fx, { File(it.percorso).length() }, { it.data }, { sha(it.id, it.percorso) })
            if (f == null) { mancanti++; continue }
            abbinate++
            if (fx.etichette.isNotEmpty() || fx.rimosse.isNotEmpty()) Db.etichetteManuali(f.id, (f.manuali + fx.etichette).distinct(), f.rimosse + fx.rimosse)
            val loc = Db.voltiFoto(f.id).filter { !it.manuale }
            for (v in fx.volti) {
                val pid = v.uid?.let { mappa[it] }
                if (v.manuale) { if (pid != null) Db.associa(listOf(f.id), pid); continue }
                val target = if (v.escluso) Db.ESCLUSO else pid ?: continue
                val k = ScambioFormato.abbinaRiquadro(loc.map { floatArrayOf(it.l, it.t, it.r, it.b) }, v.l, v.t, v.r, v.b)
                if (k >= 0) { Db.u("UPDATE volto SET persona_id = ? WHERE id = ?", target, loc[k].id); voltiAbbinati++ }
            }
        }
        avanzamento("Riconosco i volti…")
        // i campioni arrivano da un altro rilevatore di volti: soglia un po' più permissiva
        val soglia = Impostazioni.soglia - 0.05f
        val conCampioni = pacchetto.persone.filter { it.campioni.isNotEmpty() && mappa[it.uid] != null }
        var riconosciuti = 0
        if (conCampioni.isNotEmpty()) {
            val da = ArrayList<Pair<Long, FloatArray>>()
            Db.q("SELECT id, emb FROM volto WHERE emb IS NOT NULL AND manuale = 0 AND persona_id <> ${Db.ESCLUSO} AND persona_id NOT IN (SELECT id FROM persona WHERE nome IS NOT NULL AND nome <> '')") {
                da += it.getLong(1) to it.getBytes(2).toFloats()
            }
            Db.tx {
                for ((vid, e) in da) {
                    var best: PersonaX? = null; var bs = soglia
                    for (p in conCampioni) { val s = ScambioFormato.somiglianza(e, p.campioni); if (s >= bs) { bs = s; best = p } }
                    best?.let { Db.u("UPDATE volto SET persona_id = ? WHERE id = ?", mappa[it.uid]!!, vid); riconosciuti++ }
                }
                Db.ricontaInterno()
            }
        }
        avanzamento("Raggruppo i volti…")
        Raggruppa.esegui()
        return Esito(pacchetto.persone.size, nuove, abbinate, mancanti, riconosciuti + voltiAbbinati)
    }
}
