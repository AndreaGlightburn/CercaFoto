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
    val uri: Uri? = null, val cartella: String? = null, val volume: String? = null,
) {
    val uriEffettivo: Uri get() = uri ?: uriFoto(id)
    val uriEffettivoVideo: Uri get() = uri ?: android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
}

/** Carica un'immagine ridotta: miniatura di sistema per galleria/cartelle, decodifica diretta per i file dell'app (fotogrammi) */
fun caricaBitmap(ctx: Context, uri: Uri, lato: Int): Bitmap {
    var bmp = if (uri.scheme == "file") {
        val path = uri.path!!
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= lato) s *= 2
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) ?: error("illeggibile")
    } else ctx.contentResolver.loadThumbnail(uri, Size(lato, lato), null)
    if (bmp.config != Bitmap.Config.ARGB_8888) {
        val c = bmp.copy(Bitmap.Config.ARGB_8888, false); bmp.recycle(); bmp = c
    }
    return bmp
}

object Impostazioni {
    private fun p(ctx: Context) = ctx.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)
    fun ocr(ctx: Context) = p(ctx).getBoolean("ocr", true)
    fun setOcr(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ocr", v).apply()
    fun soglia(ctx: Context) = p(ctx).getFloat("soglia", 0.5f)
    fun setSoglia(ctx: Context, v: Float) = p(ctx).edit().putFloat("soglia", v).apply()
    fun cartelle(ctx: Context): Set<String> = p(ctx).getStringSet("cartelle", emptySet())!!.toSet()
    fun setCartelle(ctx: Context, v: Set<String>) = p(ctx).edit().putStringSet("cartelle", v).apply()
    /** analizza anche i video */
    fun video(ctx: Context) = p(ctx).getBoolean("video", true)
    fun setVideo(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("video", v).apply()
    /** analizza i video anche senza caricatore (di norma solo in carica) */
    fun videoSempre(ctx: Context) = p(ctx).getBoolean("video_sempre", false)
    fun setVideoSempre(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("video_sempre", v).apply()
    /** video in attesa del caricatore (per mostrarlo nell'app) */
    fun videoInAttesa(ctx: Context) = p(ctx).getInt("video_attesa", 0)
    fun setVideoInAttesa(ctx: Context, n: Int) = p(ctx).edit().putInt("video_attesa", n).apply()
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
    val v = when (vol) { "primary" -> "Memoria interna"; "home" -> "Documenti"; else -> Unita.descrizioni[vol] ?: "Unità $vol" }
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

        fun ferma(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NOME); WorkManager.getInstance(ctx).cancelUniqueWork(NOME_VIDEO) }

        const val NOME_VIDEO = "indicizza_video"
        /** un solo lavoro alla volta sui video (lavoro normale e lavoro "in carica") */
        val lucchettoVideo = kotlinx.coroutines.sync.Mutex()

        /** Analisi dei video rimandata: parte da sola quando il telefono è in carica */
        fun avviaVideoInCarica(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NOME_VIDEO, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Indicizzatore>().setInputData(workDataOf("solo_video" to true))
                    .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build()).build()
            )
        }

        fun inCarica(ctx: Context) = runCatching { ctx.getSystemService(android.os.BatteryManager::class.java).isCharging }.getOrDefault(false)

        fun leggiVideo(ctx: Context): List<MediaFoto> = runCatching {
            val out = ArrayList<MediaFoto>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.VOLUME_NAME
            )
            ctx.contentResolver.query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), proj, null, null,
                "${MediaStore.Video.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val mod = c.getLong(2); val taken = if (c.isNull(1)) 0L else c.getLong(1)
                    out += MediaFoto(c.getLong(0), if (taken > 0) taken else mod * 1000, mod, c.getString(3) ?: "", c.getString(4) ?: "", volume = c.getString(5))
                }
            }
            out
        }.getOrDefault(emptyList())

        fun leggiGalleria(ctx: Context): List<MediaFoto> {
            val out = ArrayList<MediaFoto>()
            val proj = arrayOf(
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.VOLUME_NAME
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
                        c.getString(3) ?: "", c.getString(4) ?: "", volume = c.getString(5)
                    )
                }
            }
            return out
        }

        /** Visita ricorsiva di una cartella scelta col selettore. Ritorna null se non è accessibile (unità scollegata). */
        fun leggiCartella(ctx: Context, tree: String, video: MutableList<MediaFoto>? = null): List<MediaFoto>? = runCatching {
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
                        } else if (video != null && mime.startsWith("video/")) {
                            val u = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            val mod = if (it.isNull(3)) 0L else it.getLong(3)
                            video += MediaFoto(idEsterno(u.toString()), mod, mod / 1000, nome, album, u, tree)
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

    private fun carica(uri: Uri, lato: Int = 1280): Bitmap = caricaBitmap(ctx, uri, lato)

    // ------------------------------------------------------------ video

    private suspend fun progresso(fase: String, fatte: Int, totale: Int, testo: String, nome: String = "") {
        setProgress(workDataOf("fase" to fase, "fatte" to fatte, "totale" to totale, "nome" to nome))
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICA, notifica(testo, fatte, totale)) }
    }

    /**
     * Video nuovi o modificati: estrae un fotogramma ogni pochi secondi e lo analizza come una foto.
     * Di norma solo in carica: altrimenti rimanda tutto a un lavoro che parte da solo col caricatore.
     * Ritorna true se l'indice è cambiato.
     */
    private suspend fun fasevideo(db: Db, video: List<MediaFoto>, raggiungibili: Set<String>, soloInCarica: Boolean): Boolean {
        if (!Impostazioni.video(ctx)) return false
        val cartelle = Impostazioni.cartelle(ctx)
        val noti = db.statiVideo()
        val presenti = video.mapTo(HashSet()) { it.id }
        val montati = runCatching { MediaStore.getExternalVolumeNames(ctx) }.getOrDefault(emptySet())
        val tolti = noti.values.filter { v ->
            v.id !in presenti && if (v.cartella == null) (v.volume == null || v.volume in montati) else (v.cartella in raggiungibili || v.cartella !in cartelle)
        }.map { it.id }
        db.rimuoviVideo(tolti)
        val daFare = video.filter { noti[it.id]?.modificata != it.modificata }.sortedByDescending { it.data }
        Impostazioni.setVideoInAttesa(ctx, daFare.size)
        if (daFare.isEmpty()) return tolti.isNotEmpty()
        if (soloInCarica && !inCarica(ctx) && !Impostazioni.videoSempre(ctx)) { avviaVideoInCarica(ctx); return tolti.isNotEmpty() }
        if (!lucchettoVideo.tryLock()) return tolti.isNotEmpty()
        try {
            var fatti = 0
            Analizzatore(ctx, Impostazioni.ocr(ctx)).use { an ->
                for ((i, m) in daFare.withIndex()) {
                    if (isStopped) break
                    if (soloInCarica && !inCarica(ctx) && !Impostazioni.videoSempre(ctx)) { avviaVideoInCarica(ctx); break }
                    progresso("video", i, daFare.size, "Video ${i + 1} di ${daFare.size}: ${m.nome}", m.nome)
                    runCatching { analizzaVideo(db, an, m) }
                    fatti++
                    Impostazioni.setVideoInAttesa(ctx, daFare.size - fatti)
                }
            }
            return true
        } finally { lucchettoVideo.unlock() }
    }

    private val formatoDataVideo = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    private fun analizzaVideo(db: Db, an: Analizzatore, m: MediaFoto) {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, m.uriEffettivoVideo)
            val durata = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rot = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dataReg = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let { runCatching { formatoDataVideo.parse(it.take(15))?.time }.getOrNull() }?.takeIf { it > 315532800000L }
            val inizio = dataReg ?: m.data
            db.salvaVideo(m, durata)
            val orizzontale = if (rot % 180 == 90) vh > vw else vw > vh
            val passo = maxOf(3000L, durata / 300)
            var t = 0L
            var firmaPrec: IntArray? = null; var voltiPrec = -1; var tenuti = 0
            while (t <= durata && !isStopped) {
                val ms = t; t += passo
                var bmp = (if (android.os.Build.VERSION.SDK_INT >= 27)
                    mmr.getScaledFrameAtTime(ms * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280)
                else mmr.getFrameAtTime(ms * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                // alcuni dispositivi restituiscono il fotogramma non ruotato
                if (rot % 180 == 90 && vw != vh && (bmp.width > bmp.height) != orizzontale)
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true)
                if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                val firma = firma(bmp)
                val r = runCatching { an.analizza(bmp) }.getOrNull()
                if (r == null) { bmp.recycle(); continue }
                // scena praticamente uguale alla precedente con gli stessi volti: non serve tenerla
                if (firmaPrec != null && distanza(firma, firmaPrec) < 7 && r.volti.size == voltiPrec) { bmp.recycle(); continue }
                val id = idEsterno("v:${m.uriEffettivoVideo}#$ms")
                val file = db.fileFotogramma(id)
                runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                bmp.recycle()
                db.salva(MediaFoto(id, inizio + ms, m.modificata, m.nome, m.album, Uri.fromFile(file)), r)
                db.segnaFotogramma(id, m.id, ms)
                firmaPrec = firma; voltiPrec = r.volti.size; tenuti++
            }
            db.chiudiVideo(m.id, tenuti)
        } finally { runCatching { mmr.release() } }
    }

    /** impronta 16x16 in grigi per riconoscere scene uguali */
    private fun firma(b: Bitmap): IntArray {
        val p = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256); p.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (p !== b) p.recycle()
        return IntArray(256) { val c = px[it]; (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10 }
    }

    private fun distanza(a: IntArray, b: IntArray): Int { var s = 0; for (i in a.indices) s += kotlin.math.abs(a[i] - b[i]); return s / a.size }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        runCatching { setForeground(foreground("Lettura galleria…", 0, 0)) }
        val db = Db.get(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        // Lavoro "in carica": solo i video rimandati, poi raggruppa i volti
        if (inputData.getBoolean("solo_video", false)) {
            val video = ArrayList(leggiVideo(ctx))
            val ragg = HashSet<String>()
            for (c in Impostazioni.cartelle(ctx)) if (leggiCartella(ctx, c, video) != null) ragg += c
            if (fasevideo(db, video, ragg, soloInCarica = false) && !isStopped) {
                progresso("volti", 0, 0, "Raggruppo i volti…")
                Raggruppa.esegui(ctx)
            }
            return@withContext Result.success()
        }

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
        val video = ArrayList(if (Impostazioni.video(ctx)) leggiVideo(ctx) else emptyList())
        val cartelle = Impostazioni.cartelle(ctx)
        val raggiungibili = HashSet<String>()
        for (c in cartelle) {
            leggiCartella(ctx, c, if (Impostazioni.video(ctx)) video else null)?.let { galleria += it; raggiungibili += c }
        }
        val gia = db.modifiche()
        val esterne = db.fotoEsterne()
        val presenti = galleria.mapTo(HashSet()) { it.id }
        // Rimuove solo ciò che è davvero sparito: le foto di unità scollegate restano nell'indice
        // le foto di una scheda SD tolta restano nell'indice (con nomi e correzioni) finché non la rimetti
        val volumiMontati = runCatching { MediaStore.getExternalVolumeNames(ctx) }.getOrDefault(emptySet())
        db.completaVolumi(galleria)
        val volumi = db.volumi()
        val rimosse = gia.keys.filter { id ->
            val c = esterne[id]
            id !in presenti && if (c == null) volumi[id].let { it == null || it in volumiMontati } else (c in raggiungibili || c !in cartelle)
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
        // 3) Video (solo in carica, salvo diversa impostazione)
        if (!isStopped && fasevideo(db, video, raggiungibili, soloInCarica = true)) cambiato = true
        if (isStopped || !cambiato) return@withContext Result.success()
        setProgress(workDataOf("fase" to "volti", "fatte" to 0, "totale" to 0))
        runCatching { nm.notify(NOTIFICA, notifica("Raggruppo i volti…", 0, 0)) }
        Raggruppa.esegui(ctx)
        Result.success()
    }
}
