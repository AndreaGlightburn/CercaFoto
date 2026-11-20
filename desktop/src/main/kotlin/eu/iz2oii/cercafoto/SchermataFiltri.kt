package eu.iz2oii.cercafoto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// ---------------------------------------------------------------- elenco filtri

@Composable
fun SchermataFiltri(finestra: java.awt.Window, apri: (Long) -> Unit, vaiCerca: () -> Unit) {
    val filtri by Stato.filtriUtente.collectAsState()
    val foto by Stato.foto.collectAsState()   // per ricalcolare i conteggi quando l'indice cambia
    var nuovoDescr by remember { mutableStateOf(false) }
    val conteggi = remember(filtri, foto) { filtri.associate { it.id to FiltriUtente.membri(it, Stato.vettoriClip()).size } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("I miei filtri", style = MaterialTheme.typography.headlineSmall)
        Text("Insegna all'app a riconoscere cose nuove: un luogo, una situazione, un oggetto, un animale, una pianta… " +
            "Tutto avviene su questo PC: un filtro è solo una piccola serie di numeri, si può esportare e importare senza foto.",
            style = MaterialTheme.typography.bodySmall)
        if (!Clip.disponibile) Text("Modelli non disponibili in questa installazione.", color = MaterialTheme.colorScheme.error)
        Card(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Nuovo filtro da esempi (il più preciso)", fontWeight = FontWeight.Bold)
                Text("1. Vai in Cerca e trova qualche foto del soggetto (anche con la ricerca libera).\n" +
                    "2. Tieni premuto per selezionare 5–20 foto che lo rappresentano bene.\n" +
                    "3. Clicca «Crea filtro da esempi…» e dagli un nome.\n" +
                    "4. Nella pagina del filtro escludi le foto sbagliate: l'app impara la differenza.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(vaiCerca) { Text("Vai a Cerca") }
                    OutlinedButton({ nuovoDescr = true }) { Text("Nuovo filtro da descrizione…") }
                    OutlinedButton({
                        val d = FileDialog(finestra as? Frame, "Importa filtro", FileDialog.LOAD).apply { file = "*.cercafiltro"; isVisible = true }
                        if (d.file != null) Stato.importaFiltro(File(d.directory, d.file))
                    }) { Text("Importa filtro…") }
                }
            }
        }
        if (filtri.isEmpty()) Text("Nessun filtro ancora.", Modifier.padding(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtri, key = { it.id }) { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.nome, fontWeight = FontWeight.Bold)
                            Text((if (f.esempi) "Da esempi: ${f.positivi.size} giusti, ${f.negativi.size} esclusi" else "Da descrizione: " + f.frasi.joinToString(" · ")) +
                                " — ${conteggi[f.id] ?: 0} foto", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ Stato.filtroAttivo.value = f.id; Stato.query.value = ""; vaiCerca() }) { Text("Usa in Cerca") }
                        OutlinedButton({ apri(f.id) }) { Text("Apri") }
                    }
                }
            }
        }
    }
    if (nuovoDescr) DialogoFiltroDescrizione({ nuovoDescr = false }) { apri(it) }
}

@Composable
fun DialogoFiltroDescrizione(chiudi: () -> Unit, creato: (Long) -> Unit) {
    var nome by remember { mutableStateOf("") }
    var frasi by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Nuovo filtro da descrizione") },
        text = {
            Column(Modifier.width(480.dp)) {
                OutlinedTextField(nome, { nome = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome del filtro (es. Grigliata)") })
                OutlinedTextField(frasi, { frasi = it }, Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp),
                    label = { Text("Una descrizione per riga") }, placeholder = { Text("barbecue in giardino\ncarne sulla griglia") })
                Text("Più descrizioni diverse dello stesso soggetto lo rendono più affidabile. Potrai poi correggerlo con gli esempi.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                if (nome.isNotBlank() && frasi.isNotBlank()) { Stato.creaFiltroDaDescrizione(nome, frasi.lines()) { creato(it) }; chiudi() }
            }) { Text("Crea") }
        },
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

// ---------------------------------------------------------------- pagina di un filtro

@Composable
fun SchermataFiltro(id: Long, finestra: java.awt.Window, apriFoto: (List<FotoIdx>, Int) -> Unit, indietro: () -> Unit, vaiCerca: () -> Unit) {
    val filtri by Stato.filtriUtente.collectAsState()
    val foto by Stato.foto.collectAsState()
    val f = filtri.firstOrNull { it.id == id }
    if (f == null) { LaunchedEffect(Unit) { indietro() }; return }
    val perId = remember(foto) { foto.associateBy { it.id } }
    val membri = remember(f, foto) { FiltriUtente.membri(f, Stato.vettoriClip()).mapNotNull { perId[it.first] } }
    var sel by remember(id) { mutableStateOf(setOf<Long>()) }
    var rinomina by remember { mutableStateOf(false) }
    var elimina by remember { mutableStateOf(false) }
    var soglia by remember(f.id, f.tipo) { mutableFloatStateOf(f.soglia) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(indietro) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            Column(Modifier.weight(1f)) {
                Text(f.nome, style = MaterialTheme.typography.titleLarge)
                Text(if (f.esempi) "Imparato da ${f.positivi.size} esempi giusti e ${f.negativi.size} esclusi" else "Da descrizione: " + f.frasi.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ Stato.filtroAttivo.value = f.id; Stato.query.value = ""; vaiCerca() }) { Text("Usa in Cerca") }
            TextButton({ Stato.aggiuntaEsempi.value = f.id; vaiCerca() }) { Text("Aggiungi esempi…") }
            TextButton({ rinomina = true }) { Text("Rinomina") }
            TextButton({
                val d = FileDialog(finestra as? Frame, "Esporta filtro", FileDialog.SAVE).apply { file = "${f.nome}.cercafiltro"; isVisible = true }
                if (d.file != null) Stato.esportaFiltro(f, File(d.directory, d.file.let { if (it.endsWith(".cercafiltro")) it else "$it.cercafiltro" }))
            }) { Text("Esporta…") }
            TextButton({ elimina = true }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Sensibilità", fontWeight = FontWeight.Bold)
            Text("  più foto", style = MaterialTheme.typography.bodySmall)
            Slider(soglia, { soglia = it; Stato.sogliaFiltro(f.id, it) }, valueRange = f.minimo..f.massimo, modifier = Modifier.width(320.dp))
            Text("più preciso", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            ZoomAnteprime()
        }
        Row(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (sel.isEmpty()) "${membri.size} foto trovate, dalla più pertinente · tieni premuto sulle foto sbagliate per escluderle"
                else "${sel.size} selezionate", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (sel.isNotEmpty()) {
                Button({ Stato.correggiFiltro(f.id, sel, positivi = false); sel = emptySet() }) { Text("Non c'entrano: escludi") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ Stato.correggiFiltro(f.id, sel, positivi = true); sel = emptySet() }) { Text("Sono esempi perfetti") }
                TextButton({ sel = emptySet() }) { Text("Annulla") }
            }
        }
        GrigliaFoto(membri, sel, onLongClick = { x -> sel = if (x.id in sel) sel - x.id else sel + x.id }) { i ->
            if (sel.isNotEmpty()) { val fid = membri[i].id; sel = if (fid in sel) sel - fid else sel + fid } else apriFoto(membri, i)
        }
    }
    if (rinomina) DialogoNome("Nuovo nome del filtro", "Salva", { rinomina = false }) { Stato.rinominaFiltro(f.id, it) }
    if (elimina) AlertDialog(
        onDismissRequest = { elimina = false },
        title = { Text("Eliminare il filtro «${f.nome}»?") },
        text = { Text("Le foto non vengono toccate.") },
        confirmButton = { TextButton({ Stato.eliminaFiltro(f.id); elimina = false; indietro() }) { Text("Elimina") } },
        dismissButton = { TextButton({ elimina = false }) { Text("Annulla") } },
    )
}
