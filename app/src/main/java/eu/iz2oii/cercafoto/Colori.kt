package eu.iz2oii.cercafoto

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** Colori dominanti della foto, dei capelli e degli abiti, calcolati sui pixel (nessun modello). */
object Colori {

    val NOMI = listOf("rosso", "arancione", "giallo", "verde", "azzurro", "blu", "viola", "rosa", "marrone", "nero", "bianco", "grigio")

    private val hsv = FloatArray(3)

    fun colore(p: Int): String {
        Color.colorToHSV(p, hsv)
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
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
        Color.colorToHSV(p, hsv)
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        if (v < 0.22f) return "nero"
        if (s < 0.18f) return if (v > 0.45f) "grigio" else "nero"
        return when {
            h >= 345f || h < 20f -> if (s > 0.45f && v > 0.3f) "rosso" else "castano"
            h < 50f -> if (v < 0.45f) "castano" else if (s < 0.65f && v > 0.55f) "biondo" else "castano"
            else -> null // probabilmente sfondo
        }
    }

    /** Conta le classi in un rettangolo (coordinate in pixel), campionando al massimo ~2500 punti */
    private fun conta(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, f: (Int) -> String?): Pair<Map<String, Int>, Int> {
        val m = HashMap<String, Int>()
        val w = x1 - x0; val h = y1 - y0
        if (w <= 0 || h <= 0) return m to 0
        val passo = max(1, kotlin.math.sqrt((w * h) / 2500.0).toInt())
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                n++
                f(bmp.getPixel(x, y))?.let { m[it] = (m[it] ?: 0) + 1 }
                x += passo
            }
            y += passo
        }
        return m to n
    }

    /** nome colore -> percentuale (solo >= 5%) */
    fun foto(bmp: Bitmap): Map<String, Int> {
        val (m, n) = conta(bmp, 0, 0, bmp.width, bmp.height, ::colore)
        if (n == 0) return emptyMap()
        return m.mapValues { it.value * 100 / n }.filter { it.value >= 5 }
    }

    fun capelli(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): String? {
        val W = bmp.width; val H = bmp.height
        val fw = (r - l) * W; val fh = (b - t) * H
        val x0 = ((l * W) + fw * 0.15f).toInt(); val x1 = ((r * W) - fw * 0.15f).toInt()
        val y1 = (t * H + fh * 0.02f).toInt(); val y0 = max(0, (t * H - fh * 0.3f).toInt())
        if (y1 - y0 < fh * 0.12f) return null
        val (m, n) = conta(bmp, max(0, x0), y0, min(W, x1), min(H, y1), ::capello)
        val top = m.maxByOrNull { it.value } ?: return null
        return if (n > 0 && top.value * 100 / n >= 35) top.key else null
    }

    fun abiti(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): String? {
        val W = bmp.width; val H = bmp.height
        val fw = (r - l) * W; val fh = (b - t) * H
        val x0 = max(0, (l * W - fw * 0.3f).toInt()); val x1 = min(W, (r * W + fw * 0.3f).toInt())
        val y0 = (b * H + fh * 0.3f).toInt(); val y1 = min(H, (b * H + fh * 1.6f).toInt())
        if (y1 - y0 < fh * 0.5f) return null
        val (m, n) = conta(bmp, x0, y0, x1, y1, ::colore)
        val top = m.maxByOrNull { it.value } ?: return null
        return if (n > 0 && top.value * 100 / n >= 30) top.key else null
    }
}
