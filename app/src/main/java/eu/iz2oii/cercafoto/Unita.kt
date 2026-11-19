package eu.iz2oii.cercafoto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore

/** Unità esterne (chiavette e dischi USB-C, schede SD): nomi leggibili e rilevamento */
object Unita {
    /** uuid -> descrizione (es. "SanDisk USB"), ricordata anche quando l'unità è scollegata */
    @Volatile var descrizioni: Map<String, String> = emptyMap()
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("unita", Context.MODE_PRIVATE)

    fun collegate(ctx: Context): List<StorageVolume> =
        runCatching { ctx.getSystemService(StorageManager::class.java).storageVolumes }.getOrDefault(emptyList())
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.uuid != null }

    /** Le schede SD sono già nella galleria di Android; le unità USB no (vanno aggiunte come cartella) */
    fun inGalleria(ctx: Context, v: StorageVolume): Boolean {
        val nome = v.mediaStoreVolumeName ?: return false
        return runCatching { nome in MediaStore.getExternalVolumeNames(ctx) }.getOrDefault(false)
    }

    fun aggiorna(ctx: Context) {
        val p = prefs(ctx)
        val m = HashMap<String, String>()
        p.all.forEach { (k, v) -> if (v is String) m[k] = v }
        val ed = p.edit()
        for (v in collegate(ctx)) {
            val d = v.getDescription(ctx)
            m[v.uuid!!] = d; ed.putString(v.uuid!!, d)
        }
        ed.apply()
        descrizioni = m
    }

    fun uuidDi(tree: String): String? =
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree)).substringBefore(':') }.getOrNull()

    /** Unità USB collegate che non sono ancora tra le cartelle cercate */
    fun daProporre(ctx: Context): List<StorageVolume> {
        val gia = Impostazioni.cartelle(ctx).mapNotNull { uuidDi(it) }.toSet()
        val rifiutate = prefs(ctx).getStringSet("_rifiutate", emptySet())!!
        return collegate(ctx).filter { !inGalleria(ctx, it) && it.uuid !in gia && it.uuid !in rifiutate }
    }

    fun rifiuta(ctx: Context, uuid: String) {
        val p = prefs(ctx)
        p.edit().putStringSet("_rifiutate", p.getStringSet("_rifiutate", emptySet())!! + uuid).apply()
    }

    /** Uuid delle unità rimovibili collegate in questo momento */
    fun uuidCollegati(ctx: Context): Set<String> = collegate(ctx).mapNotNull { it.uuid }.toSet()
}

/** Quando si collega un'unità esterna: aggiorna i nomi e cerca foto nuove nelle cartelle già aggiunte */
class UnitaCollegata : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Unita.aggiorna(ctx)
        if (Impostazioni.cartelle(ctx).isNotEmpty() && haPermessoFoto(ctx)) Indicizzatore.avvia(ctx)
    }
}
