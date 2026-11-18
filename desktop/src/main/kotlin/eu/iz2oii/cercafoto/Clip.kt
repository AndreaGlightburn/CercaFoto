package eu.iz2oii.cercafoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ricerca libera per descrizione (CLIP multilingue, in locale):
 * testo = sentence-transformers/clip-ViT-B-32-multilingual-v1 (Apache 2.0), immagine = openai/clip-vit-base-patch32 (MIT).
 * Entrambi convertiti in ONNX int8 (desktop/strumenti/esporta_clip.py).
 */
object Clip {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    val disponibile: Boolean by lazy { Clip::class.java.getResource("/modelli/clip_immagine.onnx") != null }

    private fun sessione(nome: String): OrtSession {
        val b = Clip::class.java.getResourceAsStream("/modelli/$nome")!!.use { it.readBytes() }
        return env.createSession(b, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    }
    private val sImg by lazy { sessione("clip_immagine.onnx") }
    private val sTesto by lazy { sessione("clip_testo.onnx") }

    // ------------------------------------------------------------ immagine

    private val MEDIA = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val DEV = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /** Vettore 512 normalizzato: lato corto a 224 (bicubico), ritaglio centrale 224×224 */
    fun immagine(src: BufferedImage): FloatArray {
        val s = 224.0 / minOf(src.width, src.height)
        val w = max(224, (src.width * s).roundToInt()); val h = max(224, (src.height * s).roundToInt())
        val r = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = r.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null); g.dispose()
        val l = (w - 224) / 2; val t = (h - 224) / 2
        val px = r.getRGB(l, t, 224, 224, null, 0, 224)
        val n = 224 * 224
        val x = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            x[i] = (((p shr 16) and 255) / 255f - MEDIA[0]) / DEV[0]
            x[n + i] = (((p shr 8) and 255) / 255f - MEDIA[1]) / DEV[1]
            x[2 * n + i] = ((p and 255) / 255f - MEDIA[2]) / DEV[2]
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, 224, 224)).use { tt ->
            sImg.run(mapOf("pixel_values" to tt)).use { res ->
                @Suppress("UNCHECKED_CAST")
                return normalizza((res[0].value as Array<FloatArray>)[0])
            }
        }
    }

    // ------------------------------------------------------------ testo

    private val vocab: Map<String, Int> by lazy {
        val m = HashMap<String, Int>(130000)
        Clip::class.java.getResourceAsStream("/modelli/vocab.txt")!!.bufferedReader(Charsets.UTF_8).useLines { l ->
            l.forEachIndexed { i, s -> m[s] = i }
        }
        m
    }

    private fun punteggiatura(c: Int): Boolean {
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        val t = Character.getType(c)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() || t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun cjk(o: Int) = o in 0x4E00..0x9FFF || o in 0x3400..0x4DBF || o in 0x20000..0x2A6DF || o in 0x2A700..0x2B73F ||
        o in 0x2B740..0x2B81F || o in 0x2B820..0x2CEAF || o in 0xF900..0xFAFF || o in 0x2F800..0x2FA1F

    /** Tokenizzatore WordPiece del modello (multilingue, maiuscole e accenti mantenuti) */
    fun token(testo: String): IntArray {
        val sb = StringBuilder()
        var i = 0
        while (i < testo.length) {
            val c = testo.codePointAt(i); i += Character.charCount(c)
            val tipo = Character.getType(c)
            val controllo = tipo == Character.CONTROL.toInt() || tipo == Character.FORMAT.toInt() || tipo == Character.UNASSIGNED.toInt() ||
                tipo == Character.PRIVATE_USE.toInt() || tipo == Character.SURROGATE.toInt()
            when {
                c == 0 || c == 0xFFFD -> {}
                c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || tipo == Character.SPACE_SEPARATOR.toInt() -> sb.append(' ')
                controllo -> {}
                cjk(c) -> { sb.append(' '); sb.appendCodePoint(c); sb.append(' ') }
                else -> sb.appendCodePoint(c)
            }
        }
        val parole = ArrayList<String>()
        for (w in sb.toString().split(' ')) {
            if (w.isEmpty()) continue
            val cur = StringBuilder()
            var k = 0
            while (k < w.length) {
                val c = w.codePointAt(k); k += Character.charCount(c)
                if (punteggiatura(c)) { if (cur.isNotEmpty()) { parole += cur.toString(); cur.clear() }; parole += String(Character.toChars(c)) }
                else cur.appendCodePoint(c)
            }
            if (cur.isNotEmpty()) parole += cur.toString()
        }
        val unk = vocab["[UNK]"]!!
        val ids = ArrayList<Int>()
        ids += vocab["[CLS]"]!!
        for (w in parole) {
            if (w.codePointCount(0, w.length) > 100) { ids += unk; continue }
            val sub = ArrayList<Int>(); var start = 0; var ok = true
            while (start < w.length) {
                var end = w.length; var trovato: Int? = null
                while (start < end) {
                    val p = (if (start > 0) "##" else "") + w.substring(start, end)
                    val v = vocab[p]
                    if (v != null) { trovato = v; break }
                    end--
                    if (end > start && Character.isLowSurrogate(w[end])) end--
                }
                if (trovato == null) { ok = false; break }
                sub += trovato; start = end
            }
            if (ok) ids += sub else ids += unk
        }
        val fino = ids.take(127).toMutableList()
        fino += vocab["[SEP]"]!!
        return fino.toIntArray()
    }

    /** Vettori 512 normalizzati delle frasi */
    fun testo(frasi: List<String>): List<FloatArray> {
        if (frasi.isEmpty()) return emptyList()
        val out = ArrayList<FloatArray>()
        for (blocco in frasi.chunked(32)) {
            val toks = blocco.map { token(it) }
            val n = toks.maxOf { it.size }
            val ids = LongArray(blocco.size * n); val mask = LongArray(blocco.size * n)
            toks.forEachIndexed { b, t -> t.forEachIndexed { j, v -> ids[b * n + j] = v.toLong(); mask[b * n + j] = 1L } }
            val forma = longArrayOf(blocco.size.toLong(), n.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), forma).use { a ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), forma).use { m ->
                    sTesto.run(mapOf("input_ids" to a, "attention_mask" to m)).use { res ->
                        @Suppress("UNCHECKED_CAST")
                        (res[0].value as Array<FloatArray>).forEach { out += normalizza(it) }
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ etichette automatiche (stesse dell'app Android)

    private val etichetteVettori: List<Pair<String, FloatArray>> by lazy {
        val nomi = Dizionario.termini.keys.toList()
        nomi.zip(testo(nomi.map { "a photo of ${it.lowercase()}" }))
    }

    /** Etichette in stile ML Kit: le più vicine all'immagine, sopra soglia */
    fun etichette(e: FloatArray): Map<String, Int> {
        val s = etichetteVettori.map { it.first to dot(e, it.second) }.sortedByDescending { it.second }
        val best = s.firstOrNull()?.second ?: return emptyMap()
        return s.take(5).filter { it.second >= 0.245f && it.second >= best - 0.03f }.associate { it.first to (it.second * 100).roundToInt() }
    }

    private var ultimaQuery: Pair<String, FloatArray>? = null

    /** Punteggio di ogni foto per una descrizione; restituisce solo quelle pertinenti, dalla migliore */
    fun cerca(descrizione: String, vettori: Map<Long, FloatArray>): List<Pair<Long, Float>> {
        val q = ultimaQuery?.takeIf { it.first == descrizione }?.second ?: testo(listOf(descrizione)).first().also { ultimaQuery = descrizione to it }
        val p = vettori.map { it.key to dot(q, it.value) }.sortedByDescending { it.second }
        val best = p.firstOrNull()?.second ?: return emptyList()
        return p.filter { it.second >= 0.215f && it.second >= best - 0.06f }.take(1000)
    }
}
