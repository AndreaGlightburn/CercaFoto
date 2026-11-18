package eu.iz2oii.cercafoto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    application {
        Window(
            onCloseRequest = ::exitApplication, title = "Cerca Foto",
            state = rememberWindowState(width = 1320.dp, height = 860.dp),
            icon = painterResource("icona.png"),
        ) {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2568C8), primaryContainer = Color(0xFFD7E6FB), secondaryContainer = Color(0xFFDDE6F3))) {
                Surface(Modifier.fillMaxSize()) { App(window) }
            }
        }
    }
}

enum class Scheda { CERCA, PERSONE, INDICE }

@Composable
fun App(finestra: java.awt.Window) {
    var scheda by remember { mutableStateOf(if (Impostazioni.cartelle.isEmpty()) Scheda.INDICE else Scheda.CERCA) }
    var visore by remember { mutableStateOf<Pair<List<FotoIdx>, Int>?>(null) }
    var personaAperta by remember { mutableStateOf<Long?>(null) }
    val lavoro by Stato.lavoro.collectAsState()
    val messaggio by Stato.messaggio.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(messaggio) { messaggio?.let { snackbar.showSnackbar(it, withDismissAction = true); Stato.messaggio.value = null } }
    LaunchedEffect(Unit) { if (Impostazioni.cartelle.isNotEmpty()) Stato.indicizza() }

    val apri: (List<FotoIdx>, Int) -> Unit = { l, i -> visore = l to i }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                NavigationRailItem(scheda == Scheda.CERCA, { scheda = Scheda.CERCA }, { Icon(Icons.Default.Search, null) }, label = { Text("Cerca") })
                NavigationRailItem(scheda == Scheda.PERSONE, { scheda = Scheda.PERSONE; personaAperta = null }, { Icon(Icons.Default.Person, null) }, label = { Text("Persone") })
                NavigationRailItem(scheda == Scheda.INDICE, { scheda = Scheda.INDICE }, { Icon(Icons.Default.Settings, null) }, label = { Text("Cartelle") })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (scheda) {
                    Scheda.CERCA -> SchermataCerca(apri)
                    Scheda.PERSONE -> personaAperta?.let { SchermataPersona(it, apri) { personaAperta = null } } ?: SchermataPersone { personaAperta = it }
                    Scheda.INDICE -> SchermataIndice(finestra)
                }
            }
        }
        visore?.let { (l, i) -> Visore(l, i, apriPersona = { id -> visore = null; scheda = Scheda.PERSONE; personaAperta = id }) { visore = null } }
        lavoro?.let { t ->
            Card(Modifier.align(Alignment.Center)) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(28.dp)); Spacer(Modifier.width(16.dp)); Text(t) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private val SUGGERIMENTI = listOf("Persone", "Gruppi", "Sorrisi", "Bambini", "Donne", "Uomini", "Anziani", "Giovani", "Animali", "Cani", "Gatti", "Cibo", "Mare", "Montagna", "Natura", "Città", "Auto", "Feste", "Documenti", "Capelli biondi", "Rosso", "Blu", "Colorate", "Estate", "Natale")

// ---------------------------------------------------------------- CERCA

@Composable
fun SchermataCerca(apri: (List<FotoIdx>, Int) -> Unit) {
    val q by Stato.query.collectAsState()
    val ris by Stato.risultati.collectAsState()
    val persone by Stato.persone.collectAsState()
    val filtri by Stato.filtri.collectAsState()
    val av by Stato.avanzamento.collectAsState()
    var selezione by remember { mutableStateOf(setOf<Long>()) }
    var pannello by remember { mutableStateOf(false) }
    var aggiungi by remember { mutableStateOf(false) }

    val descr by Stato.descrizione.collectAsState()
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        if (Clip.disponibile) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            FilterChip(!descr, { Stato.descrizione.value = false }, label = { Text("Parole chiave") })
            FilterChip(descr, { Stato.descrizione.value = true }, label = { Text("Descrizione libera") })
            Text(if (descr) "Descrivi la foto come la racconteresti: «bambino che soffia le candeline», «tramonto al mare con la barca»"
                else "Nomi, persone, oggetti, colori, date: «Mario cane», «donna anziana», «2023 mare»", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { Stato.query.value = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (descr) "Descrivi la foto che cerchi…" else "Cerca: Mario, donna anziana, maglia rossa, cane, mare, 2023…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ Stato.query.value = "" }) { Icon(Icons.Default.Close, "Cancella") } })
            Spacer(Modifier.width(8.dp))
            BadgedBox(badge = { if (filtri.attivi > 0) Badge { Text("${filtri.attivi}") } }) {
                OutlinedButton({ pannello = true }) { Icon(Icons.AutoMirrored.Filled.List, null); Spacer(Modifier.width(6.dp)); Text("Filtri") }
            }
        }
        if (filtri.attivi > 0) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                items(descriviFiltri(filtri)) { (t, togli) ->
                    InputChip(true, { Stato.filtri.value = togli(Stato.filtri.value) }, label = { Text(t) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                items(persone.filter { it.nome != null && !it.nascosta }.map { it.nome!! } + SUGGERIMENTI) { s ->
                    SuggestionChip({ Stato.query.value = s }, label = { Text(s) })
                }
            }
        }
        if (av.attivo && av.totale > 0) {
            LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("Indicizzazione: ${av.fatte} di ${av.totale}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selezione.isEmpty()) "${ris.size} foto · tieni premuto su una foto per selezionare" else "${selezione.size} selezionate",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (selezione.isNotEmpty()) {
                TextButton({ selezione = ris.map { it.id }.toSet() }) { Text("Tutte") }
                TextButton({ aggiungi = true }) { Text("Aggiungi a una persona…") }
                TextButton({ selezione = emptySet() }) { Text("Annulla selezione") }
            }
        }
        GrigliaFoto(ris, selezione, onLongClick = { f -> selezione = if (f.id in selezione) selezione - f.id else selezione + f.id }) { i ->
            if (selezione.isNotEmpty()) { val id = ris[i].id; selezione = if (id in selezione) selezione - id else selezione + id } else apri(ris, i)
        }
    }
    if (pannello) PannelloFiltri(filtri, applica = { Stato.filtri.value = it }) { pannello = false }
    if (aggiungi) DialogoAggiungiPersona(selezione.toList(), { aggiungi = false }) { selezione = emptySet() }
}

// ---------------------------------------------------------------- VISORE

private val formatoData = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)

fun mostraInEsplora(f: File) {
    runCatching {
        if (System.getProperty("os.name").lowercase().contains("win")) Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", f.absolutePath))
        else Desktop.getDesktop().open(f.parentFile)
    }
}

@Composable
fun Visore(lista: List<FotoIdx>, iniziale: Int, apriPersona: (Long) -> Unit, chiudi: () -> Unit) {
    var i by remember { mutableStateOf(iniziale.coerceIn(0, maxOf(0, lista.size - 1))) }
    val tutte by Stato.foto.collectAsState()
    val persone by Stato.persone.collectAsState()
    val f0 = lista.getOrNull(i) ?: run { chiudi(); return }
    val f = tutte.firstOrNull { it.id == f0.id } ?: f0
    var img by remember(f.id) { mutableStateOf<ImageBitmap?>(null) }
    var dettagli by remember(f.id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var volti by remember(f.id) { mutableStateOf<List<VoltoInfo>>(emptyList()) }
    var voltoScelto by remember { mutableStateOf<VoltoInfo?>(null) }
    var modifica by remember { mutableStateOf(false) }
    var aggiungi by remember { mutableStateOf(false) }
    LaunchedEffect(f.id) {
        img = withContext(Dispatchers.IO) { runCatching { Immagini.leggi(f.file, 2400)!!.toComposeImageBitmap() }.getOrNull() }
        dettagli = withContext(Dispatchers.IO) {
            val file = f.file
            buildList {
                add("File" to file.absolutePath)
                if (file.exists()) add("Dimensione" to "%.1f MB".format(Locale.ITALIAN, file.length() / 1e6)) else add("Stato" to "file non raggiungibile (unità scollegata?)")
                addAll(Immagini.exif(file))
            }
        }
    }
    LaunchedEffect(f.id, f.persone) { volti = withContext(Dispatchers.IO) { Db.voltiFoto(f.id) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(Modifier.fillMaxSize().background(Color(0xFF111111)).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionRight -> { if (i < lista.size - 1) i++; true }
            Key.DirectionLeft -> { if (i > 0) i--; true }
            Key.Escape -> { chiudi(); true }
            else -> false
        }
    }) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            img?.let { Image(it, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(chiudi) { Icon(Icons.Default.Close, "Chiudi (Esc)", tint = Color.White) }
                Text("${i + 1} / ${lista.size}", color = Color.White)
            }
            if (i > 0) IconButton({ i-- }, Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Precedente", tint = Color.White) }
            if (i < lista.size - 1) IconButton({ i++ }, Modifier.align(Alignment.CenterEnd)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Successiva", tint = Color.White) }
        }
        Column(Modifier.width(380.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(formatoData.format(Date(f.data)), fontWeight = FontWeight.Bold)
            Text(f.nome, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            for ((k, v) in dettagli) Text("$k: $v", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ runCatching { Desktop.getDesktop().open(f.file) } }) { Text("Apri") }
                TextButton({ mostraInEsplora(f.file) }) { Text("Mostra nella cartella") }
            }
            if (volti.isNotEmpty()) {
                Text("Persone (clic per vedere o cambiare il gruppo, ✋ = aggiunta a mano)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(volti, key = { it.id }) { v ->
                        val p = persone.firstOrNull { it.id == v.persona }
                        Column(Modifier.width(76.dp).clickable { voltoScelto = v }, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (v.manuale && p != null) VoltoMini(p, 60.dp) else VoltoMini(v.id, 60.dp)
                            Text((if (v.manuale) "✋ " else "") + (p?.nome ?: if (p != null) "senza nome" else "nessun gruppo"),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            for (a in f.attr) Text("• " + descriviVolto(a, persone), style = MaterialTheme.typography.bodySmall)
            TextButton({ aggiungi = true }) { Text("Aggiungi a una persona…") }
            if (f.colori.isNotEmpty()) Text("Colori: " + f.colori.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} ${it.value}%" }, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (f.etichette.isNotEmpty()) Text("Contiene (stima): " + f.etichette.entries.sortedByDescending { it.value }.joinToString(", ") { Dizionario.italiano(it.key) }, style = MaterialTheme.typography.bodySmall)
            Text("Le tue etichette", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text(if (f.manuali.isEmpty()) "Nessuna" else f.manuali.joinToString(", "))
            TextButton({ modifica = true }) { Text("Modifica etichette") }
            TextButton({ Stato.query.value = ""; Stato.filtri.value = Filtri(album = f.album); chiudi() }) { Text("Altre foto di questa cartella") }
        }
    }
    voltoScelto?.let { v -> DialogoVolto(v, persone, { voltoScelto = null }) { id -> voltoScelto = null; apriPersona(id) } }
    if (aggiungi) DialogoAggiungiPersona(listOf(f.id), { aggiungi = false }) {}
    if (modifica) DialogoEtichette(f) { modifica = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoEtichette(f: FotoIdx, chiudi: () -> Unit) {
    var manuali by remember(f.id) { mutableStateOf(f.manuali) }
    var nuova by remember { mutableStateOf("") }
    fun aggiungi() { val t = nuova.trim(); if (t.isNotEmpty() && t !in manuali) manuali = manuali + t; nuova = "" }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Etichette della foto") },
        text = {
            Column(Modifier.width(420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(nuova, { nuova = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("es. Sardegna 2023") })
                    TextButton(::aggiungi) { Text("Aggiungi") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in manuali) InputChip(true, { manuali = manuali - m }, label = { Text(m) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) })
                }
            }
        },
        confirmButton = { TextButton({ aggiungi(); Stato.salvaEtichette(f.id, manuali, f.rimosse); chiudi() }) { Text("Salva") } },
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

// ---------------------------------------------------------------- PERSONE

fun profiliPersone(foto: List<FotoIdx>): Map<Long, VoltoAttr> {
    val per = HashMap<Long, MutableList<VoltoAttr>>()
    for (f in foto) for (a in f.attr) if (a.persona > 0) per.getOrPut(a.persona) { mutableListOf() }.add(a)
    return per.mapValues { (id, l) ->
        val buoni = l.filter { it.affidabile }.ifEmpty { l }
        VoltoAttr(id, buoni.map { it.eta }.sorted()[buoni.size / 2], buoni.map { it.maschio }.average().toFloat(),
            l.mapNotNull { it.capelli }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key, null, buoni.maxOf { it.area })
    }
}

@Composable
fun SchermataPersone(apri: (Long) -> Unit) {
    val persone by Stato.persone.collectAsState()
    val foto by Stato.foto.collectAsState()
    val av by Stato.avanzamento.collectAsState()
    var cerca by remember { mutableStateOf("") }
    var conNome by remember { mutableStateOf<Boolean?>(null) }
    var tutte by remember { mutableStateOf(false) }
    var filtri by remember { mutableStateOf(Filtri()) }
    var pannello by remember { mutableStateOf(false) }
    var riorganizza by remember { mutableStateOf(false) }
    val profili = remember(foto) { profiliPersone(foto) }
    val q = Testo.normalizza(cerca)
    val visibili = persone.filter { tutte || (!it.nascosta && (it.nome != null || it.foto >= 2)) }
        .filter { q.isEmpty() || it.nome?.let { n -> Testo.normalizza(n).contains(q) } == true }
        .filter { conNome == null || (it.nome != null) == conNome }
        .filter { p -> filtri.attivi == 0 || profili[p.id]?.let { filtri.okVolto(it) } == true }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Persone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton({ riorganizza = true }, enabled = !av.attivo) { Text("Riorganizza volti senza nome…") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            OutlinedTextField(cerca, { cerca = it }, Modifier.width(320.dp), singleLine = true, placeholder = { Text("Cerca un nome") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            FilterChip(conNome == false, { conNome = if (conNome == false) null else false }, label = { Text("Senza nome") })
            FilterChip(conNome == true, { conNome = if (conNome == true) null else true }, label = { Text("Con nome") })
            FilterChip(filtri.attivi > 0, { pannello = true }, label = { Text(if (filtri.attivi > 0) descriviFiltri(filtri).joinToString(", ") { it.first } else "Sesso, età, capelli…") })
            if (filtri.attivi > 0) IconButton({ filtri = Filtri() }) { Icon(Icons.Default.Close, "Togli filtri") }
            FilterChip(tutte, { tutte = !tutte }, label = { Text("Anche volti singoli e nascosti") })
        }
        Text("${visibili.size} gruppi" + if (av.attivo && av.fase == "volti") " · sto raggruppando i volti…" else "", style = MaterialTheme.typography.labelLarge)
        LazyVerticalGrid(GridCells.Adaptive(130.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(visibili, key = { it.id }) { p ->
                Column(Modifier.padding(6.dp).clickable { apri(p.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    VoltoMini(p, 100.dp)
                    Text(p.nome ?: "Senza nome", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (p.nome != null) FontWeight.Bold else FontWeight.Normal)
                    Text("${p.foto} foto" + if (p.nascosta) " · nascosta" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (pannello) PannelloFiltri(filtri, conCartelle = false, applica = { filtri = it }) { pannello = false }
    if (riorganizza) DialogoRiorganizza { riorganizza = false }
}

@Composable
fun DialogoRiorganizza(chiudi: () -> Unit) {
    var soglia by remember { mutableFloatStateOf(Impostazioni.soglia) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Riorganizza volti senza nome") },
        text = {
            Column(Modifier.width(460.dp)) {
                Text("Le persone con nome e i volti tolti a mano non vengono toccati.", style = MaterialTheme.typography.bodySmall)
                Text("Severità del riconoscimento", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Slider(soglia, { soglia = it }, valueRange = 0.35f..0.75f)
                Row { Text("Permissivo: unisce di più", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("Severo: separa di più", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(12.dp))
                Button({ Impostazioni.soglia = soglia; Stato.ricalcolaGruppi(); chiudi() }) { Text("Riorganizza ora (veloce)") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ Impostazioni.soglia = soglia; Stato.indicizza(rianalisiVolti = true); chiudi() }) { Text("Rianalizza in alta risoluzione (lenta)") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(chiudi) { Text("Chiudi") } },
    )
}

@Composable
fun SchermataPersona(id: Long, apriFoto: (List<FotoIdx>, Int) -> Unit, indietro: () -> Unit) {
    val persone by Stato.persone.collectAsState()
    val foto by Stato.foto.collectAsState()
    val p = persone.firstOrNull { it.id == id }
    if (p == null) { LaunchedEffect(Unit) { indietro() }; return }
    val sue = remember(foto, id) { foto.filter { id in it.persone } }
    var sel by remember(id) { mutableStateOf(setOf<Long>()) }
    var rinomina by remember { mutableStateOf(false) }
    var unisci by remember { mutableStateOf(false) }
    var sposta by remember { mutableStateOf(false) }
    var nuova by remember { mutableStateOf(false) }
    var simili by remember(id, persone.size) { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    LaunchedEffect(id, persone.size) { simili = withContext(Dispatchers.IO) { Stato.somiglianze(id) } }
    var daUnire by remember { mutableStateOf<Persona?>(null) }
    val candidati = persone.filter { it.id != id && it.nome == null && (simili[it.id] ?: 0f) >= 0.45f }.sortedByDescending { simili[it.id] }.take(14)

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(indietro) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            VoltoMini(p, 56.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p.nome ?: "Senza nome", style = MaterialTheme.typography.titleLarge)
                Text("${sue.size} foto", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton({ rinomina = true }) { Text(if (p.nome == null) "Dai un nome" else "Rinomina") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ unisci = true }) { Text("Unisci a…") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton({ Stato.nascondi(p.id, !p.nascosta) }) { Text(if (p.nascosta) "Mostra" else "Nascondi") }
        }
        if (candidati.isNotEmpty()) {
            Text("Potrebbe essere anche… (clic per unire)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(candidati, key = { it.id }) { o ->
                    Column(Modifier.width(80.dp).clickable { daUnire = o }, horizontalAlignment = Alignment.CenterHorizontally) {
                        VoltoMini(o, 60.dp); Text("${((simili[o.id] ?: 0f) * 100).toInt()}% · ${o.foto}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (sel.isEmpty()) "Tieni premuto su una foto sbagliata per selezionarla" else "${sel.size} selezionate", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (sel.isNotEmpty()) {
                TextButton({ sel = sue.map { it.id }.toSet() }) { Text("Tutte") }
                TextButton({ Stato.escludiFoto(sel.toList(), id); sel = emptySet() }) { Text("Togli") }
                Button({ sposta = true }) { Text("Sposta in…") }
                TextButton({ sel = emptySet() }) { Text("Annulla") }
            }
        }
        GrigliaFoto(sue, sel, onLongClick = { f -> sel = if (f.id in sel) sel - f.id else sel + f.id }) { i ->
            if (sel.isNotEmpty()) { val fid = sue[i].id; sel = if (fid in sel) sel - fid else sel + fid } else apriFoto(sue, i)
        }
    }
    if (rinomina) {
        var t by remember { mutableStateOf(p.nome ?: "") }
        val qn = Testo.normalizza(t)
        val uguali = if (qn.length < 2) emptyList() else persone.filter { it.id != id && it.nome != null && Testo.normalizza(it.nome).contains(qn) }.take(5)
        AlertDialog(
            onDismissRequest = { rinomina = false },
            title = { Text("Nome della persona") },
            text = {
                Column(Modifier.width(420.dp)) {
                    OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("es. Mario") })
                    if (uguali.isNotEmpty()) {
                        Text("Esiste già? Clic per unire questo gruppo:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        for (o in uguali) RigaPersona(o, null) { Stato.unisci(o.id, id); rinomina = false }
                    }
                }
            },
            confirmButton = { TextButton({ Stato.rinomina(id, t); rinomina = false }) { Text("Salva") } },
            dismissButton = { TextButton({ rinomina = false }) { Text("Annulla") } },
        )
    }
    if (unisci) DialogoScegliPersona("È la stessa persona di…", persone, id, simili, { unisci = false }) { o -> Stato.unisci(id, o.id); unisci = false }
    if (sposta) {
        var s2 by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
        LaunchedEffect(sel) { s2 = withContext(Dispatchers.IO) { Stato.somiglianzeFoto(sel.toList(), id) } }
        DialogoScegliPersona("Sposta ${sel.size} foto in…", persone, id, s2, { sposta = false },
            extra = { TextButton({ sposta = false; nuova = true }) { Text("+ Nuova persona") } }) { o -> Stato.spostaFoto(sel.toList(), id, o); sel = emptySet(); sposta = false }
    }
    if (nuova) DialogoNome("Nuova persona", "Crea e sposta", { nuova = false }) { Stato.nuovaPersonaDaFoto(sel.toList(), id, it); sel = emptySet() }
    daUnire?.let { o ->
        val anteprima = remember(foto, o.id) { foto.filter { o.id in it.persone }.take(12) }
        AlertDialog(
            onDismissRequest = { daUnire = null },
            title = { Text("È ${p.nome ?: "questa persona"}?") },
            text = {
                Column(Modifier.width(520.dp)) {
                    Text("Gruppo senza nome con ${o.foto} foto, somiglianza ${((simili[o.id] ?: 0f) * 100).toInt()}%.")
                    Box(Modifier.height(320.dp)) { GrigliaFoto(anteprima, lato = 110.dp) {} }
                }
            },
            confirmButton = { TextButton({ Stato.unisci(id, o.id); daUnire = null }) { Text("Sì, unisci") } },
            dismissButton = { TextButton({ daUnire = null }) { Text("No") } },
        )
    }
}

// ---------------------------------------------------------------- CARTELLE / INDICE

@Composable
fun SchermataIndice(finestra: java.awt.Window) {
    val av by Stato.avanzamento.collectAsState()
    val cartelle by Stato.cartelle.collectAsState()
    val (nFoto, nVolti, nPersone) = Stato.conteggi.collectAsState().value
    var confermaAzzera by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Cartelle e indice", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cartelle con le foto", fontWeight = FontWeight.Bold)
                Text("Puoi aggiungere cartelle del PC, dischi esterni o chiavette. Se un'unità è scollegata, le sue foto restano cercabili.", style = MaterialTheme.typography.bodySmall)
                if (cartelle.isEmpty()) Text("Nessuna cartella: aggiungine una per iniziare (es. Immagini).", Modifier.padding(vertical = 8.dp))
                for (c in cartelle) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c + if (!File(c).isDirectory) "  (non raggiungibile)" else "", Modifier.weight(1f))
                    IconButton({ Stato.rimuoviCartella(c) }) { Icon(Icons.Default.Close, "Rimuovi") }
                }
                Button({
                    val ch = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Scegli una cartella con le foto" }
                    if (ch.showOpenDialog(finestra) == JFileChooser.APPROVE_OPTION) Stato.aggiungiCartella(ch.selectedFile)
                }) { Text("Aggiungi cartella…") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Foto analizzate: $nFoto · Volti: $nVolti · Persone: $nPersone")
                when {
                    av.attivo && av.fase == "volti" -> { Text("Raggruppo i volti…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    av.attivo && av.totale > 0 -> {
                        Text(when (av.fase) { "volti_hd" -> "Rianalisi volti in alta risoluzione: "; "clip" -> "Preparo la ricerca libera e gli oggetti: "; else -> "Analisi foto: " } + "${av.fatte} di ${av.totale}")
                        LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    av.attivo -> { Text("Lettura delle cartelle…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> Text("Indice aggiornato. All'avvio l'app controlla le foto nuove o modificate.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (av.attivo) OutlinedButton({ Stato.ferma() }) { Text("Ferma") } else Button({ Stato.indicizza() }) { Text("Aggiorna indice") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Scambio con Cerca Foto per Android", fontWeight = FontWeight.Bold)
                Text("Esporta un file con le persone a cui hai dato un nome e le tue correzioni (niente foto). Importandolo sul telefono, " +
                    "le stesse persone vengono riconosciute anche lì. Funziona nei due sensi.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton({
                        val d = FileDialog(finestra as? Frame, "Esporta persone", FileDialog.SAVE).apply { file = "persone.cercafoto"; isVisible = true }
                        if (d.file != null) Stato.esporta(File(d.directory, d.file.let { if (it.endsWith(".cercafoto")) it else "$it.cercafoto" }))
                    }, enabled = !av.attivo) { Text("Esporta…") }
                    OutlinedButton({
                        val d = FileDialog(finestra as? Frame, "Importa persone", FileDialog.LOAD).apply { file = "*.cercafoto"; isVisible = true }
                        if (d.file != null) Stato.importa(File(d.directory, d.file))
                    }, enabled = !av.attivo) { Text("Importa…") }
                }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("🔒 Privacy", fontWeight = FontWeight.Bold)
                Text("Tutta l'analisi avviene su questo PC con modelli inclusi nell'app: nessuna foto, volto o dato viene inviato in rete. " +
                    "L'indice è in ${Percorsi.base.absolutePath}.")
            }
        }
        OutlinedButton({ confermaAzzera = true }, enabled = !av.attivo) { Text("Rianalizza tutto da capo") }
        Text("Cerca Foto per Windows ${System.getProperty("jpackage.app-version") ?: ""} · Pony10 · Modelli: FaceNet (Apache 2.0), UltraFace (MIT), età/sesso (MIT), CLIP multilingue (Apache 2.0 / MIT)", style = MaterialTheme.typography.bodySmall)
    }
    if (confermaAzzera) AlertDialog(
        onDismissRequest = { confermaAzzera = false },
        title = { Text("Rianalizzare tutto?") },
        text = { Text("Cancello l'indice e rianalizzo tutte le foto. I nomi delle persone e le etichette andranno persi (esportali prima, se vuoi).") },
        confirmButton = { TextButton({ Stato.azzera(); confermaAzzera = false }) { Text("Rianalizza") } },
        dismissButton = { TextButton({ confermaAzzera = false }) { Text("Annulla") } },
    )
}
