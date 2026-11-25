package eu.iz2oii.cercafoto

import eu.iz2oii.cercafoto.IndicePortatile.FotoP
import eu.iz2oii.cercafoto.IndicePortatile.MomentoP
import eu.iz2oii.cercafoto.IndicePortatile.PersonaP
import eu.iz2oii.cercafoto.IndicePortatile.VideoP
import eu.iz2oii.cercafoto.IndicePortatile.VoltoP
import java.io.File
import java.util.UUID

/** Indice portatile su Windows: scritto nella radice di ogni cartella scelta, letto per non rianalizzare */
object Portatile {

    fun file(radice: File) = File(File(radice, IndicePortatile.CARTELLA), IndicePortatile.nomeFile("windows"))

    fun relativo(radice: File, percorso: String): String? = runCatching {
        val r = radice.toPath().toAbsolutePath().normalize(); val p = File(percorso).toPath().toAbsolutePath().normalize()
        if (!p.startsWith(r)) null else IndicePortatile.normalizzaPercorso(r.relativize(p).toString())
    }.getOrNull()

    /** Persone con nome e il loro uid (creato se manca) */
    private fun persone(): Pair<HashMap<Long, String>, List<PersonaP>> {
        val uidDi = HashMap<Long, String>(); val out = ArrayList<PersonaP>()
        val righe = ArrayList<Triple<Long, String, Pair<Boolean, String?>>>()
        Db.q("SELECT id, nome, nascosta, uid FROM persona WHERE nome IS NOT NULL AND nome <> ''") { righe += Triple(it.getLong(1), it.getString(2), (it.getInt(3) == 1) to it.getString(4)) }
        for ((id, nome, x) in righe) {
            val uid = x.second?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also { Db.u("UPDATE persona SET uid = ? WHERE id = ?", it, id) }
            uidDi[id] = uid; out += PersonaP(uid, nome, x.first)
        }
        return uidDi to out
    }

    /** "Impronta" dello stato della cartella: se non cambia non serve riscrivere l'indice */
    fun firma(cartella: String): String {
        var s = ""
        Db.q("SELECT COUNT(*), IFNULL(MAX(id),0), IFNULL(SUM(modificata),0) FROM foto WHERE cartella = ?", cartella) { s += "${it.getLong(1)}/${it.getLong(2)}/${it.getLong(3)}" }
        Db.q("SELECT COUNT(*), IFNULL(SUM(v.persona_id),0) FROM volto v JOIN foto f ON f.id = v.foto_id WHERE f.cartella = ?", cartella) { s += "|${it.getLong(1)}/${it.getLong(2)}" }
        Db.q("SELECT group_concat(id || nome, ',') FROM persona WHERE nome IS NOT NULL AND nome <> ''") { s += "|" + (it.getString(1)?.hashCode() ?: 0) }
        Db.q("SELECT COUNT(*), IFNULL(SUM(fotogrammi),0) FROM video WHERE cartella = ?", cartella) { s += "|${it.getLong(1)}/${it.getLong(2)}" }
        Db.q("SELECT group_concat(IFNULL(manuali,'') || IFNULL(rimosse,''), ',') FROM foto WHERE cartella = ?", cartella) { s += "|" + (it.getString(1)?.hashCode() ?: 0) }
        return s
    }

    private class Riga(val id: Long, val rel: String, val s: Long, val d: Long, val et: String?, val col: String?, val clip: ByteArray?, val man: String?, val rim: String?, val sorriso: Float, val tempo: Long)

    /** Scrive l'indice nella radice della cartella; ritorna il numero di foto e video scritti */
    fun scrivi(cartella: String): Int {
        val radice = File(cartella)
        if (!radice.isDirectory) return 0
        val (uidDi, persone) = persone()
        val volti = HashMap<Long, MutableList<VoltoP>>()
        Db.q("SELECT v.foto_id, v.l, v.t, v.r, v.b, v.emb, v.eta, v.maschio, v.capelli, v.abiti, v.persona_id, v.manuale FROM volto v JOIN foto f ON f.id = v.foto_id WHERE f.cartella = ?", cartella) { r ->
            val p = r.getLong(11); val man = r.getInt(12) == 1
            val uid = uidDi[p]
            if (man && uid == null) return@q
            val eta = r.getFloat(7).let { if (r.wasNull()) null else it }
            volti.getOrPut(r.getLong(1)) { mutableListOf() } += VoltoP(r.getFloat(2), r.getFloat(3), r.getFloat(4), r.getFloat(5),
                r.getBytes(6)?.toFloats(), eta, r.getFloat(8).let { if (r.wasNull()) null else it }, r.getString(9), r.getString(10),
                uid, p == Db.ESCLUSO, man)
        }
        fun fotoP(x: Riga) = FotoP(x.rel, x.s, x.d, Db.decodifica(x.et), Db.decodifica(x.col), null, x.sorriso, volti[x.id] ?: emptyList(),
            x.clip?.takeIf { it.isNotEmpty() }?.toFloats(), x.man?.split(';')?.filter { it.isNotBlank() } ?: emptyList(), x.rim?.split(';')?.filter { it.isNotBlank() } ?: emptyList())
        val righe = ArrayList<Riga>()
        Db.q("SELECT id, percorso, dimensione, data, etichette, colori, clip, manuali, rimosse, sorriso, tempo FROM foto WHERE cartella = ? AND video_id = 0", cartella) { r ->
            val rel = relativo(radice, r.getString(2)) ?: return@q
            righe += Riga(r.getLong(1), rel, r.getLong(3), r.getLong(4), r.getString(5), r.getString(6), r.getBytes(7), r.getString(8), r.getString(9), r.getFloat(10), 0)
        }
        val video = ArrayList<VideoP>()
        val vrighe = ArrayList<Pair<Long, Riga>>()
        Db.q("SELECT id, percorso, dimensione, data, durata FROM video WHERE cartella = ?", cartella) { r ->
            val rel = relativo(radice, r.getString(2)) ?: return@q
            vrighe += r.getLong(1) to Riga(r.getLong(1), rel, r.getLong(3), r.getLong(4), null, null, null, null, null, 0f, r.getLong(5))
        }
        for ((vid, v) in vrighe) {
            val m = ArrayList<MomentoP>()
            val fr = ArrayList<Riga>()
            Db.q("SELECT id, data, etichette, colori, clip, manuali, rimosse, sorriso, tempo FROM foto WHERE video_id = ? ORDER BY tempo", vid) { r ->
                fr += Riga(r.getLong(1), "", 0, r.getLong(2), r.getString(3), r.getString(4), r.getBytes(5), r.getString(6), r.getString(7), r.getFloat(8), r.getLong(9))
            }
            for (x in fr) m += MomentoP(x.tempo, fotoP(x))
            video += VideoP(v.rel, v.s, v.d, v.tempo, m)
        }
        val pacchetto = IndicePortatile.Pacchetto("windows", Impostazioni.dispositivo, System.currentTimeMillis(), persone, righe.map { fotoP(it) }, video)
        val dest = file(radice)
        dest.parentFile.mkdirs()
        runCatching { java.nio.file.Files.setAttribute(dest.parentFile.toPath(), "dos:hidden", true) }
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.outputStream().buffered().use { IndicePortatile.scrivi(pacchetto, it) }
        java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return righe.size + video.size
    }

    /** Legge l'indice di un altro dispositivo nella radice della cartella (null se assente, illeggibile o scritto da questo PC) */
    fun leggi(cartella: String): IndicePortatile.Pacchetto? {
        val dir = File(cartella, IndicePortatile.CARTELLA)
        val tutti = dir.listFiles { x -> x.isFile && IndicePortatile.eIndice(x.name) }?.sortedByDescending { it.lastModified() } ?: return null
        for (f in tutti) {
            val p = runCatching { f.inputStream().buffered().use { IndicePortatile.leggi(it) } }.getOrNull() ?: continue
            if (p.dispositivo != Impostazioni.dispositivo) return p   // il più recente scritto da un altro dispositivo
        }
        return null
    }

    /** uid delle persone dell'indice -> persone locali (per uid, poi per nome; altrimenti nuove) */
    fun mappaPersone(p: IndicePortatile.Pacchetto): HashMap<String, Long> {
        val locali = Db.caricaPersone()
        val uidLocali = HashMap<String, Long>()
        Db.q("SELECT id, uid FROM persona WHERE uid IS NOT NULL") { uidLocali[it.getString(2)] = it.getLong(1) }
        val mappa = HashMap<String, Long>()
        for (x in p.persone) {
            val id = uidLocali[x.uid] ?: locali.firstOrNull { it.nome != null && Testo.normalizza(it.nome) == Testo.normalizza(x.nome) }?.id
                ?: Db.ins("INSERT INTO persona(nome, nascosta) VALUES (?, ?)", x.nome, x.nascosta)
            Db.u("UPDATE persona SET uid = ? WHERE id = ? AND (uid IS NULL OR uid = '')", x.uid, id)
            mappa[x.uid] = id
        }
        return mappa
    }

    /**
     * Salva una foto usando l'analisi dell'indice (niente modelli: solo lettura per miniatura e ritagli dei volti).
     * Se l'indice non ha il vettore della ricerca libera lo calcolerà la passata CLIP successiva.
     */
    fun usa(ff: Indicizzatore.FileFoto, x: FotoP, persone: Map<String, Long>): Boolean {
        val f = ff.file
        val img = Immagini.leggi(f, 1280) ?: return false
        val auto = x.volti.filter { !it.manuale }
        val volti = auto.map { v ->
            val attr = v.eta?.let { Attributi(it, v.maschio ?: 0.5f, v.capelli, v.abiti) }
            val rit = Immagini.ritaglia(img, v.l, v.t, v.r, v.b, 0.2f)?.let { Immagini.jpeg(Immagini.ridimensiona(it, 160), 0.85f) }
            Db.VoltoNuovo(v.l, v.t, v.r, v.b, v.emb, attr, rit)
        }
        val clip = x.clip?.takeIf { it.isNotEmpty() }
        val id = Db.salva(f.absolutePath, ff.cartella, f.name, f.parentFile?.name ?: "", if (x.d > 0) x.d else (Immagini.dataScatto(f) ?: f.lastModified()),
            f.lastModified(), f.length(), volti, x.sorriso, x.colori, clip, if (clip != null) x.etichette else emptyMap())
        // senza vettore CLIP: tiene le etichette dell'altro dispositivo finché la passata CLIP non le ricalcola
        if (clip == null && x.etichette.isNotEmpty()) Db.u("UPDATE foto SET etichette = ? WHERE id = ?", x.etichette.entries.joinToString(";") { "${it.key}:${it.value}" }, id)
        Percorsi.miniatura(id).writeBytes(Immagini.jpeg(Immagini.ridimensiona(img, 360)))
        applicaPersone(id, x, persone)
        if (x.manuali.isNotEmpty() || x.rimosse.isNotEmpty()) Db.etichetteManuali(id, x.manuali, x.rimosse.toSet())
        return true
    }

    fun applicaPersone(fotoId: Long, x: FotoP, persone: Map<String, Long>) {
        val auto = x.volti.filter { !it.manuale }
        val ids = ArrayList<Long>()
        Db.q("SELECT id FROM volto WHERE foto_id = ? AND manuale = 0 ORDER BY id", fotoId) { ids += it.getLong(1) }
        for ((i, v) in auto.withIndex()) {
            val vid = ids.getOrNull(i) ?: break
            val p = if (v.escluso) Db.ESCLUSO else v.uid?.let { persone[it] } ?: continue
            Db.u("UPDATE volto SET persona_id = ? WHERE id = ?", p, vid)
        }
        for (v in x.volti.filter { it.manuale }) v.uid?.let { persone[it] }?.let { Db.associa(listOf(fotoId), it) }
    }
}
