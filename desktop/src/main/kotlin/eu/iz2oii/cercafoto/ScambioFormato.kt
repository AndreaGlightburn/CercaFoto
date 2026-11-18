package eu.iz2oii.cercafoto

// ATTENZIONE: questo file è identico nell'app Android e nell'app Windows (desktop/…/ScambioFormato.kt).
// Se lo modifichi, copialo anche nell'altra.

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * File di scambio ".cercafoto" (JSON compresso gzip) tra Cerca Foto Android e Windows.
 * Non contiene foto: solo persone (nome + campioni del vettore volto FaceNet 512) e correzioni manuali per foto.
 * Le foto si riconoscono per nome file + dimensione, con l'impronta SHA-256 come verifica.
 */
object ScambioFormato {
    const val VERSIONE = 1

    data class PersonaX(val uid: String, val nome: String, val nascosta: Boolean, val campioni: List<FloatArray>)
    data class VoltoX(val l: Float, val t: Float, val r: Float, val b: Float, val uid: String?, val escluso: Boolean, val manuale: Boolean)
    data class FotoX(
        val nome: String, val dimensione: Long, val data: Long, val sha256: String?,
        val etichette: List<String>, val rimosse: List<String>, val volti: List<VoltoX>,
    )
    data class Pacchetto(val origine: String, val creato: Long, val persone: List<PersonaX>, val foto: List<FotoX>)

    fun vettore(v: FloatArray): String {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) bb.putFloat(x)
        return Base64.getEncoder().encodeToString(bb.array())
    }

    fun vettore(s: String): FloatArray {
        val bb = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bb.remaining() / 4) { bb.getFloat() }
    }

    fun scrivi(p: Pacchetto, out: OutputStream) {
        val j = JSONObject()
        j.put("formato", "cercafoto"); j.put("versione", VERSIONE); j.put("origine", p.origine); j.put("creato", p.creato)
        j.put("persone", JSONArray().apply {
            for (x in p.persone) put(JSONObject().apply {
                put("uid", x.uid); put("nome", x.nome); put("nascosta", x.nascosta)
                put("campioni", JSONArray().apply { x.campioni.forEach { put(vettore(it)) } })
            })
        })
        j.put("foto", JSONArray().apply {
            for (f in p.foto) put(JSONObject().apply {
                put("nome", f.nome); put("dimensione", f.dimensione); put("data", f.data)
                f.sha256?.let { put("sha256", it) }
                if (f.etichette.isNotEmpty()) put("etichette", JSONArray(f.etichette))
                if (f.rimosse.isNotEmpty()) put("rimosse", JSONArray(f.rimosse))
                if (f.volti.isNotEmpty()) put("volti", JSONArray().apply {
                    for (v in f.volti) put(JSONObject().apply {
                        put("box", JSONArray().apply { put(v.l.toDouble()); put(v.t.toDouble()); put(v.r.toDouble()); put(v.b.toDouble()) })
                        v.uid?.let { put("persona", it) }
                        if (v.escluso) put("escluso", true)
                        if (v.manuale) put("manuale", true)
                    })
                })
            })
        })
        GZIPOutputStream(out).use { it.write(j.toString().toByteArray(Charsets.UTF_8)) }
    }

    fun leggi(input: InputStream): Pacchetto {
        val testo = GZIPInputStream(input).use { it.readBytes().toString(Charsets.UTF_8) }
        val j = JSONObject(testo)
        require(j.optString("formato") == "cercafoto") { "Non è un file di Cerca Foto" }
        fun lista(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        val persone = j.optJSONArray("persone")?.let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                PersonaX(o.getString("uid"), o.getString("nome"), o.optBoolean("nascosta"), lista(o.optJSONArray("campioni")).map { vettore(it) })
            }
        } ?: emptyList()
        val foto = j.optJSONArray("foto")?.let { a ->
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val volti = o.optJSONArray("volti")?.let { va ->
                    List(va.length()) { k ->
                        val v = va.getJSONObject(k); val b = v.getJSONArray("box")
                        VoltoX(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(),
                            v.optString("persona").ifEmpty { null }, v.optBoolean("escluso"), v.optBoolean("manuale"))
                    }
                } ?: emptyList()
                FotoX(o.getString("nome"), o.optLong("dimensione"), o.optLong("data"), o.optString("sha256").ifEmpty { null },
                    lista(o.optJSONArray("etichette")), lista(o.optJSONArray("rimosse")), volti)
            }
        } ?: emptyList()
        return Pacchetto(j.optString("origine"), j.optLong("creato"), persone, foto)
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        input.use { while (true) { val n = it.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Somiglianza di un volto con una persona: il massimo sui campioni (coseno, vettori normalizzati) */
    fun somiglianza(v: FloatArray, campioni: List<FloatArray>): Float {
        var m = -1f
        for (c in campioni) { var s = 0f; for (i in v.indices) s += v[i] * c[i]; if (s > m) m = s }
        return m
    }

    /** Sovrapposizione di due riquadri normalizzati */
    fun iou(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float): Float {
        val il = maxOf(l1, l2); val itop = maxOf(t1, t2); val ir = minOf(r1, r2); val ib = minOf(b1, b2)
        val inter = maxOf(0f, ir - il) * maxOf(0f, ib - itop)
        val uni = (r1 - l1) * (b1 - t1) + (r2 - l2) * (b2 - t2) - inter
        return if (uni > 0f) inter / uni else 0f
    }
}
