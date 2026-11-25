package eu.iz2oii.cercafoto

// File CONDIVISO tra Android e Windows (copia identica in app/src/main/java/eu/iz2oii/cercafoto/IndicePortatile.kt).
// "Indice portatile": i risultati dell'analisi salvati sul disco stesso, in <radice>/.cercafoto/indice-<windows|android>.gz,
// così l'altro dispositivo che legge lo stesso disco non deve rianalizzare le foto. Solo dati, nessuna foto.

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object IndicePortatile {
    const val CARTELLA = ".cercafoto"
    /** un file per tipo di dispositivo, così telefono e PC non si sovrascrivono a vicenda */
    fun nomeFile(origine: String) = "indice-$origine.gz"
    fun eIndice(nome: String) = nome.startsWith("indice-") && nome.endsWith(".gz")
    const val VERSIONE = 1

    class VoltoP(
        val l: Float, val t: Float, val r: Float, val b: Float,
        val emb: FloatArray?, val eta: Float?, val maschio: Float?, val capelli: String?, val abiti: String?,
        /** uid della persona con nome, se assegnato */
        val uid: String?, val escluso: Boolean = false, val manuale: Boolean = false,
    )

    class FotoP(
        /** percorso relativo alla radice del disco/cartella, separatore "/" */
        val p: String, val s: Long, val d: Long,
        val etichette: Map<String, Int>, val colori: Map<String, Int>, val testo: String?, val sorriso: Float,
        val volti: List<VoltoP>, val clip: FloatArray?, val manuali: List<String>, val rimosse: List<String>,
    )

    class MomentoP(val t: Long, val foto: FotoP)
    class VideoP(val p: String, val s: Long, val d: Long, val durata: Long, val momenti: List<MomentoP>)
    class PersonaP(val uid: String, val nome: String, val nascosta: Boolean)

    class Pacchetto(
        val origine: String, val dispositivo: String, val creato: Long,
        val persone: List<PersonaP>, val foto: List<FotoP>, val video: List<VideoP>,
    ) {
        private val perChiave by lazy { foto.associateBy { chiave(it.p, it.s) } }
        private val videoPerChiave by lazy { video.associateBy { chiave(it.p, it.s) } }
        fun foto(rel: String, dimensione: Long) = perChiave[chiave(rel, dimensione)]
        fun video(rel: String, dimensione: Long) = videoPerChiave[chiave(rel, dimensione)]
        val nomi by lazy { persone.associateBy { it.uid } }
    }

    /** Abbinamento: percorso relativo (senza maiuscole) + dimensione; la data dei file cambia tra file system diversi */
    fun chiave(rel: String, dimensione: Long) = normalizzaPercorso(rel).lowercase() + "|" + dimensione
    fun normalizzaPercorso(rel: String) = rel.replace('\\', '/').trim('/')

    // ------------------------------------------------------------ scrittura

    fun scrivi(p: Pacchetto, out: OutputStream) {
        val o = JSONObject()
        o.put("formato", "cercafoto-indice"); o.put("versione", VERSIONE)
        o.put("origine", p.origine); o.put("dispositivo", p.dispositivo); o.put("creato", p.creato)
        o.put("persone", JSONArray().apply { for (x in p.persone) put(JSONObject().put("uid", x.uid).put("nome", x.nome).put("nascosta", x.nascosta)) })
        o.put("foto", JSONArray().apply { for (f in p.foto) put(fotoJ(f)) })
        o.put("video", JSONArray().apply {
            for (v in p.video) put(JSONObject().put("p", v.p).put("s", v.s).put("d", v.d).put("durata", v.durata)
                .put("momenti", JSONArray().apply { for (m in v.momenti) put(fotoJ(m.foto).put("t", m.t)) }))
        })
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { it.write(o.toString()) }
    }

    private fun mappa(m: Map<String, Int>) = m.entries.joinToString(";") { "${it.key}:${it.value}" }

    private fun fotoJ(f: FotoP): JSONObject = JSONObject().apply {
        put("p", f.p); put("s", f.s); put("d", f.d)
        if (f.etichette.isNotEmpty()) put("e", mappa(f.etichette))
        if (f.colori.isNotEmpty()) put("c", mappa(f.colori))
        if (!f.testo.isNullOrBlank()) put("x", f.testo)
        if (f.sorriso > 0f) put("sr", f.sorriso.toDouble())
        f.clip?.takeIf { it.isNotEmpty() }?.let { put("k", f16(it)) }
        if (f.manuali.isNotEmpty()) put("man", JSONArray(f.manuali))
        if (f.rimosse.isNotEmpty()) put("rim", JSONArray(f.rimosse))
        if (f.volti.isNotEmpty()) put("v", JSONArray().apply {
            for (v in f.volti) put(JSONObject().apply {
                put("b", JSONArray().put(v.l.toDouble()).put(v.t.toDouble()).put(v.r.toDouble()).put(v.b.toDouble()))
                v.emb?.let { put("f", f16(it)) }
                v.eta?.let { put("eta", it.toDouble()) }; v.maschio?.let { put("m", it.toDouble()) }
                v.capelli?.let { put("h", it) }; v.abiti?.let { put("ab", it) }
                v.uid?.let { put("u", it) }
                if (v.escluso) put("esc", true)
                if (v.manuale) put("man", true)
            })
        })
    }

    // ------------------------------------------------------------ lettura

    fun leggi(input: InputStream): Pacchetto {
        val testo = GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val o = JSONObject(testo)
        require(o.optString("formato") == "cercafoto-indice") { "Non è un indice di Cerca Foto" }
        val persone = o.optJSONArray("persone")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { PersonaP(it.getString("uid"), it.getString("nome"), it.optBoolean("nascosta")) } } } ?: emptyList()
        val foto = o.optJSONArray("foto")?.let { a -> (0 until a.length()).map { fotoDa(a.getJSONObject(it)) } } ?: emptyList()
        val video = o.optJSONArray("video")?.let { a ->
            (0 until a.length()).map { i ->
                val v = a.getJSONObject(i)
                val mm = v.optJSONArray("momenti")
                VideoP(v.getString("p"), v.getLong("s"), v.optLong("d"), v.optLong("durata"),
                    if (mm == null) emptyList() else (0 until mm.length()).map { j -> mm.getJSONObject(j).let { MomentoP(it.getLong("t"), fotoDa(it)) } })
            }
        } ?: emptyList()
        return Pacchetto(o.optString("origine"), o.optString("dispositivo"), o.optLong("creato"), persone, foto, video)
    }

    private fun mappaDa(s: String?): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        s?.split(';')?.forEach { x -> val i = x.lastIndexOf(':'); if (i > 0) m[x.substring(0, i)] = x.substring(i + 1).toIntOrNull() ?: 0 }
        return m
    }

    private fun lista(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun fotoDa(j: JSONObject): FotoP {
        val vv = j.optJSONArray("v")
        val volti = if (vv == null) emptyList() else (0 until vv.length()).map { i ->
            val v = vv.getJSONObject(i); val b = v.getJSONArray("b")
            VoltoP(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(),
                v.optString("f").takeIf { it.isNotEmpty() }?.let { daF16(it) },
                if (v.has("eta")) v.getDouble("eta").toFloat() else null, if (v.has("m")) v.getDouble("m").toFloat() else null,
                v.optString("h").ifEmpty { null }, v.optString("ab").ifEmpty { null }, v.optString("u").ifEmpty { null },
                v.optBoolean("esc"), v.optBoolean("man"))
        }
        return FotoP(j.getString("p"), j.optLong("s"), j.optLong("d"), mappaDa(j.optString("e")), mappaDa(j.optString("c")),
            j.optString("x").ifEmpty { null }, j.optDouble("sr", 0.0).toFloat(), volti,
            j.optString("k").takeIf { it.isNotEmpty() }?.let { daF16(it) }, lista(j.optJSONArray("man")), lista(j.optJSONArray("rim")))
    }

    // ------------------------------------------------------------ vettori in mezza precisione (base64)

    fun f16(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putShort(aHalf(x))
        return Base64.getEncoder().encodeToString(bb.array())
    }

    fun daF16(s: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / 2) { daHalf(bb.getShort().toInt() and 0xffff) }
    }

    fun aHalf(v: Float): Short {
        val bits = java.lang.Float.floatToIntBits(v)
        val segno = (bits ushr 16) and 0x8000
        val esp = ((bits ushr 23) and 0xff) - 127 + 15
        var mant = bits and 0x7fffff
        if (esp <= 0) {
            if (esp < -10) return segno.toShort()
            mant = (mant or 0x800000) shr (1 - esp)
            return (segno or ((mant + 0x1000) shr 13)).toShort()
        }
        if (esp >= 31) return (segno or 0x7c00).toShort()
        return (segno or ((esp shl 10) + ((mant + 0x1000) shr 13))).toShort()
    }

    fun daHalf(h: Int): Float {
        val s = (h and 0x8000) shl 16
        val e = (h ushr 10) and 0x1f
        val m = h and 0x3ff
        if (e == 0) {
            if (m == 0) return java.lang.Float.intBitsToFloat(s)
            var mm = m; var ee = -14
            while (mm and 0x400 == 0) { mm = mm shl 1; ee-- }
            return java.lang.Float.intBitsToFloat(s or ((ee + 127) shl 23) or ((mm and 0x3ff) shl 13))
        }
        if (e == 31) return java.lang.Float.intBitsToFloat(s or 0x7f800000 or (m shl 13))
        return java.lang.Float.intBitsToFloat(s or ((e - 15 + 127) shl 23) or (m shl 13))
    }
}
