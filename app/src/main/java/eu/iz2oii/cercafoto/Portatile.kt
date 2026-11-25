package eu.iz2oii.cercafoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import eu.iz2oii.cercafoto.IndicePortatile.FotoP
import eu.iz2oii.cercafoto.IndicePortatile.PersonaP
import eu.iz2oii.cercafoto.IndicePortatile.VoltoP
import java.util.UUID

/** Indice portatile su Android: letto dalle unità/cartelle esterne per non rianalizzare, scritto per il PC */
object Portatile {

    private fun pref(ctx: Context) = ctx.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)

    fun dispositivo(ctx: Context): String = pref(ctx).getString("dispositivo", null)
        ?: UUID.randomUUID().toString().also { pref(ctx).edit().putString("dispositivo", it).apply() }

    /** scrive l'indice sul disco per il PC (di norma sì) */
    fun attivo(ctx: Context, tree: String) = pref(ctx).getBoolean("portatile|$tree", true)
    fun setAttivo(ctx: Context, tree: String, v: Boolean) = pref(ctx).edit().putBoolean("portatile|$tree", v).remove("firma|$tree").apply()
    fun usate(ctx: Context) = pref(ctx).getInt("portatile_usate", 0)
    fun errore(ctx: Context, tree: String): String? = pref(ctx).getString("portatile_err|$tree", null)

    /** Percorso relativo alla radice della cartella scelta, dal document id (es. "1A2B-3C4D:DCIM/a.jpg") */
    fun relativo(tree: String, uri: Uri): String? = runCatching {
        val radice = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        val doc = DocumentsContract.getDocumentId(uri)
        if (!doc.startsWith(radice)) return null
        val resto = doc.substring(radice.length)
        if (radice.endsWith(":") || radice.endsWith("/") || resto.startsWith("/")) IndicePortatile.normalizzaPercorso(resto) else null
    }.getOrNull()

    /** Indici trovati in una cartella (anche in sottocartelle), ciascuno con il prefisso del punto in cui si trova */
    class Indici(private val lista: List<Pair<String, IndicePortatile.Pacchetto>>) {
        val pacchetti get() = lista.map { it.second }
        private fun cerca(rel: String, f: (IndicePortatile.Pacchetto, String) -> Any?): Any? {
            for ((pref, p) in lista) {
                val sub = if (pref.isEmpty()) rel else if (rel.startsWith("$pref/", ignoreCase = true)) rel.substring(pref.length + 1) else continue
                f(p, sub)?.let { return it }
            }
            return null
        }
        fun foto(rel: String, s: Long) = cerca(rel) { p, r -> p.foto(r, s) } as FotoP?
        fun video(rel: String, s: Long) = cerca(rel) { p, r -> p.video(r, s) } as IndicePortatile.VideoP?
    }

    /** Legge gli indici trovati durante la visita della cartella; ignora quelli scritti da questo telefono */
    fun carica(ctx: Context, tree: String, trovati: List<Uri>): Indici? {
        val l = ArrayList<Pair<String, IndicePortatile.Pacchetto>>()
        val mio = dispositivo(ctx)
        for (u in trovati) runCatching {
            val p = ctx.contentResolver.openInputStream(u)!!.use { IndicePortatile.leggi(it.buffered()) }
            if (p.dispositivo == mio) return@runCatching
            // prefisso = cartella che contiene ".cercafoto"
            val rel = relativo(tree, u) ?: return@runCatching
            val pref = rel.substringBeforeLast("/${IndicePortatile.CARTELLA}/", "").let { if (rel.startsWith("${IndicePortatile.CARTELLA}/")) "" else it }
            l += pref to p
        }
        return if (l.isEmpty()) null else Indici(l.sortedByDescending { it.first.length })
    }

    /** uid delle persone degli indici -> persone locali (per uid, poi per nome; altrimenti nuove) */
    fun mappaPersone(db: Db, indici: Indici): HashMap<String, Long> {
        val w = db.writableDatabase
        val locali = db.caricaPersone()
        val uidLocali = HashMap<String, Long>()
        w.rawQuery("SELECT id, uid FROM persona WHERE uid IS NOT NULL", null).use { c -> while (c.moveToNext()) uidLocali[c.getString(1)] = c.getLong(0) }
        val mappa = HashMap<String, Long>()
        for (p in indici.pacchetti.flatMap { it.persone }) {
            if (p.uid in mappa) continue
            val id = uidLocali[p.uid] ?: locali.firstOrNull { it.nome != null && Testo.normalizza(it.nome) == Testo.normalizza(p.nome) }?.id
                ?: w.insert("persona", null, ContentValues().apply { put("nome", p.nome); put("nascosta", if (p.nascosta) 1 else 0) })
            w.update("persona", ContentValues().apply { put("uid", p.uid) }, "id=? AND (uid IS NULL OR uid = '')", arrayOf(id.toString()))
            mappa[p.uid] = id
        }
        return mappa
    }

    fun risultato(x: FotoP): Analizzatore.Risultato = Analizzatore.Risultato(
        x.etichette.entries.sortedByDescending { it.value }.map { it.key to it.value },
        x.volti.filter { !it.manuale }.map { v ->
            Analizzatore.Volto(v.l, v.t, v.r, v.b, v.emb, v.eta?.let { Analizzatore.Attributi(it, v.maschio ?: 0.5f, v.capelli, v.abiti) })
        },
        x.sorriso, x.testo ?: "", x.colori,
    ).also { r -> if (x.d > 0) r.dataExif = x.d }

    /** Salva la foto con l'analisi dell'indice e riporta persone ed etichette manuali */
    fun usa(db: Db, m: MediaFoto, x: FotoP, persone: Map<String, Long>) {
        db.salva(m, risultato(x))
        applicaPersone(db, m.id, x, persone)
        if (x.manuali.isNotEmpty() || x.rimosse.isNotEmpty()) db.etichetteManuali(m.id, x.manuali, x.rimosse.toSet())
    }

    fun applicaPersone(db: Db, fotoId: Long, x: FotoP, persone: Map<String, Long>) {
        val w = db.writableDatabase
        val ids = ArrayList<Long>()
        w.rawQuery("SELECT id FROM volto WHERE foto_id = ? AND manuale = 0 ORDER BY id", arrayOf(fotoId.toString())).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        for ((i, v) in x.volti.filter { !it.manuale }.withIndex()) {
            val vid = ids.getOrNull(i) ?: break
            val p = if (v.escluso) Db.ESCLUSO else v.uid?.let { persone[it] } ?: continue
            w.execSQL("UPDATE volto SET persona_id = ? WHERE id = ?", arrayOf<Any>(p, vid))
        }
        val manuali = x.volti.filter { it.manuale }.mapNotNull { v -> v.uid?.let { persone[it] } }
        for (p in manuali) db.associa(listOf(fotoId), p)
    }

    // ------------------------------------------------------------ scrittura (per il PC)

    private fun firma(db: Db, tree: String): String {
        val r = db.readableDatabase
        fun q(sql: String, vararg a: String) = r.rawQuery(sql, a).use { c -> if (c.moveToFirst()) (0 until c.columnCount).joinToString("/") { c.getString(it) ?: "" } else "" }
        return q("SELECT COUNT(*), IFNULL(SUM(modificata),0), IFNULL(SUM(volti),0) FROM foto WHERE cartella = ?", tree) + "|" +
            q("SELECT COUNT(*), IFNULL(SUM(v.persona_id),0) FROM volto v JOIN foto f ON f.id = v.foto_id WHERE f.cartella = ?", tree) + "|" +
            q("SELECT IFNULL(group_concat(id || nome, ','), '') FROM persona WHERE nome IS NOT NULL AND nome <> ''").hashCode() + "|" +
            q("SELECT IFNULL(group_concat(IFNULL(manuali,'') || IFNULL(rimosse,''), ','), '') FROM foto WHERE cartella = ?", tree).hashCode()
    }

    /** Scrive l'indice di una cartella esterna nella sua radice, se è cambiato. [dimensioni] = id foto -> byte */
    fun scrivi(ctx: Context, db: Db, tree: String, dimensioni: Map<Long, Long>) {
        if (!attivo(ctx, tree)) return
        val f = firma(db, tree)
        if (pref(ctx).getString("firma|$tree", null) == f) return
        val esito = runCatching {
            val r = db.readableDatabase
            // persone con nome e loro uid
            val uidDi = HashMap<Long, String>(); val persone = ArrayList<PersonaP>()
            r.rawQuery("SELECT id, nome, nascosta, uid FROM persona WHERE nome IS NOT NULL AND nome <> ''", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val uid = c.getString(3)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
                        db.writableDatabase.update("persona", ContentValues().apply { put("uid", it) }, "id=?", arrayOf(id.toString()))
                    }
                    uidDi[id] = uid; persone += PersonaP(uid, c.getString(1), c.getInt(2) == 1)
                }
            }
            val volti = HashMap<Long, MutableList<VoltoP>>()
            r.rawQuery("SELECT v.foto_id, v.l, v.t, v.r, v.b, v.emb, v.eta, v.maschio, v.capelli, v.abiti, v.persona_id, v.manuale FROM volto v JOIN foto f ON f.id = v.foto_id WHERE f.cartella = ?", arrayOf(tree)).use { c ->
                while (c.moveToNext()) {
                    val p = c.getLong(10); val man = c.getInt(11) == 1; val uid = uidDi[p]
                    if (man && uid == null) continue
                    volti.getOrPut(c.getLong(0)) { mutableListOf() } += VoltoP(c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getFloat(4),
                        if (c.isNull(5)) null else c.getBlob(5).toFloats(), if (c.isNull(6)) null else c.getFloat(6), if (c.isNull(7)) null else c.getFloat(7),
                        c.getString(8), c.getString(9), uid, p == Db.ESCLUSO, man)
                }
            }
            val foto = ArrayList<FotoP>()
            r.rawQuery("SELECT id, uri, data, etichette, colori, testo, sorriso, manuali, rimosse FROM foto WHERE cartella = ? AND video_id = 0", arrayOf(tree)).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val s = dimensioni[id] ?: continue
                    val rel = c.getString(1)?.let { relativo(tree, Uri.parse(it)) } ?: continue
                    foto += FotoP(rel, s, c.getLong(2), Db.decodifica(c.getString(3)), Db.decodifica(c.getString(4)), c.getString(5), c.getFloat(6),
                        volti[id] ?: emptyList(), null,
                        c.getString(7)?.split(';')?.filter { it.isNotBlank() } ?: emptyList(), c.getString(8)?.split(';')?.filter { it.isNotBlank() } ?: emptyList())
                }
            }
            val buf = java.io.ByteArrayOutputStream()
            IndicePortatile.scrivi(IndicePortatile.Pacchetto("android", dispositivo(ctx), System.currentTimeMillis(), persone, foto, emptyList()), buf)
            scriviFile(ctx, tree, buf.toByteArray())
        }
        pref(ctx).edit().apply {
            if (esito.isSuccess) { putString("firma|$tree", f); remove("portatile_err|$tree") }
            else putString("portatile_err|$tree", "Non riesco a scrivere sul disco: riaggiungi la cartella per dare il permesso di scrittura")
        }.apply()
    }

    private fun figlio(ctx: Context, treeUri: Uri, padre: String, nome: String): String? {
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, padre),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == nome) return c.getString(0)
        }
        return null
    }

    private fun scriviFile(ctx: Context, tree: String, dati: ByteArray) {
        val treeUri = Uri.parse(tree)
        val radice = DocumentsContract.getTreeDocumentId(treeUri)
        val cr = ctx.contentResolver
        val dir = figlio(ctx, treeUri, radice, IndicePortatile.CARTELLA)?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            ?: DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, radice), DocumentsContract.Document.MIME_TYPE_DIR, IndicePortatile.CARTELLA)
            ?: error("cartella non creata")
        val dirId = DocumentsContract.getDocumentId(dir)
        val nome = IndicePortatile.nomeFile("android")
        val file = figlio(ctx, treeUri, dirId, nome)?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            ?: DocumentsContract.createDocument(cr, dir, "application/octet-stream", nome)
            ?: error("file non creato")
        cr.openOutputStream(file, "wt")!!.use { it.write(dati) }
    }
}
