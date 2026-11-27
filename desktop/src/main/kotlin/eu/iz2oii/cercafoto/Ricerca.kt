package eu.iz2oii.cercafoto

import java.util.Calendar

/**
 * Ricerca in linguaggio semplice: ogni parola deve essere soddisfatta (AND).
 * Una parola può corrispondere a etichette, categorie, persone con nome, date, colori,
 * caratteristiche dei volti (donna, anziano, capelli biondi, maglia rossa), etichette manuali o testo letto.
 */
object Ricerca {

    private val STOP = setOf(
        "foto", "fotografia", "fotografie", "immagine", "immagini", "con", "di", "del", "della", "dello", "dei", "degli",
        "delle", "al", "alla", "allo", "ai", "agli", "alle", "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una",
        "e", "ed", "in", "nel", "nella", "nello", "nei", "negli", "nelle", "sul", "sulla", "sui", "sulle", "a", "da", "dal",
        "dalla", "per", "tra", "fra", "trova", "trovami", "mostra", "mostrami", "cerca", "cercami", "dove", "ci", "c",
        "sono", "che", "mie", "miei", "mio", "mia", "tutte", "tutti", "tutto", "qualche", "of", "and", "the", "with",
        "colore", "colori", "indossa", "porta",
    )
    private val MESI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
        "settembre", "ottobre", "novembre", "dicembre")
    private val PERSONE = setOf("Person", "Selfie", "Crowd", "Team", "Baby", "Bride", "Groom", "Grandparent", "Dude", "Crew")

    /** forme flesse -> colore canonico */
    private val COLORI: Map<String, String> = buildMap {
        fun f(c: String, vararg forme: String) { put(c, c); forme.forEach { put(it, c) } }
        f("rosso", "rossa", "rossi", "rosse", "red")
        f("arancione", "arancioni", "arancio", "orange")
        f("giallo", "gialla", "gialli", "gialle", "yellow")
        f("verde", "verdi", "green")
        f("azzurro", "azzurra", "azzurri", "azzurre", "celeste", "celesti")
        f("blu", "blue")
        f("viola", "lilla")
        f("rosa", "pink")
        f("marrone", "marroni", "brown")
        f("nero", "nera", "neri", "nere", "black")
        f("bianco", "bianca", "bianchi", "bianche", "white")
        f("grigio", "grigia", "grigi", "grigie", "gray", "grey")
    }

    /** parole che introducono un capo d'abbigliamento: il colore che segue è quello degli abiti */
    private val ABITI = setOf("vestito", "vestiti", "vestita", "vestite", "abito", "abiti", "maglia", "maglie", "maglietta",
        "magliette", "camicia", "camicie", "giacca", "giacche", "felpa", "felpe", "cappotto", "giubbotto", "maglione",
        "abbigliamento", "outfit", "tshirt", "top")
    private val CAPELLI = setOf("capelli", "capello", "chioma", "capigliatura")
    /** aggettivi che da soli indicano i capelli */
    private val CAPELLI_AGG = mapOf(
        "biondo" to setOf("biondo"), "bionda" to setOf("biondo"), "biondi" to setOf("biondo"), "bionde" to setOf("biondo"),
        "moro" to setOf("nero", "castano"), "mora" to setOf("nero", "castano"), "mori" to setOf("nero", "castano"),
        "more" to setOf("nero", "castano"), "bruno" to setOf("nero", "castano"), "bruna" to setOf("nero", "castano"),
        "castano" to setOf("castano"), "castana" to setOf("castano"), "castani" to setOf("castano"),
        "brizzolato" to setOf("grigio"), "brizzolata" to setOf("grigio"), "brizzolati" to setOf("grigio"),
        "rossiccio" to setOf("rosso"), "ramato" to setOf("rosso"), "ramati" to setOf("rosso"),
    )
    /** colore -> classi capelli */
    private val CAPELLI_COLORE = mapOf(
        "nero" to setOf("nero"), "marrone" to setOf("castano"), "rosso" to setOf("rosso"), "arancione" to setOf("rosso"),
        "giallo" to setOf("biondo"), "grigio" to setOf("grigio"), "bianco" to setOf("grigio"),
    )
    private val SCURI_CHIARI = mapOf("scuri" to setOf("nero", "castano"), "chiari" to setOf("biondo", "grigio"))

    private enum class Sesso { M, F }

    /** Vincoli che devono valere sullo stesso volto */
    private class VincoloVolto {
        var sesso: Sesso? = null
        var etaMin = 0f; var etaMax = 200f; var eta = false
        var capelli: Set<String>? = null
        var abiti: String? = null
        fun ok(a: VoltoAttr): Boolean {
            if ((sesso != null || eta) && !a.affidabile) return false
            sesso?.let { if (it == Sesso.M && a.maschio < 0.6f) return false; if (it == Sesso.F && a.maschio > 0.4f) return false }
            if (eta && (a.eta < etaMin || a.eta > etaMax)) return false
            capelli?.let { if (a.capelli == null || a.capelli !in it) return false }
            abiti?.let { if (a.abiti != it) return false }
            return true
        }
    }

    /** parola -> (sesso, età min, età max) */
    private val PERSONA_ATTR: Map<String, Triple<Sesso?, Float, Float>> = mapOf(
        "donna" to Triple(Sesso.F, 16f, 200f), "donne" to Triple(Sesso.F, 16f, 200f), "signora" to Triple(Sesso.F, 35f, 200f),
        "signore" to Triple(Sesso.M, 35f, 200f), "ragazza" to Triple(Sesso.F, 12f, 32f), "ragazze" to Triple(Sesso.F, 12f, 32f),
        "uomo" to Triple(Sesso.M, 16f, 200f), "uomini" to Triple(Sesso.M, 16f, 200f), "ragazzo" to Triple(Sesso.M, 12f, 32f),
        "ragazzi" to Triple(null, 12f, 32f), "maschio" to Triple(Sesso.M, 0f, 200f), "maschi" to Triple(Sesso.M, 0f, 200f),
        "femmina" to Triple(Sesso.F, 0f, 200f), "femmine" to Triple(Sesso.F, 0f, 200f),
        "bambina" to Triple(Sesso.F, 0f, 12f), "bambine" to Triple(Sesso.F, 0f, 12f),
        "anziano" to Triple(null, 60f, 200f), "anziani" to Triple(null, 60f, 200f), "anziana" to Triple(Sesso.F, 60f, 200f),
        "anziane" to Triple(Sesso.F, 60f, 200f), "vecchio" to Triple(null, 60f, 200f), "vecchia" to Triple(Sesso.F, 60f, 200f),
        "nonno" to Triple(Sesso.M, 60f, 200f), "nonna" to Triple(Sesso.F, 60f, 200f), "nonni" to Triple(null, 60f, 200f),
        "giovane" to Triple(null, 13f, 32f), "giovani" to Triple(null, 13f, 32f), "adolescente" to Triple(null, 12f, 19f),
        "adolescenti" to Triple(null, 12f, 19f), "adulto" to Triple(null, 25f, 60f), "adulti" to Triple(null, 25f, 60f),
        "adulta" to Triple(Sesso.F, 25f, 60f), "mezzaeta" to Triple(null, 40f, 60f),
    )

    private fun annoMese(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    private fun conTesto(f: FotoIdx, t: String) = t.length >= 3 && (f.testo.contains(t) || f.testoManuali.contains(t) ||
        Testo.normalizza(f.nome).contains(t) || Testo.normalizza(f.album).contains(t))

    /** minuscole e senza accenti, ma con punteggiatura e separatori (per nomi di file e cartelle) */
    fun semplificaPercorso(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('\\', '/')

    /**
     * Ricerca per nome file e cartelle: ogni parola deve comparire (anche in parte) nel percorso completo;
     * "frase tra virgolette" resta unita; * e ? funzionano come jolly (IMG_2023*, DSC_00??).
     */
    fun cercaPercorso(query: String, foto: List<FotoIdx>, percorso: (FotoIdx) -> String): List<FotoIdx> {
        val parole = Regex("\"([^\"]+)\"|(\\S+)").findAll(query).map { m -> semplificaPercorso(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            .filter { it.isNotBlank() }.toList()
        if (parole.isEmpty()) return foto
        val regole: List<(String) -> Boolean> = parole.map { p ->
            if ('*' in p || '?' in p) {
                val r = Regex(p.split('*').joinToString(".*") { pezzo -> pezzo.split('?').joinToString(".") { Regex.escape(it) } })
                val f: (String) -> Boolean = { s -> r.containsMatchIn(s) }; f
            } else { val f: (String) -> Boolean = { s -> s.contains(p) }; f }
        }
        return foto.filter { f -> val s = semplificaPercorso(percorso(f)); regole.all { it(s) } }
    }

    fun cerca(query: String, foto: List<FotoIdx>, persone: List<Persona>): List<FotoIdx> {
        var q = " " + Testo.normalizza(query) + " "
        if (q.isBlank()) return foto
        val criteri = ArrayList<(FotoIdx) -> Boolean>()

        // Etichette manuali di più parole ("casa nonna"): corrispondenza esatta della frase
        val frasiManuali = foto.asSequence().flatMap { it.manuali }.map { Testo.normalizza(it) }.filter { ' ' in it }.toSet()
        for (fr in frasiManuali.sortedByDescending { it.length }) {
            if (q.contains(" $fr ")) {
                criteri.add { f -> f.manuali.any { Testo.normalizza(it) == fr } }
                q = q.replace(" $fr ", " ")
            }
        }

        // Nomi di persona (anche di più parole), i più lunghi prima
        val giaNomi = HashMap<String, MutableSet<Long>>()
        for (p in persone) p.nome?.let { giaNomi.getOrPut(Testo.normalizza(it)) { mutableSetOf() }.add(p.id) }
        for ((nome, ids) in giaNomi.entries.sortedByDescending { it.key.length }) {
            if (nome.isNotEmpty() && q.contains(" $nome ")) {
                criteri.add { f -> f.persone.any { it in ids } }
                q = q.replace(" $nome ", " ")
            }
        }
        q = q.replace(" mezza eta ", " mezzaeta ").replace(" t shirt ", " tshirt ")

        // Termini composti del dizionario ("occhiali da sole")
        for (k in Dizionario.composti) {
            if (q.contains(" $k ")) {
                val et = Dizionario.etichettePer(k) ?: continue
                criteri.add { f -> f.etichette.keys.any { it in et } }
                q = q.replace(" $k ", " ")
            }
        }

        // Scansione parola per parola, con vincoli sui volti
        val parole = q.split(' ').filter { it.isNotBlank() }
        val volti = ArrayList<VincoloVolto>()
        var corrente: VincoloVolto? = null
        var attesa: String? = null   // "capelli" / "abiti" / "persona": a cosa si riferisce il prossimo colore
        var i = 0
        while (i < parole.size) {
            val t = parole[i]; i++
            if (t in STOP) continue
            val pa = PERSONA_ATTR[t]
            if (pa != null) {
                val c = corrente
                val nuovo = c == null || (pa.first != null && c.sesso != null) || c.eta
                val v = if (nuovo || c == null) VincoloVolto().also { volti += it } else c
                corrente = v
                pa.first?.let { v.sesso = it }
                if (pa.second > 0f || pa.third < 200f) { v.eta = true; v.etaMin = pa.second; v.etaMax = pa.third }
                attesa = "persona"
                continue
            }
            if (t in CAPELLI) { attesa = "capelli"; continue }
            val agg = CAPELLI_AGG[t]
            if (agg != null) {
                val v = corrente ?: VincoloVolto().also { volti += it }
                corrente = v; v.capelli = agg; continue
            }
            if (attesa == "capelli") {
                val cl = COLORI[t]?.let { CAPELLI_COLORE[it] } ?: SCURI_CHIARI[t]
                if (cl != null) {
                    val v = corrente ?: VincoloVolto().also { volti += it }
                    corrente = v; v.capelli = cl; attesa = null; continue
                }
            }
            if (t in ABITI) {
                // "vestito rosso" -> colore degli abiti; "vestito" da solo -> etichetta Dress & co.
                val prossimo = parole.drop(i).firstOrNull { it !in STOP }
                if (prossimo != null && COLORI.containsKey(prossimo)) { attesa = "abiti"; continue }
            }
            val col = COLORI[t]
            if (col != null) {
                if (attesa == "abiti" || attesa == "persona") {
                    val v = corrente ?: VincoloVolto().also { volti += it }
                    corrente = v; v.abiti = col; attesa = null; continue
                }
                criteri.add { f -> (f.colori[col] ?: 0) >= 12 || f.attr.any { it.abiti == col } }
                continue
            }
            attesa = null
            criteri.add(criterio(t, persone))
        }
        for (v in volti) criteri.add { f -> f.attr.any { v.ok(it) } }

        if (criteri.isEmpty()) return foto
        return foto.filter { f -> criteri.all { it(f) } }
    }

    private fun criterio(t: String, persone: List<Persona>): (FotoIdx) -> Boolean {
        // Anno
        if (t.length == 4 && (t.startsWith("19") || t.startsWith("20")) && t.all { it.isDigit() }) {
            val a = t.toInt(); return { f -> annoMese(f.data).first == a }
        }
        // Mese
        val m = MESI.indexOf(t)
        if (m >= 0) return { f -> annoMese(f.data).second == m }
        when (t) {
            "estate", "estivo", "estive" -> return { f -> annoMese(f.data).second in 5..7 }
            "inverno", "invernale" -> return { f -> annoMese(f.data).second in listOf(11, 0, 1) }
            "primavera" -> return { f -> annoMese(f.data).second in 2..4 }
            "autunno", "autunnale" -> return { f -> annoMese(f.data).second in 8..10 }
            "persona", "persone", "gente", "umani", "ritratto", "ritratti" ->
                return { f -> f.volti > 0 || f.etichette.keys.any { it in PERSONE } }
            "volto", "volti", "viso", "visi", "faccia", "facce" -> return { f -> f.volti > 0 }
            "gruppo", "gruppi", "comitiva", "amici", "folla" ->
                return { f -> f.volti >= 3 || "Crowd" in f.etichette || "Team" in f.etichette }
            "coppia" -> return { f -> f.volti == 2 }
            "sorriso", "sorrisi", "sorridente", "sorridenti", "sorride" ->
                return { f -> f.sorriso >= 0.7f || "Smile" in f.etichette || "Laugh" in f.etichette }
            "selfie", "autoscatto" -> return { f -> "Selfie" in f.etichette }
            "screenshot", "schermata", "schermate", "screen" ->
                return { f -> "Screenshot" in f.etichette || f.album.contains("screenshot", true) || f.nome.contains("screenshot", true) }
            "testo", "scritta", "scritte", "scritto" -> return { f -> f.testo.length >= 20 }
            "documento", "documenti", "carta", "fogli", "foglio" -> {
                val et = Dizionario.etichettePer(t) ?: emptySet()
                return { f -> f.testo.length >= 80 || f.etichette.keys.any { it in et } }
            }
            "colorata", "colorate", "colorati", "colorato", "variopinta", "variopinte" ->
                return { f -> f.colori.count { it.key !in setOf("nero", "bianco", "grigio") && it.value >= 8 } >= 3 }
            "bambino", "bambini", "bimbo", "bimbi", "bimba", "bimbe", "neonato", "neonati", "piccoli" ->
                return { f -> "Baby" in f.etichette || f.attr.any { it.affidabile && it.eta < 12f } }
            "nessuno", "vuote" -> return { f -> f.volti == 0 }
            "esterna", "esterne", "usb", "chiavetta", "disco" -> return { f -> f.cartella != null }
        }
        // Persona per nome singolo (es. "mario" quando il gruppo si chiama "Mario Rossi")
        val ids = persone.filter { p -> p.nome?.let { n -> Testo.normalizza(n).split(' ').any { it == t } } == true }.map { it.id }.toSet()
        val et = Dizionario.etichettePer(t)
        return { f ->
            (ids.isNotEmpty() && f.persone.any { it in ids }) ||
                (et != null && f.etichette.keys.any { it in et }) ||
                f.manuali.any { Testo.normalizza(it).split(' ').any { w -> w == t || (t.length >= 4 && w.startsWith(t)) } } ||
                conTesto(f, t)
        }
    }
}

/** Filtri scelti dal pannello: quelli sul volto devono valere sulla stessa persona */
data class Filtri(
    val sesso: String? = null,     // "donna" | "uomo"
    val eta: String? = null,       // "bambini" | "ragazzi" | "adulti" | "anziani"
    val capelli: String? = null,   // nero | castano | biondo | rosso | grigio
    val abiti: String? = null,     // nome colore
    val album: String? = null,     // cartella (nome album)
    val persone: Int? = null,      // 0 = nessuna, 1 = una, 2 = due, 3 = gruppo
) {
    val attivi: Int get() = listOfNotNull(sesso, eta, capelli, abiti, album, persone).size
    private val suVolto get() = sesso != null || eta != null || capelli != null || abiti != null

    fun okVolto(a: VoltoAttr): Boolean {
        if ((sesso != null || eta != null) && !a.affidabile) return false
        when (sesso) { "donna" -> if (a.maschio > 0.4f) return false; "uomo" -> if (a.maschio < 0.6f) return false }
        when (eta) {
            "bambini" -> if (a.eta >= 13f) return false
            "ragazzi" -> if (a.eta < 13f || a.eta >= 30f) return false
            "adulti" -> if (a.eta < 30f || a.eta >= 60f) return false
            "anziani" -> if (a.eta < 60f) return false
        }
        if (capelli != null && a.capelli != capelli) return false
        if (abiti != null && a.abiti != abiti) return false
        return true
    }

    fun applica(lista: List<FotoIdx>): List<FotoIdx> {
        if (attivi == 0) return lista
        return lista.filter { f ->
            (album == null || f.album == album) &&
                (persone == null || when (persone) { 0 -> f.volti == 0; 1 -> f.volti == 1; 2 -> f.volti == 2; else -> f.volti >= 3 }) &&
                (!suVolto || f.attr.any { okVolto(it) })
        }
    }
}
