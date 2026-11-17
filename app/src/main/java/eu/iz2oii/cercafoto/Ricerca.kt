package eu.iz2oii.cercafoto

import java.util.Calendar

/**
 * Ricerca in linguaggio semplice: ogni parola deve essere soddisfatta (AND),
 * ogni parola può corrispondere a etichette, categorie, persone con nome, date o testo letto nella foto.
 */
object Ricerca {

    private val STOP = setOf(
        "foto", "fotografia", "fotografie", "immagine", "immagini", "con", "di", "del", "della", "dello", "dei", "degli",
        "delle", "al", "alla", "allo", "ai", "agli", "alle", "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una",
        "e", "ed", "in", "nel", "nella", "nello", "nei", "negli", "nelle", "sul", "sulla", "sui", "sulle", "a", "da", "dal",
        "dalla", "per", "tra", "fra", "trova", "trovami", "mostra", "mostrami", "cerca", "cercami", "dove", "ci", "c",
        "sono", "che", "mie", "miei", "mio", "mia", "tutte", "tutti", "tutto", "qualche", "of", "and", "the", "with",
    )
    private val MESI = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
        "settembre", "ottobre", "novembre", "dicembre")
    private val PERSONE = setOf("Person", "Selfie", "Crowd", "Team", "Baby", "Bride", "Groom", "Grandparent", "Dude", "Crew")

    private fun annoMese(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) to c.get(Calendar.MONTH)
    }

    private fun conTesto(f: FotoIdx, t: String) = t.length >= 3 && (f.testo.contains(t) || Testo.normalizza(f.nome).contains(t) || Testo.normalizza(f.album).contains(t))

    fun cerca(query: String, foto: List<FotoIdx>, persone: List<Persona>): List<FotoIdx> {
        var q = " " + Testo.normalizza(query) + " "
        if (q.isBlank()) return foto
        val criteri = ArrayList<(FotoIdx) -> Boolean>()

        // Nomi di persona (anche di più parole), i più lunghi prima
        val nominate = persone.filter { it.nome != null }.sortedByDescending { it.nome!!.length }
        val giaNomi = HashMap<String, MutableSet<Long>>()
        for (p in nominate) giaNomi.getOrPut(Testo.normalizza(p.nome!!)) { mutableSetOf() }.add(p.id)
        for ((nome, ids) in giaNomi.entries.sortedByDescending { it.key.length }) {
            if (nome.isNotEmpty() && q.contains(" $nome ")) {
                criteri.add { f -> f.persone.any { it in ids } }
                q = q.replace(" $nome ", " ")
            }
        }
        // Termini composti del dizionario ("occhiali da sole")
        for (k in Dizionario.composti) {
            if (q.contains(" $k ")) {
                val et = Dizionario.etichettePer(k) ?: continue
                criteri.add { f -> f.etichette.keys.any { it in et } }
                q = q.replace(" $k ", " ")
            }
        }

        for (t in q.split(' ').filter { it.isNotBlank() && it !in STOP }) {
            criteri.add(criterio(t, persone))
        }
        if (criteri.isEmpty()) return foto
        return foto.filter { f -> criteri.all { it(f) } }
    }

    private fun criterio(t: String, persone: List<Persona>): (FotoIdx) -> Boolean {
        // Anno
        if (t.length == 4 && (t.startsWith("19") || t.startsWith("20")) && t.all { it.isDigit() }) {
            val a = t.toInt(); return { f -> annoMese(f.data).first == a }
        }
        // Mese
        MESI.indexOf(t).takeIf { it >= 0 }?.let { m -> return { f -> annoMese(f.data).second == m } }
        // Stagioni (estate si cerca anche come "mare" tramite dizionario: qui prevale la data)
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
            "senza" -> return { true }
            "nessuno", "vuote" -> return { f -> f.volti == 0 }
        }
        // Persona per nome singolo (es. "mario" quando il gruppo si chiama "Mario Rossi")
        val ids = persone.filter { p -> p.nome != null && Testo.normalizza(p.nome).split(' ').any { it == t } }.map { it.id }.toSet()
        val et = Dizionario.etichettePer(t)
        if (ids.isEmpty() && et == null) return { f -> conTesto(f, t) }
        return { f ->
            (ids.isNotEmpty() && f.persone.any { it in ids }) ||
                (et != null && f.etichette.keys.any { it in et }) ||
                (t.length >= 4 && conTesto(f, t))
        }
    }
}
