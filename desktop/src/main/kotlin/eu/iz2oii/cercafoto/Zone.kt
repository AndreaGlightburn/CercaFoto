package eu.iz2oii.cercafoto

import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Impronte CLIP di 9 zone sovrapposte di una foto (griglia 3×3, ogni zona metà larghezza × metà altezza),
 * per trovare oggetti piccoli. Conservate a 8 bit per zona (512 byte + scala): ~4,6 KB per foto.
 */
class Zone(private val q: ByteArray, private val scala: FloatArray) {
    val n get() = scala.size

    /** prodotto scalare massimo tra [w] e le zone */
    fun massimo(w: FloatArray): Float {
        var migliore = -1e9f
        val d = w.size
        if (q.size < n * d) return migliore
        for (t in 0 until n) {
            var acc = 0f; val o = t * d
            for (k in 0 until d) acc += q[o + k] * w[k]
            acc *= scala[t]
            if (acc > migliore) migliore = acc
        }
        return migliore
    }

    fun vettore(t: Int, d: Int = 512): FloatArray = FloatArray(d) { q[t * d + it] * scala[t] }

    fun blob(): ByteArray {
        val bb = ByteBuffer.allocate(1 + n * 4 + q.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(n.toByte()); for (s in scala) bb.putFloat(s); bb.put(q)
        return bb.array()
    }

    companion object {
        /** riquadri normalizzati (l, t, r, b) delle 9 zone */
        val RIQUADRI: List<FloatArray> = buildList {
            for (y in 0..2) for (x in 0..2) add(floatArrayOf(x * 0.25f, y * 0.25f, x * 0.25f + 0.5f, y * 0.25f + 0.5f))
        }

        fun da(vettori: List<FloatArray>): Zone {
            val d = vettori.first().size
            val q = ByteArray(vettori.size * d); val s = FloatArray(vettori.size)
            for ((t, v) in vettori.withIndex()) {
                var m = 0f; for (x in v) m = max(m, abs(x))
                val k = if (m > 0f) m / 127f else 1f
                s[t] = k
                for (i in 0 until d) q[t * d + i] = (v[i] / k).roundToInt().coerceIn(-127, 127).toByte()
            }
            return Zone(q, s)
        }

        fun daBlob(b: ByteArray): Zone? = runCatching {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.get().toInt()
            val s = FloatArray(n) { bb.getFloat() }
            val q = ByteArray(bb.remaining()); bb.get(q)
            Zone(q, s)
        }.getOrNull()

        /** Ritaglia una parte normalizzata dell'immagine */
        fun ritaglio(img: BufferedImage, l: Float, t: Float, r: Float, b: Float): BufferedImage {
            val x0 = (l * img.width).toInt().coerceIn(0, img.width - 1); val y0 = (t * img.height).toInt().coerceIn(0, img.height - 1)
            val x1 = (r * img.width).toInt().coerceIn(x0 + 1, img.width); val y1 = (b * img.height).toInt().coerceIn(y0 + 1, img.height)
            return img.getSubimage(x0, y0, x1 - x0, y1 - y0)
        }

        /** Calcola le 9 zone di un'immagine */
        fun calcola(img: BufferedImage): Zone = da(RIQUADRI.map { z -> Clip.immagine(ritaglio(img, z[0], z[1], z[2], z[3])) })
    }
}
