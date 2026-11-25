package eu.iz2oii.cercafoto

import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Video: estrazione dei fotogrammi chiave con FFmpeg (incluso nell'app, build LGPL, lavora solo in locale).
 * Ogni fotogramma chiave viene poi analizzato come una foto (volti, oggetti, colori, ricerca libera).
 */
object Video {
    val ESTENSIONI = setOf("mp4", "mov", "m4v", "mkv", "avi", "3gp", "webm", "mts", "m2ts", "wmv", "mpg", "mpeg")

    private val cartellaApp: File? = System.getProperty("compose.application.resources.dir")?.let { File(it, "ffmpeg") }?.takeIf { it.isDirectory }

    private fun eseguibile(nome: String): String? {
        cartellaApp?.let { d -> listOf("$nome.exe", nome).map { File(d, it) }.firstOrNull { it.isFile }?.let { return it.absolutePath } }
        val path = System.getenv("PATH") ?: return null
        for (d in path.split(File.pathSeparator)) for (n in listOf("$nome.exe", nome)) {
            val f = File(d, n); if (f.isFile && f.canExecute()) return f.absolutePath
        }
        return null
    }

    val ffmpeg: String? by lazy { eseguibile("ffmpeg") }
    val ffplay: String? by lazy { eseguibile("ffplay") }
    val disponibile get() = ffmpeg != null

    class Info(val durataMs: Long, val larg: Int, val alt: Int, val creazione: Long?)

    private fun esegui(cmd: List<String>, timeoutS: Long, riga: (String) -> Unit = {}): Int {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        p.inputStream.bufferedReader().useLines { it.forEach(riga) }
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 }
        return p.exitValue()
    }

    /** Durata, dimensioni (già ruotate) e data di registrazione, lette dalla risposta di ffmpeg -i */
    fun info(f: File): Info? {
        val ff = ffmpeg ?: return null
        var durata = 0L; var w = 0; var h = 0; var rot = 0; var creazione: Long? = null
        val reDur = Regex("""Duration: (\d+):(\d+):(\d+(?:\.\d+)?)""")
        val reDim = Regex(""",\s(\d{2,5})x(\d{2,5})[\s,\[]""")
        val reRot = Regex("""rotation of (-?\d+(?:\.\d+)?) degrees""")
        val reData = Regex("""creation_time\s*:\s*(\S+)""")
        var primoVideo = true
        esegui(listOf(ff, "-hide_banner", "-nostdin", "-i", f.absolutePath), 60) { l ->
            reDur.find(l)?.let { m -> durata = ((m.groupValues[1].toLong() * 3600 + m.groupValues[2].toLong() * 60) * 1000 + (m.groupValues[3].toDouble() * 1000).toLong()) }
            if (l.contains("Video:") && primoVideo) reDim.find(l)?.let { m -> w = m.groupValues[1].toInt(); h = m.groupValues[2].toInt(); primoVideo = false }
            reRot.find(l)?.let { m -> rot = m.groupValues[1].toDouble().toInt() }
            if (creazione == null) reData.find(l)?.let { m -> creazione = runCatching { Instant.parse(m.groupValues[1]).toEpochMilli() }.getOrNull()?.takeIf { it > 315532800000L } }
        }
        if (w == 0 && durata == 0L) return null
        if (Math.abs(rot) % 180 == 90) { val t = w; w = h; h = t }
        return Info(durata, w, h, creazione)
    }

    /**
     * Estrae i fotogrammi chiave in [dir]: uno ogni [intervallo] secondi più i cambi di scena.
     * Decodifica solo i fotogrammi "chiave" del file (velocissimo); se sono troppo radi decodifica tutto.
     * Ritorna (file jpg, istante in ms).
     */
    fun fotogrammi(f: File, dir: File, durataMs: Long, intervallo: Double): List<Pair<File, Long>> {
        val ff = ffmpeg ?: return emptyList()
        fun estrai(soloChiave: Boolean): List<Pair<File, Long>> {
            dir.listFiles()?.forEach { it.delete() }
            val tempi = ArrayList<Long>()
            val re = Regex("""pts_time:(-?\d+(?:\.\d+)?)""")
            val iv = "%.2f".format(java.util.Locale.US, intervallo)
            val cmd = buildList {
                addAll(listOf(ff, "-hide_banner", "-nostdin"))
                if (soloChiave) addAll(listOf("-skip_frame", "nokey"))
                addAll(listOf("-i", f.absolutePath, "-an", "-sn", "-dn", "-vf",
                    "select='isnan(prev_selected_t)+gte(t-prev_selected_t,$iv)+gt(scene,0.3)*gte(t-prev_selected_t,1.5)'," +
                        "scale='if(gt(iw,ih),min(1600,iw),-2)':'if(gt(iw,ih),-2,min(1600,ih))',showinfo",
                    "-fps_mode", "vfr", "-q:v", "3", File(dir, "f_%05d.jpg").absolutePath))
            }
            esegui(cmd, maxOf(120L, durataMs / 1000 * 2)) { l -> if (l.contains("Parsed_showinfo")) re.find(l)?.let { tempi += (it.groupValues[1].toDouble() * 1000).toLong().coerceAtLeast(0) } }
            val file = dir.listFiles { x -> x.name.startsWith("f_") }?.sortedBy { it.name } ?: emptyList()
            return file.mapIndexed { i, x -> x to (tempi.getOrNull(i) ?: (i * intervallo * 1000).toLong()) }
        }
        var r = estrai(true)
        val attesi = durataMs / 1000.0 / intervallo
        if (durataMs > 20_000 && r.size < attesi / 3) r = estrai(false)
        return r
    }

    /** Intervallo tra i fotogrammi: 3 s per i video brevi, cresce per quelli lunghi (max ~600 fotogrammi) */
    fun intervallo(durataMs: Long): Double = maxOf(3.0, durataMs / 1000.0 / 600.0)

    /** Riproduce dal punto indicato: VLC se installato, altrimenti il lettore incluso, altrimenti il predefinito */
    fun riproduci(f: File, ms: Long) {
        val s = ms / 1000.0
        val vlc = listOf(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).filterNotNull()
            .map { File(it, "VideoLAN/VLC/vlc.exe") }.firstOrNull { it.isFile }
        runCatching {
            when {
                vlc != null -> ProcessBuilder(vlc.absolutePath, "--start-time=${"%.1f".format(java.util.Locale.US, s)}", f.absolutePath).start()
                ffplay != null -> ProcessBuilder(ffplay!!, "-hide_banner", "-loglevel", "error", "-ss", "%.2f".format(java.util.Locale.US, s),
                    "-window_title", "${f.name} — Cerca Foto (spazio = pausa, frecce = avanti/indietro, Esc = chiudi)", "-autoexit", f.absolutePath).start()
                else -> java.awt.Desktop.getDesktop().open(f)
            }
        }.onFailure { runCatching { java.awt.Desktop.getDesktop().open(f) } }
    }
}

fun tempoVideo(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
