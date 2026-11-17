package eu.iz2oii.cercafoto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaFoto(val id: Long, val data: Long, val modificata: Long, val nome: String, val album: String)

object Impostazioni {
    private fun p(ctx: Context) = ctx.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)
    fun ocr(ctx: Context) = p(ctx).getBoolean("ocr", true)
    fun setOcr(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("ocr", v).apply()
    fun soglia(ctx: Context) = p(ctx).getFloat("soglia", 0.5f)
    fun setSoglia(ctx: Context, v: Float) = p(ctx).edit().putFloat("soglia", v).apply()
}

class Indicizzatore(private val ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {

    companion object {
        const val NOME = "indicizza"
        private const val CANALE = "indice"
        private const val NOTIFICA = 42

        fun avvia(ctx: Context, sostituisci: Boolean = false) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NOME,
                if (sostituisci) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<Indicizzatore>().build()
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
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
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

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        runCatching { setForeground(foreground("Lettura galleria…", 0, 0)) }
        val db = Db.get(ctx)
        val galleria = leggiGalleria(ctx)
        val gia = db.modifiche()
        val presenti = galleria.mapTo(HashSet()) { it.id }
        val rimosse = gia.keys.filter { it !in presenti }
        db.rimuovi(rimosse)
        val daFare = galleria.filter { gia[it.id] != it.modificata }
        val tot = daFare.size
        val nm = ctx.getSystemService(NotificationManager::class.java)

        if (tot > 0) {
            Analizzatore(ctx, Impostazioni.ocr(ctx)).use { an ->
                for ((i, m) in daFare.withIndex()) {
                    if (isStopped) break
                    val r = runCatching {
                        var bmp = ctx.contentResolver.loadThumbnail(uriFoto(m.id), Size(1280, 1280), null)
                        if (bmp.config != Bitmap.Config.ARGB_8888) {
                            val c = bmp.copy(Bitmap.Config.ARGB_8888, false); bmp.recycle(); bmp = c
                        }
                        an.analizza(bmp).also { bmp.recycle() }
                    }.getOrNull()
                    db.salva(m, r)
                    if (i % 5 == 0 || i == tot - 1) {
                        setProgress(workDataOf("fase" to "analisi", "fatte" to i + 1, "totale" to tot))
                        runCatching { nm.notify(NOTIFICA, notifica("Foto ${i + 1} di $tot", i + 1, tot)) }
                    }
                }
            }
        }
        if (isStopped || (tot == 0 && rimosse.isEmpty())) return@withContext Result.success()
        setProgress(workDataOf("fase" to "volti", "fatte" to 0, "totale" to 0))
        runCatching { nm.notify(NOTIFICA, notifica("Raggruppo i volti…", 0, 0)) }
        Raggruppa.esegui(ctx)
        Result.success()
    }
}
