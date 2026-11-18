package eu.iz2oii.cercafoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Foto da indicizzare. uri/cartella valorizzati solo per le cartelle esterne (SAF). */
class MediaFoto(
    val id: Long, val data: Long, val modificata: Long, val nome: String, val album: String,
    val uri: Uri? = null, val cartella: String? = null,
) {
    val uriEffettivo: Uri get() = uri ?: uriFoto(id)
}

object Impostazioni {
    private fun p(ctx: Context) = ctx.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)
    fun ocr(ctx: Context) = p(ctx).getBoolean("ocr", true)
    fun setOcr(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ocr", v).apply()
    fun soglia(ctx: Context) = p(ctx).getFloat("soglia", 0.5f)
    fun setSoglia(ctx: Context, v: Float) = p(ctx).edit().putFloat("soglia", v).apply()
    fun cartelle(ctx: Context): Set<String> = p(ctx).getStringSet("cartelle", emptySet())!!.toSet()
    fun setCartelle(ctx: Context, v: Set<String>) = p(ctx).edit().putStringSet("cartelle", v).apply()
}

/** Id stabile e negativo per i file delle cartelle esterne (non collide con gli id della galleria) */
fun idEsterno(uri: String): Long {
    var h = -0x340d631b7bdddcdbL // FNV-1a 64
    for (ch in uri) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
    return -((h and Long.MAX_VALUE) or 1L)
}

/** Nome leggibile di una cartella scelta col selettore (es. "Unità 1A2B-3C4D · DCIM/Vacanze") */
fun nomeCartella(tree: String): String {
    val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrDefault(tree)
    val parti = id.split(':', limit = 2)
    val vol = parti[0]; val path = parti.getOrElse(1) { "" }
    val v = when (vol) { "primary" -> "Memoria interna"; "home" -> "Documenti"; else -> "Unità $vol" }
    return if (path.isBlank()) v else "$v · $path"
}

class Indicizzatore(private val ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    companion object {
        const val NOME = "indicizza"
        private const val CANALE = "indice"
        private const val NOTIFICA = 42

        fun avvia(ctx: Context, sostituisci: Boolean = false, rianalisiVolti: Boolean = false) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NOME,
                if (sostituisci || rianalisiVolti) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Indicizzatore>().setInputData(workDataOf("rianalisi_volti" to rianalisiVolti)).build()
            )
        }

        fun ferma(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NOME)

        fun leggiGalleria(ctx: Context): List<MediaFoto> {
            val out = ArrayList<MediaFoto>()
            val proj = arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            // VOLUME_EXTERNAL comprende memoria interna e schede SD
            ctx.contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), proj, null, null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val mod = c.getLong(2)
                    val taken = if (c.isNull(1)) 0L else c.getLong(1)
                    out += MediaFoto(
                        c.getLong(0), if (taken > 0) taken else mod * 1000, mod,
                        c.getString(3) ?: "", c.getString(4) ?: ""
                    )
                }
            }
            return out
        }

        /** Visita ricorsiva di una cartella scelta col selettore. Ritorna null se non è accessibile (unità scollegata). */
        fun leggiCartella(ctx: Context, tree: String): List<MediaFoto>? = runCatching {
            val treeUri = Uri.parse(tree)
            val out = ArrayList<MediaFoto>()
            val coda = ArrayDeque<Pair<String, String>>()
            val radice = DocumentsContract.getTreeDocumentId(treeUri)
            coda.addLast(radice to radice.substringAfterLast('/').substringAfterLast(':'))
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            var visitate = 0
            while (coda.isNotEmpty() && visitate < 20000) {
                val (docId, album) = coda.removeFirst()
                visitate++
                val figli = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val c = ctx.contentResolver.query(figli, proj, null, null, null) ?: error("non accessibile")
                c.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0); val nome = it.getString(1) ?: ""; val mime = it.getString(2) ?: ""
                        if (nome.startsWith(".")) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) coda.addLast(id to nome)
                        else if (mime.startsWith("image/")) {
                            val u = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val mod = if (it.isNull(3)) 0L else it.getLong(3)
                            out += MediaFoto(idEsterno(u.toString()), mod, mod / 1000, nome, album, u, tree)
                        }
                    }
                }
            }
            out
        }.getOrNull()

        private val formatoExif = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

        fun dataExif(ctx: Context, uri: Uri): Long? = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val e = ExifInterface(s)
                (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))
                    ?.let { synchronized(formatoExif) { formatoExif.parse(it)?.time } }
            }
        }.getOrNull()
    }

    private fun notifica(testo: String, fatte: Int, tot: Int) =
        NotificationCompat.Builder(ctx, CANALE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cerca Foto: indicizzazione")
            .setContentText(testo)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(tot, fatte, tot == 0)
            .build()

    private fun foreground(testo: String, fatte: Int, tot: Int): ForegroundInfo {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANALE) == null)
            nm.createNotificationChannel(NotificationChannel(CANALE, "Indicizzazione foto", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(NOTIFICA, notifica(testo, fatte, tot), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparazione…", 0, 0)

    private fun carica(uri: Uri, lato: Int = 1280): Bitmap {
        var bmp = ctx.contentResolver.loadThumbnail(uri, Size(lato, lato), null)
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val c = bmp.copy(Bitmap.Config.ARGB_8888, false); bmp.recycle(); bmp = c
        }
        return bmp
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        runCatching { setForeground(foreground("Lettura galleria…", 0, 0)) }
        val db = Db.get(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Rianalisi approfondita dei soli volti senza nome: alta risoluzione e volti più piccoli
        if (inputData.getBoolean("rianalisi_volti", false)) {
            val lista = db.fotoConVoltiSenzaNome()
            Analizzatore(ctx, false, minVolto = 0.025f).use { an ->
                for ((i, f) in lista.withIndex()) {
                    if (isStopped) break
                    runCatching {
                        val bmp = carica(f.second, 2560)
                        val (volti, sorriso) = an.volti(bmp)
                        bmp.recycle()
                        db.sostituisciVolti(f.first, volti, sorriso)
                    }
                    if (i % 5 == 0 || i == lista.size - 1) {
                        setProgress(workDataOf("fase" to "volti_hd", "fatte" to i + 1, "totale" to lista.size))
                        runCatching { nm.notify(NOTIFICA, notifica("Volti in alta risoluzione: ${i + 1} di ${lista.size}", i + 1, lista.size)) }
                    }
                }
            }
            if (isStopped) return@withContext Result.success()
            setProgress(workDataOf("fase" to "volti", "fatte" to 0, "totale" to 0))
            runCatching { nm.notify(NOTIFICA, notifica("Raggruppo i volti…", 0, 0)) }
            Raggruppa.esegui(ctx)
            return@withContext Result.success()
        }

        // Elenco foto: galleria + cartelle esterne raggiungibili
        val galleria = ArrayList(leggiGalleria(ctx))
        val cartelle = Impostazioni.cartelle(ctx)
        val raggiungibili = HashSet<String>()
        for (c in cartelle) {
            leggiCartella(ctx, c)?.let { galleria += it; raggiungibili += c }
        }
        val gia = db.modifiche()
        val esterne = db.fotoEsterne()
        val presenti = galleria.mapTo(HashSet()) { it.id }
        // Rimuove solo ciò che è davvero sparito: le foto di unità scollegate restano nell'indice
        val rimosse = gia.keys.filter { id ->
            val c = esterne[id]
            id !in presenti && (c == null || c in raggiungibili || c !in cartelle)
        }
        db.rimuovi(rimosse)
        var cambiato = rimosse.isNotEmpty()

        // 1) Completa le foto già analizzate con la versione precedente (solo colori/età/sesso/capelli/abiti)
        val daCompletare = db.daCompletare()
        if (daCompletare.isNotEmpty()) {
            cambiato = true
            Analizzatore(ctx, false).use { an ->
                for ((i, f) in daCompletare.withIndex()) {
                    if (isStopped) break
                    runCatching {
                        val bmp = carica(f.second)
                        val attr = db.voltiDi(f.first).mapNotNull { v -> an.attributi(bmp, v.l, v.t, v.r, v.b)?.let { v.id to it } }
                        db.completa(f.first, an.colori(bmp), attr)
                        bmp.recycle()
                    }.onFailure { db.completa(f.first, null, emptyList()) }
                    if (i % 10 == 0 || i == daCompletare.size - 1) {
                        setProgress(workDataOf("fase" to "completamento", "fatte" to i + 1, "totale" to daCompletare.size))
                        runCatching { nm.notify(NOTIFICA, notifica("Aggiornamento ${i + 1} di ${daCompletare.size}", i + 1, daCompletare.size)) }
                    }
                }
            }
        }

        // 2) Analisi completa delle foto nuove o modificate
        val daFare = galleria.filter { gia[it.id] != it.modificata }.sortedByDescending { it.data }
        val tot = daFare.size
        if (tot > 0 && !isStopped) {
            cambiato = true
            Analizzatore(ctx, Impostazioni.ocr(ctx)).use { an ->
                for ((i, m) in daFare.withIndex()) {
                    if (isStopped) break
                    val r = runCatching {
                        val bmp = carica(m.uriEffettivo)
                        an.analizza(bmp).also { bmp.recycle() }
                    }.getOrNull()
                    if (r != null && m.uri != null) r.dataExif = dataExif(ctx, m.uri)
                    db.salva(m, r)
                    if (i % 5 == 0 || i == tot - 1) {
                        setProgress(workDataOf("fase" to "analisi", "fatte" to i + 1, "totale" to tot))
                        runCatching { nm.notify(NOTIFICA, notifica("Foto ${i + 1} di $tot", i + 1, tot)) }
                    }
                }
            }
        }
        if (isStopped || !cambiato) return@withContext Result.success()
        setProgress(workDataOf("fase" to "volti", "fatte" to 0, "totale" to 0))
        runCatching { nm.notify(NOTIFICA, notifica("Raggruppo i volti…", 0, 0)) }
        Raggruppa.esegui(ctx)
        Result.success()
    }
}
