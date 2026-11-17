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

    class Volto(val l: Float, val t: Float, val r: Float, val b: Float, val emb: FloatArray?)
    class Risultato(val etichette: List<Pair<String, Int>>, val volti: List<Volto>, val sorriso: Float, val testo: String)

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
            Volto(l / bmp.width.toFloat(), t / bmp.height.toFloat(), r / bmp.width.toFloat(), b / bmp.height.toFloat(), emb)
        }
        val txt = testo?.let { runCatching { Tasks.await(it.process(img)).text }.getOrDefault("") } ?: ""
        return Risultato(et, volti, sorriso, txt)
    }

    override fun close() {
        labeler.close(); faces.close(); testo?.close(); facenet.close()
    }
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
