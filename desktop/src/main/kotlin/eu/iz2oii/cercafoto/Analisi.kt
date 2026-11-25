package eu.iz2oii.cercafoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.util.TimeZone
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Attributi(val eta: Float, val maschio: Float, val capelli: String?, val abiti: String?)

// ---------------------------------------------------------------- immagini

object Immagini {
    /** Legge un'immagine riducendola già in decodifica (sottocampionamento) e applica l'orientamento EXIF */
    fun leggi(f: File, lato: Int): BufferedImage? {
        val img = ImageIO.createImageInputStream(f)?.use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
            try {
                reader.input = iis
                val w = reader.getWidth(0); val h = reader.getHeight(0)
                val passo = max(1, max(w, h) / (lato * 2))
                val p = reader.defaultReadParam.apply { if (passo > 1) setSourceSubsampling(passo, passo, 0, 0) }
                reader.read(0, p)
            } finally { reader.dispose() }
        } ?: return null
        return ridimensiona(orienta(rgb(img), orientamento(f)), lato)
    }

    /** Dimensioni dell'originale come si vede (orientamento EXIF applicato) */
    fun dimensioni(f: File): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(f)?.use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
            try {
                reader.input = iis
                val w = reader.getWidth(0); val h = reader.getHeight(0)
                if (orientamento(f) >= 5) h to w else w to h
            } finally { reader.dispose() }
        }
    }.getOrNull()

    /** Legge solo una zona dell'originale (coordinate dell'immagine orientata), sottocampionata di [passo] */
    fun zona(f: File, l: Int, t: Int, r: Int, b: Int, passo: Int, orient: Int): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(f)?.use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
            try {
                reader.input = iis
                val rw = reader.getWidth(0); val rh = reader.getHeight(0)
                val g = rettangoloGrezzo(androidx.compose.ui.unit.IntRect(l, t, r, b), rw, rh, orient) ?: return null
                val p = reader.defaultReadParam.apply {
                    sourceRegion = java.awt.Rectangle(g.left, g.top, g.width, g.height)
                    if (passo > 1) setSourceSubsampling(passo, passo, 0, 0)
                }
                orienta(rgb(reader.read(0, p)), orient)
            } finally { reader.dispose() }
        }
    }.getOrNull()

    /** Legge l'originale intero orientato (per salvare una copia modificata) */
    fun intera(f: File): BufferedImage? = runCatching {
        val img = ImageIO.read(f) ?: return null
        orienta(rgb(img), orientamento(f))
    }.getOrNull()

    fun orientamento(f: File): Int = runCatching {
        ImageMetadataReader.readMetadata(f).getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
    }.getOrNull() ?: 1

    fun dataScatto(f: File): Long? = runCatching {
        ImageMetadataReader.readMetadata(f).getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)?.getDateOriginal(TimeZone.getDefault())?.time
    }.getOrNull()

    /** Dettagli EXIF leggibili */
    fun exif(f: File): List<Pair<String, String>> = runCatching {
        val m = ImageMetadataReader.readMetadata(f)
        val out = ArrayList<Pair<String, String>>()
        m.getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.let { d ->
            val cam = listOfNotNull(d.getString(ExifIFD0Directory.TAG_MAKE), d.getString(ExifIFD0Directory.TAG_MODEL)).joinToString(" ").trim()
            if (cam.isNotEmpty()) out += "Fotocamera" to cam
        }
        m.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation?.takeIf { !it.isZero }?.let {
            out += "Posizione" to "%.5f, %.5f".format(java.util.Locale.US, it.latitude, it.longitude)
        }
        out
    }.getOrDefault(emptyList())

    private fun rgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val d = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = d.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, d.width, d.height); g.drawImage(src, 0, 0, null); g.dispose()
        return d
    }

    fun orienta(src: BufferedImage, o: Int): BufferedImage {
        if (o <= 1 || o > 8) return src
        val w = src.width; val h = src.height
        val scambia = o >= 5
        val t = AffineTransform()
        when (o) {
            2 -> { t.scale(-1.0, 1.0); t.translate(-w.toDouble(), 0.0) }
            3 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h.toDouble()) }
            5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            6 -> { t.translate(h.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h.toDouble(), 0.0); t.translate(0.0, w.toDouble()); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w.toDouble()); t.rotate(3 * Math.PI / 2) }
        }
        val d = BufferedImage(if (scambia) h else w, if (scambia) w else h, BufferedImage.TYPE_INT_RGB)
        val g = d.createGraphics(); g.drawImage(src, t, null); g.dispose()
        return d
    }

    fun ridimensiona(src: BufferedImage, lato: Int): BufferedImage {
        val s = lato.toDouble() / max(src.width, src.height)
        if (s >= 1.0) return src
        return scala(src, max(1, (src.width * s).toInt()), max(1, (src.height * s).toInt()))
    }

    fun scala(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val d = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = d.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null); g.dispose()
        return d
    }

    fun jpeg(img: BufferedImage, qualita: Float = 0.82f): ByteArray {
        val out = ByteArrayOutputStream()
        val w = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            w.output = ios
            val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = qualita }
            w.write(null, IIOImage(img, null, null), p)
        }
        w.dispose()
        return out.toByteArray()
    }

    fun ritaglia(img: BufferedImage, l: Float, t: Float, r: Float, b: Float, margine: Float): BufferedImage? {
        val W = img.width; val H = img.height
        val mx = (r - l) * W * margine; val my = (b - t) * H * margine
        val x0 = max(0, (l * W - mx).toInt()); val y0 = max(0, (t * H - my).toInt())
        val x1 = min(W, (r * W + mx).toInt()); val y1 = min(H, (b * H + my).toInt())
        if (x1 - x0 < 2 || y1 - y0 < 2) return null
        return img.getSubimage(x0, y0, x1 - x0, y1 - y0)
    }
}

// ---------------------------------------------------------------- modelli ONNX

object Modelli {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private fun sessione(nome: String): OrtSession {
        val b = Modelli::class.java.getResourceAsStream("/modelli/$nome")!!.use { it.readBytes() }
        return env.createSession(b, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    }

    private val rilevatore by lazy { sessione("ultraface-640.onnx") }
    private val facenet by lazy { sessione("facenet_512.onnx") }
    private val etaM by lazy { sessione("eta.onnx") }
    private val sessoM by lazy { sessione("sesso.onnx") }

    private fun esegui(s: OrtSession, dati: FloatArray, forma: LongArray): Any {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(dati), forma).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r -> return r[0].value }
        }
    }

    /** Volti trovati: riquadri normalizzati 0..1 (UltraFace RFB-640, licenza MIT) */
    fun rileva(img: BufferedImage, soglia: Float = 0.7f): List<FloatArray> {
        val s = Immagini.scala(img, 640, 480)
        val px = s.getRGB(0, 0, 640, 480, null, 0, 640)
        val n = 640 * 480
        val x = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = px[i]
            x[i] = (((p shr 16) and 255) - 127f) / 128f
            x[n + i] = (((p shr 8) and 255) - 127f) / 128f
            x[2 * n + i] = ((p and 255) - 127f) / 128f
        }
        var punteggi: Array<FloatArray>? = null
        var riquadri: Array<FloatArray>? = null
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, 480, 640)).use { t ->
            rilevatore.run(mapOf(rilevatore.inputNames.first() to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                punteggi = (r[0].value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                riquadri = (r[1].value as Array<Array<FloatArray>>)[0]
            }
        }
        val cand = ArrayList<FloatArray>()
        val sc = punteggi!!; val bx = riquadri!!
        for (i in sc.indices) if (sc[i][1] > soglia) {
            val b = bx[i]
            cand += floatArrayOf(b[0].coerceIn(0f, 1f), b[1].coerceIn(0f, 1f), b[2].coerceIn(0f, 1f), b[3].coerceIn(0f, 1f), sc[i][1])
        }
        // soppressione dei riquadri sovrapposti
        cand.sortByDescending { it[4] }
        val out = ArrayList<FloatArray>()
        for (c in cand) if (out.none { ScambioFormato.iou(it[0], it[1], it[2], it[3], c[0], c[1], c[2], c[3]) > 0.3f }) out += c
        return out
    }

    /** Vettore FaceNet 512 normalizzato: stessa pre-elaborazione dell'app Android */
    fun embedding(volto: BufferedImage): FloatArray {
        val s = Immagini.scala(volto, 160, 160)
        val px = s.getRGB(0, 0, 160, 160, null, 0, 160)
        val f = FloatArray(160 * 160 * 3)
        var k = 0; var somma = 0.0
        for (p in px) {
            val r = ((p shr 16) and 255).toFloat(); val g = ((p shr 8) and 255).toFloat(); val b = (p and 255).toFloat()
            f[k++] = r; f[k++] = g; f[k++] = b; somma += r + g + b
        }
        val media = (somma / f.size).toFloat()
        var v = 0.0
        for (x in f) v += ((x - media) * (x - media)).toDouble()
        val std = max(sqrt(v / f.size).toFloat(), 1f / sqrt(f.size.toFloat()))
        for (i in f.indices) f[i] = (f[i] - media) / std
        @Suppress("UNCHECKED_CAST")
        val out = (esegui(facenet, f, longArrayOf(1, 160, 160, 3)) as Array<FloatArray>)[0]
        return normalizza(out)
    }

    private fun rgb01(img: BufferedImage, lato: Int): FloatArray {
        val s = Immagini.scala(img, lato, lato)
        val px = s.getRGB(0, 0, lato, lato, null, 0, lato)
        val f = FloatArray(lato * lato * 3); var k = 0
        for (p in px) { f[k++] = ((p shr 16) and 255) / 255f; f[k++] = ((p shr 8) and 255) / 255f; f[k++] = (p and 255) / 255f }
        return f
    }

    /** (età in anni, probabilità maschio) */
    fun etaSesso(volto: BufferedImage): Pair<Float, Float> {
        @Suppress("UNCHECKED_CAST")
        val e = (esegui(etaM, rgb01(volto, 200), longArrayOf(1, 200, 200, 3)) as Array<FloatArray>)[0][0]
        @Suppress("UNCHECKED_CAST")
        val g = (esegui(sessoM, rgb01(volto, 128), longArrayOf(1, 128, 128, 3)) as Array<FloatArray>)[0]
        val tot = g[0] + g[1]
        return e * 116f to (if (tot > 0f) g[0] / tot else 0.5f)
    }
}

// ---------------------------------------------------------------- colori (stessa logica dell'app Android)

object Colori {
    val NOMI = listOf("rosso", "arancione", "giallo", "verde", "azzurro", "blu", "viola", "rosa", "marrone", "nero", "bianco", "grigio")

    fun colore(p: Int): String {
        val hsv = Color.RGBtoHSB((p shr 16) and 255, (p shr 8) and 255, p and 255, null)
        val h = hsv[0] * 360f; val s = hsv[1]; val v = hsv[2]
        if (v < 0.2f) return "nero"
        if (s < 0.18f) return if (v > 0.8f) "bianco" else if (v < 0.3f) "nero" else "grigio"
        return when {
            h < 15f || h >= 340f -> if (s < 0.45f && v > 0.7f) "rosa" else if (v < 0.4f) "marrone" else "rosso"
            h < 45f -> if (v < 0.6f) "marrone" else if (s < 0.4f && v > 0.8f) "rosa" else "arancione"
            h < 70f -> if (v < 0.45f) "marrone" else "giallo"
            h < 165f -> "verde"
            h < 200f -> "azzurro"
            h < 255f -> "blu"
            h < 290f -> "viola"
            else -> "rosa"
        }
    }

    private fun capello(p: Int): String? {
        val hsv = Color.RGBtoHSB((p shr 16) and 255, (p shr 8) and 255, p and 255, null)
        val h = hsv[0] * 360f; val s = hsv[1]; val v = hsv[2]
        if (v < 0.22f) return "nero"
        if (s < 0.18f) return if (v > 0.45f) "grigio" else "nero"
        return when {
            h >= 345f || h < 20f -> if (s > 0.45f && v > 0.3f) "rosso" else "castano"
            h < 50f -> if (v < 0.45f) "castano" else if (s < 0.65f && v > 0.55f) "biondo" else "castano"
            else -> null
        }
    }

    private fun conta(img: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, f: (Int) -> String?): Pair<Map<String, Int>, Int> {
        val m = HashMap<String, Int>()
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return m to 0
        val passo = max(1, sqrt((w * h) / 2500.0).toInt())
        var n = 0
        var y = y0
        while (y < y1) { var x = x0; while (x < x1) { n++; f(img.getRGB(x, y))?.let { m[it] = (m[it] ?: 0) + 1 }; x += passo }; y += passo }
        return m to n
    }

    fun foto(img: BufferedImage): Map<String, Int> {
        val (m, n) = conta(img, 0, 0, img.width, img.height, ::colore)
        if (n == 0) return emptyMap()
        return m.mapValues { it.value * 100 / n }.filter { it.value >= 5 }
    }

    fun capelli(img: BufferedImage, l: Float, t: Float, r: Float, b: Float): String? {
        val W = img.width; val H = img.height
        val fw = (r - l) * W; val fh = (b - t) * H
        val x0 = ((l * W) + fw * 0.15f).toInt(); val x1 = ((r * W) - fw * 0.15f).toInt()
        val y1 = (t * H + fh * 0.02f).toInt(); val y0 = max(0, (t * H - fh * 0.3f).toInt())
        if (y1 - y0 < fh * 0.12f) return null
        val (m, n) = conta(img, max(0, x0), y0, min(W, x1), min(H, y1), ::capello)
        val top = m.maxByOrNull { it.value } ?: return null
        return if (n > 0 && top.value * 100 / n >= 35) top.key else null
    }

    fun abiti(img: BufferedImage, l: Float, t: Float, r: Float, b: Float): String? {
        val W = img.width; val H = img.height
        val fw = (r - l) * W; val fh = (b - t) * H
        val x0 = max(0, (l * W - fw * 0.3f).toInt()); val x1 = min(W, (r * W + fw * 0.3f).toInt())
        val y0 = (b * H + fh * 0.3f).toInt(); val y1 = min(H, (b * H + fh * 1.6f).toInt())
        if (y1 - y0 < fh * 0.5f) return null
        val (m, n) = conta(img, x0, y0, x1, y1, ::colore)
        val top = m.maxByOrNull { it.value } ?: return null
        return if (n > 0 && top.value * 100 / n >= 30) top.key else null
    }
}

// ---------------------------------------------------------------- analisi di una foto

object Analisi {
    class Risultato(val volti: List<Db.VoltoNuovo>, val sorriso: Float, val colori: Map<String, Int>, val miniatura: ByteArray,
                    val clip: FloatArray?, val etichette: Map<String, Int>)

    fun analizza(f: File, lato: Int = 1280): Risultato? {
        val img = Immagini.leggi(f, lato) ?: return null
        val volti = Modelli.rileva(img).mapNotNull { b -> volto(img, b[0], b[1], b[2], b[3]) }
        val clip = if (Clip.disponibile) runCatching { Clip.immagine(img) }.getOrNull() else null
        val et = clip?.let { runCatching { Clip.etichette(it) }.getOrDefault(emptyMap()) } ?: emptyMap()
        return Risultato(volti, 0f, Colori.foto(img), Immagini.jpeg(Immagini.ridimensiona(img, 360)), clip, et)
    }

    fun volto(img: BufferedImage, l: Float, t: Float, r: Float, b: Float): Db.VoltoNuovo? {
        val W = img.width; val H = img.height
        val x0 = (l * W).toInt(); val y0 = (t * H).toInt(); val x1 = (r * W).toInt(); val y1 = (b * H).toInt()
        val w = x1 - x0; val h = y1 - y0
        if (w <= 4 || h <= 4) return null
        val crop = img.getSubimage(x0, y0, w, h)
        val emb = if (w >= 40 && h >= 40) runCatching { Modelli.embedding(crop) }.getOrNull() else null
        val attr = if (w >= 24 && h >= 24) runCatching {
            val c = Immagini.ritaglia(img, l, t, r, b, 0.1f)!!
            val (eta, m) = Modelli.etaSesso(c)
            Attributi(eta, m, Colori.capelli(img, l, t, r, b), Colori.abiti(img, l, t, r, b))
        }.getOrNull() else null
        val ritaglio = Immagini.ritaglia(img, l, t, r, b, 0.2f)?.let { Immagini.jpeg(Immagini.ridimensiona(it, 160), 0.85f) }
        return Db.VoltoNuovo(l, t, r, b, emb, attr, ritaglio)
    }
}
