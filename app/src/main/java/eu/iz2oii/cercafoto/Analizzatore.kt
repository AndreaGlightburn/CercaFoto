package eu.iz2oii.cercafoto

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Tutta l'analisi avviene sul telefono: ML Kit (modelli inclusi nell'APK) + FaceNet TFLite. */
class Analizzatore(ctx: Context, ocr: Boolean) : Closeable {

    class Volto(val l: Float, val t: Float, val r: Float, val b: Float, val emb: FloatArray?, val attr: Attributi?)
    class Risultato(
        val etichette: List<Pair<String, Int>>, val volti: List<Volto>, val sorriso: Float, val testo: String,
        val colori: Map<String, Int>,
    ) { var dataExif: Long? = null }

    class Attributi(val eta: Float, val maschio: Float, val capelli: String?, val abiti: String?) {
        fun valori() = android.content.ContentValues().apply {
            put("eta", eta); put("maschio", maschio); put("capelli", capelli); put("abiti", abiti)
        }
    }

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build()
    )
    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.05f)
            .build()
    )
    private val testo = if (ocr) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
    private val facenet = FaceNet(ctx)
    private val etaSesso = EtaSesso(ctx)

    /** Attributi di un volto dato il riquadro normalizzato 0..1 */
    fun attributi(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): Attributi? = runCatching {
        val W = bmp.width; val H = bmp.height
        val fw = (r - l) * W; val fh = (b - t) * H
        if (fw < 24 || fh < 24) return@runCatching null
        val mx = fw * 0.1f; val my = fh * 0.1f
        val x0 = max(0, (l * W - mx).toInt()); val y0 = max(0, (t * H - my).toInt())
        val x1 = min(W, (r * W + mx).toInt()); val y1 = min(H, (b * H + my).toInt())
        val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
        val (eta, maschio) = etaSesso.stima(crop)
        crop.recycle()
        Attributi(eta, maschio, Colori.capelli(bmp, l, t, r, b), Colori.abiti(bmp, l, t, r, b))
    }.getOrNull()

    fun colori(bmp: Bitmap) = Colori.foto(bmp)

    fun analizza(bmp: Bitmap): Risultato {
        val img = InputImage.fromBitmap(bmp, 0)
        val et = Tasks.await(labeler.process(img)).map { it.text to (it.confidence * 100).roundToInt() }
        val fs = Tasks.await(faces.process(img))
        var sorriso = 0f
        val volti = fs.mapNotNull { f ->
            val bb = f.boundingBox
            val l = max(0, bb.left); val t = max(0, bb.top)
            val r = min(bmp.width, bb.right); val b = min(bmp.height, bb.bottom)
            val w = r - l; val h = b - t
            if (w <= 4 || h <= 4) return@mapNotNull null
            sorriso = max(sorriso, f.smilingProbability ?: 0f)
            val emb = if (w >= 40 && h >= 40) {
                runCatching {
                    val crop = Bitmap.createBitmap(bmp, l, t, w, h)
                    facenet.embedding(crop).also { crop.recycle() }
                }.getOrNull()
            } else null
            val nl = l / bmp.width.toFloat(); val nt = t / bmp.height.toFloat()
            val nr = r / bmp.width.toFloat(); val nb = b / bmp.height.toFloat()
            Volto(nl, nt, nr, nb, emb, attributi(bmp, nl, nt, nr, nb))
        }
        val txt = testo?.let { runCatching { Tasks.await(it.process(img)).text }.getOrDefault("") } ?: ""
        return Risultato(et, volti, sorriso, txt, colori(bmp))
    }

    override fun close() {
        labeler.close(); faces.close(); testo?.close(); facenet.close(); etaSesso.close()
    }
}

/** Età e sesso apparenti (modelli MIT di shubham0204, addestrati su UTKFace). Input RGB/255. */
class EtaSesso(ctx: Context) : Closeable {
    private val eta = Interpreter(mappa(ctx, "eta.tflite"), Interpreter.Options().setNumThreads(2))
    private val sesso = Interpreter(mappa(ctx, "sesso.tflite"), Interpreter.Options().setNumThreads(2))
    private val inEta = ByteBuffer.allocateDirect(200 * 200 * 3 * 4).order(ByteOrder.nativeOrder())
    private val inSesso = ByteBuffer.allocateDirect(128 * 128 * 3 * 4).order(ByteOrder.nativeOrder())

    private fun riempi(bmp: Bitmap, lato: Int, buf: ByteBuffer) {
        val s = Bitmap.createScaledBitmap(bmp, lato, lato, true)
        val px = IntArray(lato * lato)
        s.getPixels(px, 0, lato, 0, 0, lato, lato)
        if (s !== bmp) s.recycle()
        buf.rewind()
        for (p in px) {
            buf.putFloat(((p shr 16) and 255) / 255f); buf.putFloat(((p shr 8) and 255) / 255f); buf.putFloat((p and 255) / 255f)
        }
        buf.rewind()
    }

    /** ritorna (età in anni, probabilità maschio 0..1) */
    fun stima(volto: Bitmap): Pair<Float, Float> {
        riempi(volto, 200, inEta)
        val oe = Array(1) { FloatArray(1) }
        eta.run(inEta, oe)
        riempi(volto, 128, inSesso)
        val os = Array(1) { FloatArray(2) }
        sesso.run(inSesso, os)
        val tot = os[0][0] + os[0][1]
        return (oe[0][0] * 116f) to (if (tot > 0f) os[0][0] / tot else 0.5f)
    }

    override fun close() { eta.close(); sesso.close() }
}

fun mappa(ctx: Context, nome: String): java.nio.MappedByteBuffer {
    val fd = ctx.assets.openFd(nome)
    return FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
}

/** FaceNet 512 (Apache 2.0, vedi NOTICE). Input 160x160 RGB standardizzato, output vettore 512. */
class FaceNet(ctx: Context) : Closeable {
    private val interp: Interpreter
    private val input = ByteBuffer.allocateDirect(160 * 160 * 3 * 4).order(ByteOrder.nativeOrder())
    private val px = IntArray(160 * 160)
    private val f = FloatArray(160 * 160 * 3)

    init {
        val fd = ctx.assets.openFd("facenet_512.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interp = Interpreter(buf, Interpreter.Options().setNumThreads(4))
    }

    fun embedding(face: Bitmap): FloatArray {
        val s = Bitmap.createScaledBitmap(face, 160, 160, true)
        s.getPixels(px, 0, 160, 0, 0, 160, 160)
        if (s !== face) s.recycle()
        var k = 0
        var somma = 0.0
        for (p in px) {
            val r = ((p shr 16) and 255).toFloat(); val g = ((p shr 8) and 255).toFloat(); val b = (p and 255).toFloat()
            f[k++] = r; f[k++] = g; f[k++] = b
            somma += r + g + b
        }
        val media = (somma / f.size).toFloat()
        var v = 0.0
        for (x in f) v += ((x - media) * (x - media)).toDouble()
        val std = max(sqrt(v / f.size).toFloat(), 1f / sqrt(f.size.toFloat()))
        input.rewind()
        for (x in f) input.putFloat((x - media) / std)
        input.rewind()
        val out = Array(1) { FloatArray(512) }
        interp.run(input, out)
        return normalizza(out[0])
    }

    override fun close() = interp.close()
}

fun normalizza(v: FloatArray): FloatArray {
    var n = 0f
    for (x in v) n += x * x
    n = sqrt(n)
    if (n > 0f) for (i in v.indices) v[i] /= n
    return v
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
