package eu.iz2oii.cercafoto

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Dove copiare/spostare: una cartella della galleria (percorso relativo) o una cartella scelta col selettore. */
sealed class Destinazione {
    data class Galleria(val percorso: String) : Destinazione() {   // es. "Pictures/Vacanze/"
        val nome get() = percorso.trimEnd('/').substringAfterLast('/')
    }
    data class Albero(val tree: String) : Destinazione()
    fun descrizione() = when (this) { is Galleria -> percorso.trimEnd('/'); is Albero -> nomeCartella(tree) }
}

data class CartellaGalleria(val percorso: String, val volume: String, val n: Int)

object Operazioni {

    // ------------------------------------------------------------ informazioni

    /** Cartelle della galleria con numero di foto, le più usate prima */
    fun cartelleGalleria(ctx: Context): List<CartellaGalleria> {
        val m = HashMap<Pair<String, String>, Int>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.VOLUME_NAME), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val k = (c.getString(0) ?: "") to (c.getString(1) ?: "")
                m[k] = (m[k] ?: 0) + 1
            }
        }
        return m.map { CartellaGalleria(it.key.first, it.key.second, it.value) }.sortedByDescending { it.n }
    }

    /** Dettagli leggibili di una foto: etichetta -> valore */
    fun dettagli(ctx: Context, f: FotoIdx): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val cr = ctx.contentResolver
        if (f.cartella == null) {
            cr.query(
                f.uri, arrayOf(
                    MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
                ), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val vol = c.getString(1) ?: ""
                    val dove = if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Memoria interna" else "Scheda SD ($vol)"
                    out += "Cartella" to "$dove › ${(c.getString(0) ?: "").trimEnd('/')}"
                    out += "File" to (c.getString(2) ?: f.nome)
                    out += "Dimensione" to formatoMB(c.getLong(3))
                    if (c.getInt(4) > 0) out += "Risoluzione" to "${c.getInt(4)} × ${c.getInt(5)} (${"%.1f".format(Locale.ITALIAN, c.getInt(4) * c.getInt(5) / 1e6)} MP)"
                }
            }
        } else {
            val doc = runCatching { DocumentsContract.getDocumentId(f.uri) }.getOrDefault("")
            out += "Cartella" to (nomeCartella(f.cartella).substringBefore(" · ") + " › " + doc.substringAfter(':').substringBeforeLast('/', ""))
            out += "File" to f.nome
            cr.query(f.uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) out += "Dimensione" to formatoMB(c.getLong(0))
            }
        }
        // EXIF: fotocamera e posizione (setRequireOriginal serve per leggere il GPS, con il permesso posizione foto)
        runCatching {
            val u = if (f.cartella == null) runCatching { MediaStore.setRequireOriginal(f.uri) }.getOrDefault(f.uri) else f.uri
            cr.openInputStream(u)?.use { s ->
                val e = ExifInterface(s)
                val cam = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                    .joinToString(" ").trim()
                if (cam.isNotEmpty()) out += "Fotocamera" to cam
                e.latLong?.let { (lat, lon) -> out += "Posizione" to "%.5f, %.5f".format(Locale.US, lat, lon) }
            }
        }
        return out
    }

    fun formatoMB(b: Long) = if (b >= 1_000_000) "%.1f MB".format(Locale.ITALIAN, b / 1e6) else "${b / 1000} KB"

    // ------------------------------------------------------------ consensi di sistema (Android 11+)

    private fun galleria(lista: List<FotoIdx>) = lista.filter { it.cartella == null }.map { it.uri }

    fun richiestaCestino(ctx: Context, lista: List<FotoIdx>): PendingIntent? =
        galleria(lista).takeIf { it.isNotEmpty() }?.let { MediaStore.createTrashRequest(ctx.contentResolver, it, true) }

    fun richiestaEliminazione(ctx: Context, lista: List<FotoIdx>): PendingIntent? =
        galleria(lista).takeIf { it.isNotEmpty() }?.let { MediaStore.createDeleteRequest(ctx.contentResolver, it) }

    fun richiestaScrittura(ctx: Context, lista: List<FotoIdx>): PendingIntent? =
        galleria(lista).takeIf { it.isNotEmpty() }?.let { MediaStore.createWriteRequest(ctx.contentResolver, it) }

    // ------------------------------------------------------------ operazioni

    private fun mime(ctx: Context, u: Uri) = ctx.contentResolver.getType(u) ?: "image/jpeg"

    /** Copia una foto; ritorna l'uri nuovo o null */
    fun copia(ctx: Context, db: Db, f: FotoIdx, dest: Destinazione): Uri? {
        val cr = ctx.contentResolver
        val tipo = mime(ctx, f.uri)
        return when (dest) {
            is Destinazione.Galleria -> {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, f.nome)
                    put(MediaStore.Images.Media.MIME_TYPE, tipo)
                    put(MediaStore.Images.Media.RELATIVE_PATH, dest.percorso)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.DATE_TAKEN, f.data)
                }
                val nuovo = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv) ?: return null
                try {
                    cr.openInputStream(f.uri)!!.use { i -> cr.openOutputStream(nuovo)!!.use { o -> i.copyTo(o) } }
                    cr.update(nuovo, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    cr.delete(nuovo, null, null); throw e
                }
                // registra subito la copia nell'indice, così non va rianalizzata
                val id = ContentUris.parseId(nuovo)
                cr.query(nuovo, arrayOf(MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) db.duplica(f.id, id, c.getLong(0), dest.nome, c.getString(1) ?: f.nome)
                }
                nuovo
            }
            is Destinazione.Albero -> {
                val tree = Uri.parse(dest.tree)
                val radice = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val nuovo = DocumentsContract.createDocument(cr, radice, tipo, f.nome) ?: return null
                cr.openInputStream(f.uri)!!.use { i -> cr.openOutputStream(nuovo)!!.use { o -> i.copyTo(o) } }
                nuovo
            }
        }
    }

    /** Sposta nella galleria cambiando solo il percorso (serve il consenso di scrittura). L'id resta lo stesso. */
    fun spostaInGalleria(ctx: Context, db: Db, f: FotoIdx, dest: Destinazione.Galleria): Boolean {
        val ok = ctx.contentResolver.update(f.uri, ContentValues().apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, dest.percorso)
        }, null, null) > 0
        if (ok) db.aggiornaAlbum(f.id, dest.nome)
        return ok
    }

    /** Elimina un file di una cartella esterna (serve il permesso di scrittura sulla cartella) */
    fun eliminaEsterna(ctx: Context, f: FotoIdx): Boolean =
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, f.uri) }.getOrDefault(false)
}
