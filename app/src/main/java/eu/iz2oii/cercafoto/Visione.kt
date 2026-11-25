package eu.iz2oii.cercafoto

// File CONDIVISO tra Android e Windows (copia identica in desktop/src/main/kotlin/eu/iz2oii/cercafoto/Visione.kt):
// visualizzazione con zoom, spostamento, lente d'ingrandimento e regolazioni di luce/colore.

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- zoom

class ZoomStato {
    var scala by mutableFloatStateOf(1f)
    var spost by mutableStateOf(Offset.Zero)
    val ingrandita get() = scala > 1.01f
    fun azzera() { scala = 1f; spost = Offset.Zero }
    // impostati dalla vista: servono per zoom da tastiera/pulsanti
    internal var area = IntSize.Zero
    internal var fit = IntSize.Zero
    internal var massima = 5f
    internal var larg = 0

    /** Ingrandisce di [z] tenendo fermo il punto [c] (coordinate della vista) e poi sposta di [pan] */
    fun applica(z: Float, pan: Offset = Offset.Zero, c: Offset = Offset(area.width / 2f, area.height / 2f)) {
        if (area == IntSize.Zero) return
        val s1 = (scala * z).coerceIn(1f, massima)
        val zr = s1 / scala
        val centro = Offset(area.width / 2f, area.height / 2f)
        var o = c - centro - (c - centro - spost) * zr + pan
        val mx = max(0f, (fit.width * s1 - area.width) / 2f)
        val my = max(0f, (fit.height * s1 - area.height) / 2f)
        o = Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        scala = s1; spost = o
    }
}

/** Parte di immagine decodificata a piena risoluzione (coordinate dell'originale già orientato) */
private class Tassello(val r: IntRect, val img: ImageBitmap, val passo: Int)

/**
 * Converte un rettangolo dell'immagine orientata (come si vede) nel rettangolo dei pixel grezzi del file,
 * dati l'orientamento EXIF (1, 3, 6, 8) e le dimensioni grezze. null per gli orientamenti specchiati.
 */
fun rettangoloGrezzo(r: IntRect, rw: Int, rh: Int, orient: Int): IntRect? = when (orient) {
    0, 1 -> r
    3 -> IntRect(rw - r.right, rh - r.bottom, rw - r.left, rh - r.top)
    6 -> IntRect(r.top, rh - r.right, r.bottom, rh - r.left)
    8 -> IntRect(rw - r.bottom, r.left, rw - r.top, r.right)
    else -> null
}

fun gradiOrientamento(orient: Int) = when (orient) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }

/**
 * Foto ingrandibile: pizzica / rotellina / doppio tocco per lo zoom, trascina per spostarti,
 * lente che segue il dito (sopra il dito) o il mouse. Oltre la risoluzione dell'anteprima
 * carica il dettaglio a piena risoluzione della sola zona visibile tramite [dettaglio].
 *
 * @param larg, alt dimensioni dell'originale (già orientato); 0 = sconosciute
 */
@Composable
fun VistaZoom(
    base: ImageBitmap?,
    larg: Int, alt: Int,
    stato: ZoomStato,
    modifier: Modifier = Modifier,
    filtro: ColorFilter? = null,
    lente: Boolean = false,
    tocco: () -> Unit = {},
    dettaglio: (suspend (IntRect, Int) -> ImageBitmap?)? = null,
) {
    var area by remember { mutableStateOf(IntSize.Zero) }
    var tassello by remember(base) { mutableStateOf<Tassello?>(null) }
    var posLente by remember { mutableStateOf<Offset?>(null) }
    var lenteTouch by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lenteAgg by rememberUpdatedState(lente)
    val toccoAgg by rememberUpdatedState(tocco)
    val ingrLente = 2.5f
    val raggio = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }

    val iw = if (larg > 0) larg else base?.width ?: 0
    val ih = if (alt > 0) alt else base?.height ?: 0
    // rettangolo "adatta allo schermo"
    val fs = if (iw > 0 && ih > 0 && area != IntSize.Zero) min(area.width / iw.toFloat(), area.height / ih.toFloat()) else 0f
    val fitW = iw * fs; val fitH = ih * fs
    SideEffect {
        stato.area = area
        stato.fit = IntSize(fitW.roundToInt(), fitH.roundToInt())
        stato.massima = if (fitW > 0) max(5f, iw / fitW * 4f) else 5f
        stato.larg = iw
    }
    if (!lente) posLente = null

    fun rettangolo(): Rect {
        val w = stato.fit.width * stato.scala; val h = stato.fit.height * stato.scala
        val cx = stato.area.width / 2f + stato.spost.x; val cy = stato.area.height / 2f + stato.spost.y
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    // carica il dettaglio della zona visibile quando lo zoom supera la risoluzione dell'anteprima
    LaunchedEffect(base, iw, ih, dettaglio) {
        if (base == null || dettaglio == null || iw <= 0) return@LaunchedEffect
        snapshotFlow { Triple(stato.scala, stato.spost, area) to (lenteAgg && posLente != null) }.collectLatest { (t, conLente) ->
            val (s, _, a) = t
            if (s <= 1.01f && !conLente) { tassello = null; return@collectLatest }
            delay(220)
            if (a == IntSize.Zero) return@collectLatest
            val r = rettangolo()
            val schermoPerPx = r.width / iw * (if (conLente) ingrLente else 1f)   // pixel di schermo per pixel originale
            if (schermoPerPx <= base.width / iw.toFloat() * 1.15f) { tassello = null; return@collectLatest }
            val vis = Rect(max(0f, r.left), max(0f, r.top), min(a.width.toFloat(), r.right), min(a.height.toFloat(), r.bottom))
            if (vis.width <= 0 || vis.height <= 0) return@collectLatest
            val k = iw / r.width
            val mx = vis.width * k * 0.2f; val my = vis.height * k * 0.2f
            val q = IntRect(
                ((vis.left - r.left) * k - mx).toInt().coerceIn(0, iw - 1), ((vis.top - r.top) * k - my).toInt().coerceIn(0, ih - 1),
                ((vis.right - r.left) * k + mx).toInt().coerceIn(1, iw), ((vis.bottom - r.top) * k + my).toInt().coerceIn(1, ih),
            )
            var passo = 1
            while (passo * 2 <= 1f / schermoPerPx) passo *= 2
            val vecchio = tassello
            val visOrig = IntRect(((vis.left - r.left) * k).toInt(), ((vis.top - r.top) * k).toInt(), ((vis.right - r.left) * k).toInt(), ((vis.bottom - r.top) * k).toInt())
            if (vecchio != null && vecchio.passo <= passo && vecchio.r.left <= visOrig.left && vecchio.r.top <= visOrig.top &&
                vecchio.r.right >= visOrig.right && vecchio.r.bottom >= visOrig.bottom) return@collectLatest
            val img = withContext(Dispatchers.IO) { runCatching { dettaglio(q, passo) }.getOrNull() } ?: return@collectLatest
            tassello = Tassello(q, img, passo)
        }
    }

    Box(modifier.onSizeChanged { area = it }
        // pizzica (2 dita) e trascina quando è ingrandita; con la lente un dito muove la lente
        .pointerInput(Unit) {
            awaitEachGesture {
                val giu = awaitFirstDown(requireUnconsumed = false)
                var mosso = 0f
                var trascina = false
                do {
                    val ev = awaitPointerEvent()
                    val premuti = ev.changes.count { it.pressed }
                    if (premuti >= 2) {
                        stato.applica(ev.calculateZoom(), ev.calculatePan(), ev.calculateCentroid(useCurrent = true))
                        ev.changes.forEach { it.consume() }
                    } else if (premuti == 1) {
                        val pan = ev.calculatePan()
                        if (lenteAgg && giu.type == PointerType.Touch) {
                            ev.changes.forEach { it.consume() }
                        } else if (stato.ingrandita) {
                            mosso += pan.getDistance()
                            if (mosso > viewConfiguration.touchSlop) trascina = true
                            if (trascina) { stato.applica(1f, pan); ev.changes.forEach { it.consume() } }
                        }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
        // rotellina del mouse = zoom; posizione della lente (mouse o dito)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: continue
                    when (ev.type) {
                        PointerEventType.Scroll -> {
                            val d = ch.scrollDelta.y
                            if (d != 0f) { stato.applica(exp(-d * 0.18f), Offset.Zero, ch.position); ev.changes.forEach { it.consume() } }
                        }
                        PointerEventType.Exit -> posLente = null
                        PointerEventType.Release -> if (ch.type == PointerType.Touch) posLente = null
                        else -> if (lenteAgg && (ch.type != PointerType.Touch || ch.pressed)) {
                            posLente = ch.position; lenteTouch = ch.type == PointerType.Touch
                        }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { toccoAgg() },
                onDoubleTap = { p ->
                    scope.launch {
                        val da = stato.scala
                        val a = if (stato.ingrandita) 1f else min(stato.massima, max(2.5f, if (stato.fit.width > 0) stato.larg / stato.fit.width.toFloat() else 2.5f))
                        var prec = da
                        animate(da, a) { v, _ -> stato.applica(v / prec, Offset.Zero, p); prec = v }
                        if (a == 1f) stato.azzera()
                    }
                },
            )
        }
    ) {
        if (base == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            if (fitW <= 0f) return@Canvas
            stato.area = area; stato.fit = IntSize(fitW.roundToInt(), fitH.roundToInt())
            val r = rettangolo()
            disegna(base, tassello, r, iw, filtro)
            val p = posLente
            if (lente && p != null) {
                val c = if (lenteTouch) Offset(p.x, max(raggio, p.y - raggio * 1.4f)) else p
                val rl = Rect(c + (r.topLeft - p) * ingrLente, r.size * ingrLente)
                val cerchio = Path().apply { addOval(Rect(c, raggio)) }
                clipPath(cerchio) {
                    drawRect(Color.Black, Offset(c.x - raggio, c.y - raggio), androidx.compose.ui.geometry.Size(raggio * 2, raggio * 2))
                    disegna(base, tassello, rl, iw, filtro)
                }
                drawCircle(Color.White, raggio, c, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), raggio + 2.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }
        }
        if (stato.ingrandita) Text(
            "×" + "%.1f".format(stato.scala).replace('.', ','),
            color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun DrawScope.disegna(base: ImageBitmap, t: Tassello?, r: Rect, iw: Int, filtro: ColorFilter?) {
    drawImage(
        base, IntOffset.Zero, IntSize(base.width, base.height),
        IntOffset(r.left.roundToInt(), r.top.roundToInt()), IntSize(r.width.roundToInt(), r.height.roundToInt()),
        colorFilter = filtro, filterQuality = FilterQuality.High,
    )
    if (t != null) {
        val k = r.width / iw
        val l = r.left + t.r.left * k; val tp = r.top + t.r.top * k
        drawImage(
            t.img, IntOffset.Zero, IntSize(t.img.width, t.img.height),
            IntOffset(l.roundToInt(), tp.roundToInt()), IntSize((t.r.width * k).roundToInt(), (t.r.height * k).roundToInt()),
            colorFilter = filtro, filterQuality = FilterQuality.High,
        )
    }
}


// ---------------------------------------------------------------- regolazioni

enum class Effetto(val nome: String) { NESSUNO("Originale"), BN("Bianco e nero"), SEPPIA("Seppia"), NEGATIVO("Negativo") }

/** Regolazioni di visualizzazione: non toccano il file originale (si può salvare una copia) */
data class Regolazioni(
    val luce: Float = 0f,          // -1..1
    val esposizione: Float = 0f,   // -1..1
    val contrasto: Float = 0f,     // -1..1
    val saturazione: Float = 0f,   // -1..1
    val tonalita: Float = 0f,      // -180..180 gradi
    val calore: Float = 0f,        // -1..1 (freddo..caldo)
    val effetto: Effetto = Effetto.NESSUNO,
    val auto: List<Float>? = null, // livelli automatici: scala e spostamento per R, G, B
) {
    val neutra get() = this == Regolazioni()

    fun filtro(): ColorFilter? = if (neutra) null else ColorFilter.colorMatrix(ColorMatrix(matrice()))

    /** Matrice colore 4x5 (stessa convenzione di Android: spostamenti in 0..255) */
    fun matrice(): FloatArray {
        var m = identita()
        auto?.let { a -> m = per(scalaSposta(a[0], a[2], a[4], a[1], a[3], a[5]), m) }
        if (esposizione != 0f) { val k = 2f.pow(esposizione * 1.5f); m = per(scalaSposta(k, k, k, 0f, 0f, 0f), m) }
        if (luce != 0f) { val o = luce * 80f; m = per(scalaSposta(1f, 1f, 1f, o, o, o), m) }
        if (contrasto != 0f) {
            val k = if (contrasto >= 0) 1f + contrasto * 1.5f else 1f + contrasto * 0.9f
            val o = 127.5f * (1f - k); m = per(scalaSposta(k, k, k, o, o, o), m)
        }
        val sat = if (effetto == Effetto.BN) 0f else 1f + saturazione
        if (sat != 1f) m = per(saturazioneM(sat), m)
        if (tonalita != 0f) m = per(tonalitaM(tonalita), m)
        if (calore != 0f) m = per(scalaSposta(1f, 1f, 1f, calore * 35f, calore * 6f, -calore * 35f), m)
        when (effetto) {
            Effetto.SEPPIA -> m = per(floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f), m)
            Effetto.NEGATIVO -> m = per(scalaSposta(-1f, -1f, -1f, 255f, 255f, 255f), m)
            else -> {}
        }
        return m
    }

    companion object {
        private const val LR = 0.2126f; private const val LG = 0.7152f; private const val LB = 0.0722f
        fun identita() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        fun scalaSposta(r: Float, g: Float, b: Float, or: Float, og: Float, ob: Float) =
            floatArrayOf(r, 0f, 0f, 0f, or, 0f, g, 0f, 0f, og, 0f, 0f, b, 0f, ob, 0f, 0f, 0f, 1f, 0f)
        fun saturazioneM(s: Float): FloatArray {
            val r = LR * (1 - s); val g = LG * (1 - s); val b = LB * (1 - s)
            return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }
        fun tonalitaM(gradi: Float): FloatArray {
            val a = gradi * (Math.PI / 180).toFloat(); val c = cos(a); val s = sin(a)
            val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
            return floatArrayOf(
                lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
                lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
                lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
        /** a ∘ b: prima b, poi a */
        fun per(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(20)
            for (i in 0..3) {
                for (j in 0..3) { var s = 0f; for (k in 0..3) s += a[i * 5 + k] * b[k * 5 + j]; r[i * 5 + j] = s }
                var s = a[i * 5 + 4]; for (k in 0..3) s += a[i * 5 + k] * b[k * 5 + 4]; r[i * 5 + 4] = s
            }
            return r
        }

        /** Livelli automatici per canale (toglie velature e dominanti di colore) calcolati sull'anteprima */
        fun livelliAuto(img: ImageBitmap): List<Float>? {
            val w = img.width; val h = img.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            img.readPixels(px)
            val ist = Array(3) { IntArray(256) }
            var n = 0
            var i = 0
            while (i < px.size) {
                val c = px[i]
                ist[0][(c shr 16) and 255]++; ist[1][(c shr 8) and 255]++; ist[2][c and 255]++
                n++; i += 3
            }
            val out = ArrayList<Float>(6)
            for (ch in 0..2) {
                var acc = 0; var lo = 0; var hi = 255
                for (v in 0..255) { acc += ist[ch][v]; if (acc > n * 0.004f) { lo = v; break } }
                acc = 0
                for (v in 255 downTo 0) { acc += ist[ch][v]; if (acc > n * 0.004f) { hi = v; break } }
                if (hi - lo < 16) { out += 1f; out += 0f; continue }
                val k = min(4f, 255f / (hi - lo))
                out += k; out += -lo * k
            }
            return out
        }

        /** Applica la matrice ai pixel ARGB (per salvare la copia modificata) */
        fun applica(px: IntArray, m: FloatArray, da: Int = 0, a: Int = px.size) {
            for (i in da until a) {
                val c = px[i]
                val r = ((c shr 16) and 255).toFloat(); val g = ((c shr 8) and 255).toFloat(); val b = (c and 255).toFloat()
                val nr = (m[0] * r + m[1] * g + m[2] * b + m[4]).roundToInt().coerceIn(0, 255)
                val ng = (m[5] * r + m[6] * g + m[7] * b + m[9]).roundToInt().coerceIn(0, 255)
                val nb = (m[10] * r + m[11] * g + m[12] * b + m[14]).roundToInt().coerceIn(0, 255)
                px[i] = (c and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PannelloRegolazioni(
    r: Regolazioni, cambia: (Regolazioni) -> Unit, auto: () -> Unit,
    salva: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Luce e colore", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ cambia(Regolazioni()) }, enabled = !r.neutra) { Text("Azzera") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(r.auto != null, auto, label = { Text("Auto") })
            for (e in Effetto.entries.drop(1))
                FilterChip(r.effetto == e, { cambia(r.copy(effetto = if (r.effetto == e) Effetto.NESSUNO else e)) }, label = { Text(e.nome) })
        }
        Cursore("Luminosità", r.luce, -1f..1f) { cambia(r.copy(luce = it)) }
        Cursore("Esposizione", r.esposizione, -1f..1f) { cambia(r.copy(esposizione = it)) }
        Cursore("Contrasto", r.contrasto, -1f..1f) { cambia(r.copy(contrasto = it)) }
        Cursore("Saturazione", r.saturazione, -1f..1f) { cambia(r.copy(saturazione = it)) }
        Cursore("Calore", r.calore, -1f..1f) { cambia(r.copy(calore = it)) }
        Cursore("Tonalità", r.tonalita, -180f..180f, gradi = true) { cambia(r.copy(tonalita = it)) }
        Text("Tocca il valore per azzerare la singola voce. L'originale non viene mai modificato.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        if (salva != null) OutlinedButton(salva, enabled = !r.neutra, modifier = Modifier.padding(top = 6.dp)) { Text("Salva una copia modificata") }
    }
}

@Composable
private fun Cursore(nome: String, v: Float, campo: ClosedFloatingPointRange<Float>, gradi: Boolean = false, cambia: (Float) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(nome, Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(v, { cambia(if (abs(it) < (campo.endInclusive - campo.start) * 0.02f) 0f else it) }, Modifier.weight(1f).height(36.dp), valueRange = campo)
        Text(
            if (gradi) "${v.roundToInt()}°" else (if (v > 0) "+" else "") + (v * 100).roundToInt(),
            Modifier.width(48.dp).pointerInput(Unit) { detectTapGestures { cambia(0f) } },
            style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
