package eu.iz2oii.cercafoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Accesso alla foto a piena risoluzione per zone (BitmapRegionDecoder), rispettando l'orientamento */
private class Originale(ctx: Context, uri: Uri) : AutoCloseable {
    private val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")!!
    private val dec = BitmapRegionDecoder.newInstance(pfd)
    val orient: Int = runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrDefault(1)
    val larg = if (orient >= 5) dec.height else dec.width
    val alt = if (orient >= 5) dec.width else dec.height

    fun zona(r: IntRect, passo: Int): ImageBitmap? {
        val g = rettangoloGrezzo(r, dec.width, dec.height, orient) ?: return null
        val bm = synchronized(this) {
            if (dec.isRecycled) return null
            dec.decodeRegion(android.graphics.Rect(g.left, g.top, g.right, g.bottom), BitmapFactory.Options().apply { inSampleSize = passo })
        } ?: return null
        val gradi = gradiOrientamento(orient)
        val fin = if (gradi == 0) bm else Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(gradi.toFloat()) }, true)
        return fin.asImageBitmap()
    }

    override fun close() { synchronized(this) { dec.recycle() }; runCatching { pfd.close() } }
}

class FotoCaricata(val base: ImageBitmap, val larg: Int, val alt: Int)

/** Foto del visore: anteprima nitida + zoom con dettaglio a piena risoluzione */
@Composable
fun FotoZoom(
    uri: Uri, stato: ZoomStato, filtro: ColorFilter?, lente: Boolean,
    caricata: (FotoCaricata) -> Unit = {}, tocco: () -> Unit,
) {
    val ctx = LocalContext.current
    var foto by remember(uri) { mutableStateOf<FotoCaricata?>(null) }
    var orig by remember(uri) { mutableStateOf<Originale?>(null) }
    LaunchedEffect(uri) {
        val base = withContext(Dispatchers.IO) {
            val req = ImageRequest.Builder(ctx).data(uri).size(2048).allowHardware(false).build()
            ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } ?: return@LaunchedEffect
        val o = withContext(Dispatchers.IO) { runCatching { Originale(ctx, uri) }.getOrNull() }
        orig = o
        val f = FotoCaricata(base.asImageBitmap(), o?.larg ?: base.width, o?.alt ?: base.height)
        foto = f
        caricata(f)
    }
    DisposableEffect(uri) { onDispose { orig?.close() } }
    val o = orig
    Box(Modifier.fillMaxSize()) {
        val f = foto
        if (f == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        VistaZoom(
            f?.base, f?.larg ?: 0, f?.alt ?: 0, stato, Modifier.fillMaxSize(), filtro, lente, tocco,
            dettaglio = if (o != null) { r, p -> o.zona(r, p) } else null,
        )
    }
}

/** Salva in Pictures/CercaFoto una copia della foto con le regolazioni applicate; restituisce il nome */
suspend fun salvaCopiaModificata(ctx: Context, f: FotoIdx, reg: Regolazioni): String = withContext(Dispatchers.IO) {
    val req = ImageRequest.Builder(ctx).data(f.uri).size(4096).allowHardware(false).build()
    val src = ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        ?: error("Impossibile leggere la foto")
    val w = src.width; val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val m = reg.matrice()
    val righe = 64
    val buf = IntArray(w * righe)
    var y = 0
    while (y < h) {
        val n = minOf(righe, h - y)
        src.getPixels(buf, 0, w, 0, y, w, n)
        Regolazioni.applica(buf, m, 0, w * n)
        out.setPixels(buf, 0, w, 0, y, w, n)
        y += n
    }
    val nome = f.nome.substringBeforeLast('.') + "_modificata_" + System.currentTimeMillis() % 100000 + ".jpg"
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, nome)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CercaFoto/")
        put(MediaStore.Images.Media.DATE_TAKEN, f.data)
    }
    val dest = ctx.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv)
        ?: error("Impossibile creare il file")
    ctx.contentResolver.openOutputStream(dest)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    out.recycle()
    "Pictures/CercaFoto/$nome"
}
