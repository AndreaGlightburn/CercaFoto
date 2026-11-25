package eu.iz2oii.cercafoto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val SUGGERIMENTI = listOf(
    "Persone", "Gruppi", "Selfie", "Sorrisi", "Animali", "Cani", "Gatti", "Cibo", "Mare", "Montagna",
    "Natura", "Neve", "Città", "Auto", "Feste", "Bambini", "Donne", "Uomini", "Anziani", "Giovani",
    "Capelli biondi", "Rosso", "Blu", "Verde", "Colorate", "Documenti", "Scontrini", "Screenshot", "Chiavetta",
)

// ---------------------------------------------------------------- CERCA

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchermataCerca(vm: AppVM, apri: (List<FotoIdx>, Int) -> Unit, vaiIndice: () -> Unit) {
    val ctx = LocalContext.current
    val q by vm.query.collectAsStateWithLifecycle()
    val ris by vm.risultati.collectAsStateWithLifecycle()
    val foto by vm.foto.collectAsStateWithLifecycle()
    val persone by vm.persone.collectAsStateWithLifecycle()
    val av by vm.avanzamento.collectAsStateWithLifecycle()
    val caricato by vm.caricato.collectAsStateWithLifecycle()
    val filtri by vm.filtri.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var selezione by remember { mutableStateOf(setOf<Long>()) }
    var pannelloFiltri by remember { mutableStateOf(false) }
    var azione by remember { mutableStateOf<TipoOp?>(null) }
    var aggiungiPersona by remember { mutableStateOf(false) }
    val selezionate = remember(selezione, ris) { ris.filter { it.id in selezione } }
    if (selezione.isNotEmpty()) androidx.activity.compose.BackHandler { selezione = emptySet() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (selezione.isNotEmpty()) {
            // Barra della selezione multipla
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ selezione = emptySet() }) { Icon(Icons.Default.Close, "Annulla selezione") }
                Text("${selezione.size} selezionate", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton({ selezione = ris.map { it.id }.toSet() }) { Text("Tutte") }
                IconButton({ condividi(ctx, selezionate) }) { Icon(Icons.Default.Share, "Condividi") }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Altro") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Copia in…") }, { menu = false; azione = TipoOp.COPIA })
                        DropdownMenuItem({ Text("Sposta in…") }, { menu = false; azione = TipoOp.SPOSTA })
                        DropdownMenuItem({ Text("Elimina…") }, { menu = false; azione = TipoOp.ELIMINA })
                        DropdownMenuItem({ Text("Aggiungi a una persona…") }, { menu = false; aggiungiPersona = true })
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = q, onValueChange = { vm.query.value = it },
                    modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    placeholder = { Text("cane al mare, Mario, 2024…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Default.Close, "Cancella") } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                )
                BadgedBox(badge = { if (filtri.attivi > 0) Badge { Text("${filtri.attivi}") } }, Modifier.padding(horizontal = 8.dp)) {
                    IconButton({ pannelloFiltri = true }) { Icon(Icons.AutoMirrored.Filled.List, "Filtri") }
                }
            }
        }
        if (filtri.attivi > 0) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(descriviFiltri(filtri)) { (testo, togli) ->
                    InputChip(true, { vm.filtri.value = togli(vm.filtri.value) }, label = { Text(testo) },
                        trailingIcon = { Icon(Icons.Default.Close, "Togli", Modifier.size(16.dp)) })
                }
            }
        } else {
            val nomi = persone.filter { it.nome != null && !it.nascosta }.map { it.nome!! }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nomi + SUGGERIMENTI) { s ->
                    SuggestionChip(onClick = { vm.query.value = s; focus.clearFocus() }, label = { Text(s) })
                }
            }
        }
        BannerUnita(vm)
        val nVideo by vm.nVideo.collectAsStateWithLifecycle()
        val tipo by vm.tipo.collectAsStateWithLifecycle()
        if (nVideo > 0) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tipo == null, { vm.tipo.value = null }, label = { Text("Tutto") })
            FilterChip(tipo == "foto", { vm.tipo.value = "foto" }, label = { Text("Foto") })
            FilterChip(tipo == "video", { vm.tipo.value = "video" }, label = { Text("Video ($nVideo)") })
        }
        if (av.attivo && av.totale > 0) {
            LinearProgressIndicator(
                progress = { av.fatte / av.totale.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(12.dp, 4.dp)
            )
            Text("Indicizzazione: ${av.fatte} di ${av.totale} — i risultati crescono man mano",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (caricato && foto.isEmpty() && !av.attivo) {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Indice vuoto", fontWeight = FontWeight.Bold)
                    Text("Avvia l'analisi delle foto dalla scheda Indice.")
                    TextButton(onClick = vaiIndice) { Text("Vai a Indice") }
                }
            }
        } else {
            Text(
                (if (q.isBlank() && filtri.attivi == 0) "${ris.size} elementi indicizzati" else "${ris.size} trovati") +
                    if (selezione.isEmpty() && ris.isNotEmpty()) " · tieni premuto per selezionare" else "",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(12.dp, 6.dp)
            )
        }
        GrigliaFoto(
            ris, selezionate = selezione,
            onLongClick = { f -> focus.clearFocus(); selezione = if (f.id in selezione) selezione - f.id else selezione + f.id },
        ) { i ->
            focus.clearFocus()
            if (selezione.isNotEmpty()) { val id = ris[i].id; selezione = if (id in selezione) selezione - id else selezione + id }
            else apri(ris, i)
        }
    }

    if (pannelloFiltri) PannelloFiltri(vm, filtri, { vm.filtri.value = it }) { pannelloFiltri = false }
    if (aggiungiPersona) DialogoAggiungiPersona(vm, selezionate, { aggiungiPersona = false }) { selezione = emptySet() }
    azione?.let { t ->
        DialogoAzione(vm, t, selezionate, chiudi = { azione = null }) { selezione = emptySet() }
    }
}

/** Proposta di aggiungere un'unità USB appena collegata */
@Composable
fun BannerUnita(vm: AppVM, inIndice: Boolean = false) {
    val nuove by vm.unitaNuove.collectAsStateWithLifecycle()
    val apri = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { vm.aggiungiCartella(it) }
    }
    for (u in nuove) Card(Modifier.fillMaxWidth().padding(12.dp, 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Unità collegata: ${u.nome}", fontWeight = FontWeight.Bold)
            Text("Vuoi cercare le foto anche qui? Nel selettore conferma la cartella principale dell'unità (o una sua cartella) e tocca «Usa questa cartella».",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ runCatching { apri.launch(u.intent) } }) { Text("Aggiungi") }
                if (!inIndice) TextButton({ vm.rifiutaUnita(u.uuid) }) { Text("Non ora") }
            }
        }
    }
}

fun condividi(ctx: Context, lista0: List<FotoIdx>) {
    if (lista0.isEmpty()) return
    // per i video si condivide il filmato, non il fotogramma
    val uri = lista0.map { it.video ?: it.uri }.distinct()
    val tipo = if (lista0.any { it.isVideo }) (if (lista0.all { it.isVideo }) "video/*" else "*/*") else "image/*"
    val i = if (uri.size == 1) Intent(Intent.ACTION_SEND).setType(tipo).putExtra(Intent.EXTRA_STREAM, uri[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).setType(tipo)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uri))
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Condividi"))
}

val NOMI_ETA = mapOf("bambini" to "Bambini (0-12)", "ragazzi" to "Ragazzi (13-29)", "adulti" to "Adulti (30-59)", "anziani" to "Anziani (60+)")
val NOMI_CAPELLI = mapOf("nero" to "Neri", "castano" to "Castani", "biondo" to "Biondi", "rosso" to "Rossi", "grigio" to "Grigi/bianchi")
private val NOMI_PERSONE = mapOf(0 to "Nessuna persona", 1 to "Una persona", 2 to "Due persone", 3 to "Gruppo (3+)")

fun descriviFiltri(f: Filtri): List<Pair<String, (Filtri) -> Filtri>> = buildList {
    f.sesso?.let { add((if (it == "donna") "Donna" else "Uomo") to { x: Filtri -> x.copy(sesso = null) }) }
    f.eta?.let { add((NOMI_ETA[it] ?: it) to { x: Filtri -> x.copy(eta = null) }) }
    f.capelli?.let { add("Capelli ${NOMI_CAPELLI[it]?.lowercase()}" to { x: Filtri -> x.copy(capelli = null) }) }
    f.abiti?.let { add("Abiti $it" to { x: Filtri -> x.copy(abiti = null) }) }
    f.persone?.let { add((NOMI_PERSONE[it] ?: "") to { x: Filtri -> x.copy(persone = null) }) }
    f.album?.let { add("Cartella: $it" to { x: Filtri -> x.copy(album = null) }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Scelte(titolo: String, opzioni: List<Pair<T, String>>, valore: T?, scegli: (T?) -> Unit) {
    Text(titolo, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, nome) in opzioni) FilterChip(valore == v, { scegli(if (valore == v) null else v) }, label = { Text(nome) })
    }
}

@Composable
fun PannelloFiltri(vm: AppVM, iniziali: Filtri, applica: (Filtri) -> Unit, chiudi: () -> Unit) {
    var f by remember { mutableStateOf(iniziali) }
    val foto by vm.foto.collectAsStateWithLifecycle()
    val album = remember(foto) { foto.groupingBy { it.album }.eachCount().entries.filter { it.key.isNotBlank() }.sortedByDescending { it.value } }
    var tutteCartelle by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Filtri") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Età, sesso e capelli sono stime: valgono sulla stessa persona e solo per volti abbastanza grandi.",
                    style = MaterialTheme.typography.bodySmall)
                Scelte("Persone nella foto", NOMI_PERSONE.toList(), f.persone) { f = f.copy(persone = it) }
                Scelte("Sesso", listOf("donna" to "Donna", "uomo" to "Uomo"), f.sesso) { f = f.copy(sesso = it) }
                Scelte("Età", NOMI_ETA.toList(), f.eta) { f = f.copy(eta = it) }
                Scelte("Capelli", NOMI_CAPELLI.toList(), f.capelli) { f = f.copy(capelli = it) }
                Scelte("Colore abiti", Colori.NOMI.map { it to it }, f.abiti) { f = f.copy(abiti = it) }
                val elenco = if (tutteCartelle) album else album.take(8)
                Scelte("Cartella", elenco.map { it.key to "${it.key} (${it.value})" }, f.album) { f = f.copy(album = it) }
                if (album.size > 8) TextButton({ tutteCartelle = !tutteCartelle }) { Text(if (tutteCartelle) "Meno cartelle" else "Tutte le cartelle (${album.size})") }
            }
        },
        confirmButton = { TextButton({ applica(f); chiudi() }) { Text("Applica") } },
        dismissButton = { TextButton({ applica(Filtri()); chiudi() }) { Text("Azzera") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrigliaFoto(
    lista: List<FotoIdx>, modifier: Modifier = Modifier, selezionate: Set<Long> = emptySet(),
    onLongClick: ((FotoIdx) -> Unit)? = null, onClick: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    LazyVerticalGrid(GridCells.Adaptive(100.dp), modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp)) {
        items(lista.size, key = { lista[it].id }) { i ->
            val f = lista[i]
            val sel = f.id in selezionate
            Box(Modifier.aspectRatio(1f).padding(1.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = { onClick(i) }, onLongClick = onLongClick?.let { cb -> { cb(f) } })) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(f.uri).size(300).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(if (sel) 10.dp else 0.dp)
                )
                if (sel) Icon(Icons.Default.CheckCircle, "Selezionata", Modifier.align(Alignment.TopStart).padding(4.dp),
                    tint = MaterialTheme.colorScheme.primary)
                if (f.isVideo) Text(
                    "▶ " + tempoVideo(f.tempo) + if (f.momenti.size > 1) " ·${f.momenti.size}" else "",
                    color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- COPIA / SPOSTA / ELIMINA

@Composable
fun DialogoAzione(vm: AppVM, tipo: TipoOp, tutte: List<FotoIdx>, chiudi: () -> Unit, fatto: () -> Unit) {
    // copia, sposta ed elimina valgono per le foto; i video si gestiscono dalla Galleria
    val lista = tutte.filter { !it.isVideo }
    if (lista.isEmpty()) {
        AlertDialog(onDismissRequest = chiudi, title = { Text("Video") },
            text = { Text("Copia, sposta ed elimina funzionano sulle foto. Per i video usa la Galleria o l'app File.") },
            confirmButton = { TextButton(chiudi) { Text("OK") } })
        return
    }
    val n = (if (lista.size == 1) "questa foto" else "${lista.size} foto") + if (lista.size < tutte.size) " (i video selezionati restano dove sono)" else ""
    if (tipo == TipoOp.ELIMINA || tipo == TipoOp.CESTINO) {
        val esterne = lista.count { it.cartella != null }
        AlertDialog(
            onDismissRequest = chiudi,
            title = { Text("Eliminare $n?") },
            text = {
                val nota = if (esterne > 0) "\n\n$esterne foto sono su unità esterne: vengono eliminate subito (lì non c'è cestino)." else ""
                Text("Il cestino tiene le foto per 30 giorni e si possono recuperare.$nota\n\nAndroid ti chiederà una conferma.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({ vm.esegui(TipoOp.CESTINO, lista); chiudi(); fatto() }) { Text("Sposta nel cestino") }
                    TextButton({ vm.esegui(TipoOp.ELIMINA, lista); chiudi(); fatto() }) {
                        Text("Elimina definitivamente", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(chiudi) { Text("Annulla") } },
        )
    } else {
        DialogoDestinazione(vm, if (tipo == TipoOp.COPIA) "Copia $n in…" else "Sposta $n in…", chiudi) { d ->
            vm.esegui(tipo, lista, d); chiudi(); fatto()
        }
    }
}

@Composable
fun DialogoDestinazione(vm: AppVM, titolo: String, chiudi: () -> Unit, scegli: (Destinazione) -> Unit) {
    val cartelle by vm.cartelleGalleria.collectAsStateWithLifecycle()
    val esterne by vm.cartelle.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.caricaCartelleGalleria() }
    var nuova by remember { mutableStateOf(false) }
    var nomeNuova by remember { mutableStateOf("") }
    val selettore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            val ctx = vm.getApplication<android.app.Application>()
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            scegli(Destinazione.Albero(u.toString()))
        }
    }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(titolo) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    if (nuova) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(nomeNuova, { nomeNuova = it.replace("/", "") }, Modifier.weight(1f), singleLine = true,
                                label = { Text("Nuova cartella in Pictures") })
                            TextButton({
                                if (nomeNuova.isNotBlank()) scegli(Destinazione.Galleria("Pictures/${nomeNuova.trim()}/"))
                            }) { Text("OK") }
                        }
                    } else TextButton({ nuova = true }) { Text("+ Nuova cartella") }
                    TextButton({ selettore.launch(null) }) { Text("Altra posizione / chiavetta USB…") }
                    HorizontalDivider()
                }
                items(esterne.toList()) { t ->
                    Text(nomeCartella(t), Modifier.fillMaxWidth().clickable { scegli(Destinazione.Albero(t)) }.padding(10.dp))
                }
                items(cartelle, key = { it.volume + it.percorso }) { c ->
                    Column(Modifier.fillMaxWidth().clickable { scegli(Destinazione.Galleria(c.percorso)) }.padding(10.dp)) {
                        Text(c.percorso.trimEnd('/').substringAfterLast('/'), fontWeight = FontWeight.Bold)
                        Text(
                            (if (c.volume == "external_primary") "" else "Scheda SD · ") + c.percorso.trimEnd('/') + " · ${c.n} foto",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

// ---------------------------------------------------------------- VISORE

private val formatoData = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)

@Composable
fun Visore(vm: AppVM, lista: List<FotoIdx>, iniziale: Int, apriPersona: (Long) -> Unit = {}, chiudi: () -> Unit) {
    val ctx = LocalContext.current
    val persone by vm.persone.collectAsStateWithLifecycle()
    val stato = rememberPagerState(initialPage = iniziale.coerceIn(0, max(0, lista.size - 1))) { lista.size }
    val tutte by vm.foto.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf(false) }
    var modifica by remember { mutableStateOf(false) }
    var azione by remember { mutableStateOf<TipoOp?>(null) }
    var dettagli by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var volti by remember { mutableStateOf<List<VoltoInfo>>(emptyList()) }
    var voltoScelto by remember { mutableStateOf<VoltoInfo?>(null) }
    var aggiungiPersona by remember { mutableStateOf(false) }
    val zoom = remember { HashMap<Long, ZoomStato>() }
    var lente by remember { mutableStateOf(false) }
    var regola by remember { mutableStateOf(false) }
    var reg by remember { mutableStateOf(Regolazioni()) }
    val basi = remember { HashMap<Long, ImageBitmap>() }
    var messaggio by remember { mutableStateOf<String?>(null) }
    var lettore by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stato.settledPage) {
        val id = lista.getOrNull(stato.settledPage)?.id
        zoom.forEach { (k, z) -> if (k != id) z.azzera() }
        reg = Regolazioni()
    }
    val idCorrente = lista.getOrNull(stato.currentPage)?.id
    val zCorrente = idCorrente?.let { zoom.getOrPut(it) { ZoomStato() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            stato, Modifier.fillMaxSize(), key = { lista[it].id },
            userScrollEnabled = zCorrente?.ingrandita != true && !lente,
        ) { p ->
            val fp = lista[p]
            FotoZoom(
                fp.uri, zoom.getOrPut(fp.id) { ZoomStato() },
                filtro = if (fp.id == idCorrente) reg.filtro() else null,
                lente = lente && fp.id == idCorrente,
                caricata = { basi[fp.id] = it.base; if (basi.size > 6) basi.keys.filter { k -> k != fp.id }.take(basi.size - 6).forEach(basi::remove) },
            ) { if (!regola) info = !info else regola = false }
        }
        val f0 = lista.getOrNull(stato.currentPage) ?: return@Box
        val f = tutte.firstOrNull { it.id == f0.id } ?: f0
        LaunchedEffect(f.id, f.album, info) {
            if (info) dettagli = withContext(Dispatchers.IO) { runCatching { Operazioni.dettagli(ctx, f) }.getOrDefault(emptyList()) }
        }
        LaunchedEffect(f.id, f.persone, info) { volti = if (info) vm.voltiFoto(f.id) else emptyList() }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(chiudi) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Text("${stato.currentPage + 1} / ${lista.size}", color = Color.White, modifier = Modifier.weight(1f))
            if (zCorrente?.ingrandita == true) TextButton({ zCorrente.azzera() }) { Text("Adatta", color = Color.White) }
            IconButton({ lente = !lente }) {
                Icon(Icons.Default.Search, "Lente", tint = if (lente) Color(0xFFFFD54F) else Color.White)
            }
            IconButton({ regola = !regola; if (regola) info = false }) {
                Icon(Icons.Default.Edit, "Luce e colore", tint = if (regola || !reg.neutra) Color(0xFFFFD54F) else Color.White)
            }
            IconButton({ info = !info; if (info) regola = false }) { Icon(Icons.Default.Info, "Dettagli", tint = Color.White) }
            if (f.isVideo) IconButton({ lettore = f.tempo }) { Icon(Icons.Default.PlayArrow, "Riproduci da qui", tint = Color.White) }
            IconButton({ condividi(ctx, listOf(f)) }) { Icon(Icons.Default.Share, "Condividi", tint = Color.White) }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Altro", tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    if (f.isVideo) DropdownMenuItem({ Text("Apri il video in un'altra app") }, {
                        menu = false
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.video, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }) else {
                        DropdownMenuItem({ Text("Copia in…") }, { menu = false; azione = TipoOp.COPIA })
                        DropdownMenuItem({ Text("Sposta in…") }, { menu = false; azione = TipoOp.SPOSTA })
                        DropdownMenuItem({ Text("Elimina…") }, { menu = false; azione = TipoOp.ELIMINA })
                    }
                    DropdownMenuItem({ Text("Modifica etichette") }, { menu = false; modifica = true })
                    DropdownMenuItem({ Text("Aggiungi a una persona…") }, { menu = false; aggiungiPersona = true })
                    DropdownMenuItem({ Text("Altre foto di questa cartella") }, {
                        menu = false; vm.query.value = ""; vm.filtri.value = Filtri(album = f.album); chiudi()
                    })
                }
            }
        }
        if (lente) Text(
            "Lente attiva: trascina il dito sulla foto. Pizzica per ingrandire, tocca 🔍 per spegnerla.",
            color = Color.White, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(8.dp)
        )
        if (regola) Surface(
            color = Color(0xE6000000), contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            PannelloRegolazioni(
                reg, { reg = it },
                auto = { reg = if (reg.auto != null) reg.copy(auto = null) else reg.copy(auto = basi[f.id]?.let { Regolazioni.livelliAuto(it) }) },
                salva = {
                    val r0 = reg
                    scope.launch {
                        messaggio = "Salvataggio in corso…"
                        messaggio = runCatching { "Copia salvata in " + salvaCopiaModificata(ctx, f, r0) + " (sarà indicizzata al prossimo aggiornamento)" }
                            .getOrElse { "Errore: ${it.message}" }
                    }
                },
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        }
        messaggio?.let { m ->
            LaunchedEffect(m) { if (!m.endsWith("…")) { kotlinx.coroutines.delay(4000); messaggio = null } }
            Text(m, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)
                .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(12.dp))
        }
        if (info) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000))
                    .navigationBarsPadding().padding(16.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState())
            ) {
                Text(formatoData.format(Date(f.data)), color = Color.White, fontWeight = FontWeight.Bold)
                if (f.isVideo) {
                    Text("Video «${f.nome}» · momento ${tempoVideo(f.tempo)} di ${tempoVideo(f.durata)}", color = Color.White)
                    Button({ lettore = f.tempo }, Modifier.padding(vertical = 4.dp)) { Text("▶ Riproduci da ${tempoVideo(f.tempo)}") }
                    val trovati = f0.momenti.toSet()
                    val momenti = remember(tutte, f.video) { tutte.filter { it.video == f.video }.sortedBy { it.tempo } }
                    Text("Momenti del video (tocca per riprodurre da lì" + (if (trovati.size > 1) "; in giallo quelli trovati" else "") + "):",
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        items(momenti, key = { it.id }) { m ->
                            val evid = m.id in trovati || m.id == f.id
                            Column(Modifier.width(76.dp).clickable { lettore = m.tempo }, horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(ImageRequest.Builder(ctx).data(m.uri).size(200).build(), null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(4.dp))
                                        .then(if (evid) Modifier.background(Color(0xFFFFB300)).padding(2.dp) else Modifier))
                                Text(tempoVideo(m.tempo), color = if (evid) Color(0xFFFFD54F) else Color.LightGray, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (dettagli.isEmpty()) Text("${f.album} · ${f.nome}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                for ((k, v) in dettagli) Text("$k: $v", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                val nomi = persone.filter { it.id in f.persone }.map { it.nome ?: "persona senza nome" }
                if (f.volti > 0) Text("Volti: ${f.volti}" + if (nomi.isNotEmpty()) " — ${nomi.joinToString(", ")}" else "", color = Color.White)
                for (a in f.attr) Text("• " + descriviVolto(a, persone), color = Color.White, style = MaterialTheme.typography.bodySmall)
                if (volti.isNotEmpty()) {
                    Text("Tocca un volto per vedere o cambiare il gruppo (✋ = aggiunta a mano):", color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        items(volti, key = { it.id }) { v ->
                            val pers = persone.firstOrNull { it.id == v.persona }
                            Column(Modifier.width(72.dp).clickable { voltoScelto = v }, horizontalAlignment = Alignment.CenterHorizontally) {
                                VoltoMini(v.id, f.uri, v.l, v.t, v.r, v.b, 56.dp)
                                Text(
                                    (if (v.manuale) "✋ " else "") + (pers?.nome ?: if (pers != null) "senza nome" else "nessun gruppo"),
                                    color = if (pers?.nome != null) Color.White else Color.LightGray, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
                if (f.etichette.isNotEmpty())
                    Text("Contiene: " + f.etichette.entries.sortedByDescending { it.value }.joinToString(", ") { Dizionario.italiano(it.key) },
                        color = Color.White)
                if (f.manuali.isNotEmpty()) Text("Tue etichette: " + f.manuali.joinToString(", "), color = Color(0xFFFFD54F))
                if (f.colori.isNotEmpty())
                    Text("Colori: " + f.colori.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} ${it.value}%" },
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                if (f.testoOriginale.isNotBlank())
                    Text("Testo letto: " + f.testoOriginale.take(400).replace('\n', ' '), color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton({
                        val i = if (f.isVideo) Intent(Intent.ACTION_VIEW).setDataAndType(f.video, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { ctx.startActivity(i) }
                    }) { Text(if (f.isVideo) "Apri video" else if (f.cartella != null) "Apri con…" else "Apri in Galleria") }
                    TextButton({ modifica = true }) { Text("Etichette") }
                    dettagli.firstOrNull { it.first == "Posizione" }?.let { (_, pos) ->
                        TextButton({
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + pos.replace(" ", "")))) }
                        }) { Text("Mappa") }
                    }
                }
            }
        }
    }
    lettore?.let { ms ->
        lista.getOrNull(stato.currentPage)?.video?.let { v -> LettoreVideo(v, ms) { lettore = null } }
    }
    if (aggiungiPersona) lista.getOrNull(stato.currentPage)?.let { x ->
        DialogoAggiungiPersona(vm, listOf(x), { aggiungiPersona = false }) {}
    }
    voltoScelto?.let { v ->
        val f = lista.getOrNull(stato.currentPage)
        if (f != null) DialogoVolto(vm, v, f.uri, persone, { voltoScelto = null }) { id -> voltoScelto = null; apriPersona(id) }
    }
    azione?.let { t ->
        val f = lista.getOrNull(stato.currentPage)?.let { x -> tutte.firstOrNull { it.id == x.id } ?: x }
        if (f != null) DialogoAzione(vm, t, listOf(f), chiudi = { azione = null }) { if (t != TipoOp.COPIA) chiudi() }
    }
    if (modifica) {
        val f = lista.getOrNull(stato.currentPage)?.let { x -> tutte.firstOrNull { it.id == x.id } ?: x }
        if (f != null) DialogoEtichette(f, { modifica = false }) { man, rim -> vm.salvaEtichette(f.id, man, rim) }
    }
}

/** Lettore video interno: parte dal punto indicato */
@Composable
fun LettoreVideo(uri: Uri, ms: Long, chiudi: () -> Unit) {
    androidx.compose.ui.window.Dialog(chiudi, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c ->
                    android.widget.VideoView(c).apply {
                        val mc = android.widget.MediaController(c)
                        mc.setAnchorView(this); setMediaController(mc)
                        setOnPreparedListener { mp -> mp.seekTo(ms, android.media.MediaPlayer.SEEK_CLOSEST); mp.start(); mc.show(3000) }
                        setOnErrorListener { _, _, _ -> android.widget.Toast.makeText(c, "Impossibile riprodurre questo video qui: usa «Apri video»", android.widget.Toast.LENGTH_LONG).show(); true }
                        setVideoURI(uri)
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            )
            IconButton(chiudi, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)) {
                Icon(Icons.Default.Close, "Chiudi", tint = Color.White)
            }
        }
    }
}

@Composable
fun DialogoVolto(vm: AppVM, v: VoltoInfo, uri: Uri, persone: List<Persona>, chiudi: () -> Unit, apriPersona: (Long) -> Unit) {
    val attuale = persone.firstOrNull { it.id == v.persona }
    var scegli by remember { mutableStateOf(false) }
    var nuovoNome by remember { mutableStateOf<String?>(null) }
    var simili by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    LaunchedEffect(v.id) { simili = vm.somiglianzeVolto(v.id) }
    when {
        nuovoNome != null -> AlertDialog(
            onDismissRequest = chiudi,
            title = { Text("Nuova persona") },
            text = { OutlinedTextField(nuovoNome!!, { nuovoNome = it }, singleLine = true, placeholder = { Text("Nome") }) },
            confirmButton = {
                TextButton({ if (nuovoNome!!.isNotBlank()) { vm.nuovaPersonaDaVolto(v.id, nuovoNome!!); chiudi() } }) { Text("Crea") }
            },
            dismissButton = { TextButton(chiudi) { Text("Annulla") } },
        )
        scegli -> DialogoScegliPersona("Sposta questo volto in…", persone, v.persona, simili, chiudi,
            extra = { TextButton({ scegli = false; nuovoNome = "" }) { Text("+ Nuova persona") } }) { o ->
            vm.assegnaVolto(v.id, o.id, o.nome); chiudi()
        }
        else -> AlertDialog(
            onDismissRequest = chiudi,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoltoMini(v.id, uri, v.l, v.t, v.r, v.b, 56.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(attuale?.nome ?: if (attuale != null) "Gruppo senza nome" else "Nessun gruppo")
                        attuale?.let { Text("${it.foto} foto nel gruppo", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            text = {
                if (v.manuale) Column {
                    Text("Foto collegata a mano a questa persona.", style = MaterialTheme.typography.bodySmall)
                    if (attuale != null) TextButton({ apriPersona(attuale.id) }) { Text("Apri il gruppo") }
                    TextButton({ vm.rimuoviAssociazione(v.id); chiudi() }) { Text("Togli da questa persona", color = MaterialTheme.colorScheme.error) }
                } else Column {
                    if (!v.conVettore) Text("Volto troppo piccolo per il riconoscimento automatico, ma puoi assegnarlo a mano.",
                        style = MaterialTheme.typography.bodySmall)
                    if (attuale != null) TextButton({ apriPersona(attuale.id) }) { Text("Apri il gruppo") }
                    TextButton({ scegli = true }) { Text(if (attuale != null) "Sposta in un'altra persona…" else "Assegna a una persona…") }
                    TextButton({ nuovoNome = "" }) { Text("Crea una nuova persona") }
                    if (attuale != null) TextButton({ vm.assegnaVolto(v.id, Db.ESCLUSO, null); chiudi() }) {
                        Text("Togli dal gruppo", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(chiudi) { Text("Chiudi") } },
        )
    }
}

/** Collega a mano una o più foto a una persona con nome, anche se il volto non si vede */
@Composable
fun DialogoAggiungiPersona(vm: AppVM, lista: List<FotoIdx>, chiudi: () -> Unit, fatto: () -> Unit) {
    val persone by vm.persone.collectAsStateWithLifecycle()
    var nuovoNome by remember { mutableStateOf<String?>(null) }
    val n = if (lista.size == 1) "questa foto" else "${lista.size} foto"
    if (nuovoNome != null) {
        AlertDialog(
            onDismissRequest = chiudi,
            title = { Text("Nuova persona") },
            text = { OutlinedTextField(nuovoNome!!, { nuovoNome = it }, singleLine = true, placeholder = { Text("Nome") }) },
            confirmButton = {
                TextButton({ if (nuovoNome!!.isNotBlank()) { vm.nuovaPersonaConFoto(lista, nuovoNome!!); chiudi(); fatto() } }) { Text("Crea") }
            },
            dismissButton = { TextButton(chiudi) { Text("Annulla") } },
        )
    } else {
        DialogoScegliPersona("Aggiungi $n a…", persone.filter { it.nome != null }, null, emptyMap(), chiudi,
            extra = {
                Text("Anche se il volto non si vede: la foto comparirà nel gruppo e nelle ricerche per nome.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                TextButton({ nuovoNome = "" }) { Text("+ Nuova persona") }
            }) { p -> vm.associa(lista, p); chiudi(); fatto() }
    }
}

fun descriviVolto(a: VoltoAttr, persone: List<Persona>): String {
    val nome = persone.firstOrNull { it.id == a.persona }?.nome
    val chi = when {
        a.eta < 12 -> if (a.maschio > 0.6f) "bambino" else if (a.maschio < 0.4f) "bambina" else "bambino/a"
        a.eta < 30 -> if (a.maschio > 0.6f) "ragazzo" else if (a.maschio < 0.4f) "ragazza" else "giovane"
        a.eta >= 60 -> if (a.maschio > 0.6f) "uomo anziano" else if (a.maschio < 0.4f) "donna anziana" else "persona anziana"
        else -> if (a.maschio > 0.6f) "uomo" else if (a.maschio < 0.4f) "donna" else "persona"
    }
    val parti = mutableListOf((nome?.let { "$it, " } ?: "") + "$chi, circa ${a.eta.toInt()} anni (stima)")
    a.capelli?.let { parti += "capelli " + mapOf("nero" to "neri", "castano" to "castani", "biondo" to "biondi", "rosso" to "rossi", "grigio" to "grigi/bianchi")[it] }
    a.abiti?.let { parti += "abiti $it" }
    return parti.joinToString(", ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogoEtichette(f: FotoIdx, chiudi: () -> Unit, salva: (List<String>, Set<String>) -> Unit) {
    var manuali by remember(f.id) { mutableStateOf(f.manuali) }
    var rimosse by remember(f.id) { mutableStateOf(f.rimosse) }
    val automatiche = remember(f.id) { (f.etichette.keys + f.rimosse).sortedBy { Dizionario.italiano(it) } }
    var nuova by remember { mutableStateOf("") }
    fun aggiungi() { val t = nuova.trim(); if (t.isNotEmpty() && t !in manuali) manuali = manuali + t; nuova = "" }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Etichette della foto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Aggiungi le tue", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(nuova, { nuova = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("es. Sardegna 2023") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { aggiungi() }))
                    TextButton(::aggiungi) { Text("Aggiungi") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in manuali) InputChip(true, { manuali = manuali - m }, label = { Text(m) },
                        trailingIcon = { Icon(Icons.Default.Close, "Togli", Modifier.size(16.dp)) })
                }
                Spacer(Modifier.height(12.dp))
                Text("Trovate in automatico", fontWeight = FontWeight.Bold)
                Text("Tocca per togliere o rimettere.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in automatiche) {
                        val attiva = e !in rimosse
                        FilterChip(attiva, { rimosse = if (attiva) rimosse + e else rimosse - e },
                            label = { Text(Dizionario.italiano(e)) },
                            trailingIcon = { if (attiva) Icon(Icons.Default.Close, "Togli", Modifier.size(16.dp)) })
                    }
                }
                if (automatiche.isEmpty()) Text("Nessuna.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ aggiungi(); salva(manuali, rimosse); chiudi() }) { Text("Salva") } },
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

// ---------------------------------------------------------------- PERSONE

private object CacheVolti { val lru = LruCache<Long, ImageBitmap>(300) }

private fun caricaVolto(ctx: Context, uri: Uri, pl: Float, pt: Float, pr: Float, pb: Float): ImageBitmap? {
    val bw = max(pr - pl, 0.02f)
    val lato = (240f / bw).toInt().coerceIn(240, 2048)
    val bmp = caricaBitmap(ctx, uri, lato)
    val w = bmp.width; val h = bmp.height
    val mx = (pr - pl) * 0.2f; val my = (pb - pt) * 0.2f
    val l = (max(0f, pl - mx) * w).toInt(); val t = (max(0f, pt - my) * h).toInt()
    val r = (min(1f, pr + mx) * w).toInt(); val b = (min(1f, pb + my) * h).toInt()
    if (r - l < 2 || b - t < 2) return null
    val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    val out = Bitmap.createScaledBitmap(crop, 200, 200 * (b - t) / (r - l), true)
    if (crop !== bmp) bmp.recycle()
    return out.asImageBitmap()
}

/** Miniatura rotonda di un volto (voltoId è la chiave della cache) */
@Composable
fun VoltoMini(voltoId: Long, uri: Uri, l: Float, t: Float, r: Float, b: Float, dim: Dp) {
    val ctx = LocalContext.current
    var img by remember(voltoId) { mutableStateOf(CacheVolti.lru.get(voltoId)) }
    LaunchedEffect(voltoId) {
        if (img == null && voltoId > 0) {
            img = withContext(Dispatchers.IO) { runCatching { caricaVolto(ctx, uri, l, t, r, b) }.getOrNull() }
            img?.let { CacheVolti.lru.put(voltoId, it) }
        }
    }
    Box(Modifier.size(dim).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, null)
    }
}

@Composable
fun VoltoMini(p: Persona, dim: Dp) = VoltoMini(p.coverVolto, p.coverUri, p.l, p.t, p.r, p.b, dim)

/** Elenco persone con ricerca per nome e suggerimenti per somiglianza */
@Composable
fun DialogoScegliPersona(
    titolo: String, persone: List<Persona>, escludi: Long?, simili: Map<Long, Float>,
    chiudi: () -> Unit, extra: (@Composable () -> Unit)? = null, scegli: (Persona) -> Unit,
) {
    var cerca by remember { mutableStateOf("") }
    val q = Testo.normalizza(cerca)
    val altre = persone.filter { it.id != escludi && (it.nome != null || it.foto >= 2) }
    val trovate = if (q.isEmpty()) emptyList() else altre.filter { o -> o.nome?.let { Testo.normalizza(it).contains(q) } == true }
    val suggerite = altre.filter { (simili[it.id] ?: 0f) >= 0.35f }.sortedByDescending { simili[it.id] }.take(6)
    val nominate = altre.filter { it.nome != null }.sortedBy { Testo.normalizza(it.nome!!) }
    val senzaNome = altre.filter { it.nome == null }.sortedByDescending { simili[it.id] ?: 0f }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(titolo) },
        text = {
            Column {
                OutlinedTextField(cerca, { cerca = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Cerca un nome") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (cerca.isNotEmpty()) IconButton({ cerca = "" }) { Icon(Icons.Default.Close, "Cancella") } })
                extra?.invoke()
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    fun sezione(t: String, lista: List<Persona>, chiave: String) {
                        if (lista.isEmpty()) return
                        item(key = "t$chiave") {
                            Text(t, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        items(lista, key = { "$chiave${it.id}" }) { o -> RigaPersona(o, simili[o.id]) { scegli(o) } }
                    }
                    if (q.isNotEmpty()) {
                        sezione("Risultati", trovate, "r")
                        if (trovate.isEmpty()) item { Text("Nessun nome trovato.", Modifier.padding(8.dp)) }
                    } else {
                        sezione("Più somiglianti", suggerite, "s")
                        sezione("Con nome (A-Z)", nominate, "n")
                        sezione("Senza nome", senzaNome, "x")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

@Composable
fun SchermataPersone(vm: AppVM, apri: (Long) -> Unit) {
    val persone by vm.persone.collectAsStateWithLifecycle()
    val foto by vm.foto.collectAsStateWithLifecycle()
    val av by vm.avanzamento.collectAsStateWithLifecycle()
    var tutte by remember { mutableStateOf(false) }
    var cerca by remember { mutableStateOf("") }
    var conNome by remember { mutableStateOf<Boolean?>(null) }   // null = tutti
    var filtri by remember { mutableStateOf(Filtri()) }
    var pannello by remember { mutableStateOf(false) }
    val profili = remember(foto) { profiliPersone(foto) }
    val q = Testo.normalizza(cerca)
    val visibili = persone.filter { tutte || (!it.nascosta && (it.nome != null || it.foto >= 2)) }
        .filter { q.isEmpty() || it.nome?.let { n -> Testo.normalizza(n).contains(q) } == true }
        .filter { conNome == null || (it.nome != null) == conNome }
        .filter { p -> filtri.attivi == 0 || profili[p.id]?.let { filtri.okVolto(it) } == true }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Persone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
        Text(
            "Volti raggruppati automaticamente. Tocca un gruppo per dargli un nome: poi potrai cercarlo nella scheda Cerca.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp)
        )
        OutlinedTextField(cerca, { cerca = it }, Modifier.fillMaxWidth().padding(12.dp, 4.dp), singleLine = true,
            placeholder = { Text("Cerca un nome") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (cerca.isNotEmpty()) IconButton({ cerca = "" }) { Icon(Icons.Default.Close, "Cancella") } })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(conNome == false, { conNome = if (conNome == false) null else false }, label = { Text("Senza nome") }) }
            item { FilterChip(conNome == true, { conNome = if (conNome == true) null else true }, label = { Text("Con nome") }) }
            item {
                FilterChip(filtri.attivi > 0, { pannello = true },
                    label = { Text(if (filtri.attivi > 0) descriviFiltri(filtri).joinToString(", ") { it.first } else "Sesso, età, capelli…") },
                    trailingIcon = { if (filtri.attivi > 0) Icon(Icons.Default.Close, "Togli", Modifier.size(16.dp).clickable { filtri = Filtri() }) })
            }
            item { FilterChip(tutte, { tutte = !tutte }, label = { Text("Anche volti singoli e nascosti") }) }
        }
        Text("${visibili.size} gruppi", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 4.dp))
        val ricalcolo by vm.ricalcolo.collectAsStateWithLifecycle()
        var riorganizza by remember { mutableStateOf(false) }
        when {
            av.attivo && av.fase == "volti" || ricalcolo -> Row(Modifier.padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp)); Text("  Sto raggruppando i volti…")
            }
            av.attivo && av.fase == "volti_hd" && av.totale > 0 -> Column(Modifier.padding(16.dp, 4.dp)) {
                Text("Rianalisi volti in alta risoluzione: ${av.fatte} di ${av.totale}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            else -> TextButton({ riorganizza = true }, Modifier.padding(horizontal = 8.dp), enabled = !av.attivo) {
                Text("Riorganizza volti senza nome…")
            }
        }
        if (riorganizza) DialogoRiorganizza(vm) { riorganizza = false }
        if (visibili.isEmpty()) {
            Text(if (persone.isEmpty()) "Nessun gruppo ancora: i volti compaiono qui dopo l'indicizzazione." else "Nessun gruppo con questi filtri.",
                Modifier.padding(16.dp))
        }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(visibili, key = { it.id }) { p ->
                Column(
                    Modifier.padding(6.dp).clickable { apri(p.id) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    VoltoMini(p, 84.dp)
                    Text(p.nome ?: "Senza nome", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (p.nome != null) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${p.foto} foto" + if (p.nascosta) " · nascosta" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (pannello) DialogoFiltriPersona(filtri, { filtri = it }) { pannello = false }
}

/** Profilo medio di ogni gruppo (età mediana, sesso medio, capelli e abiti più frequenti) dai volti affidabili */
fun profiliPersone(foto: List<FotoIdx>): Map<Long, VoltoAttr> {
    val per = HashMap<Long, MutableList<VoltoAttr>>()
    for (f in foto) for (a in f.attr) if (a.persona > 0) per.getOrPut(a.persona) { mutableListOf() }.add(a)
    return per.mapValues { (id, l) ->
        val buoni = l.filter { it.affidabile }.ifEmpty { l }
        val eta = buoni.map { it.eta }.sorted().let { it[it.size / 2] }
        val maschio = buoni.map { it.maschio }.average().toFloat()
        val capelli = l.mapNotNull { it.capelli }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        VoltoAttr(id, eta, maschio, capelli, null, buoni.maxOf { it.area })
    }
}

@Composable
fun DialogoRiorganizza(vm: AppVM, chiudi: () -> Unit) {
    val ctx = LocalContext.current
    var soglia by remember { mutableFloatStateOf(Impostazioni.soglia(ctx)) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Riorganizza volti senza nome") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Le persone con nome e i volti che hai tolto a mano non vengono toccati.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Text("Severità del riconoscimento", fontWeight = FontWeight.Bold)
                Slider(soglia, { soglia = it }, valueRange = 0.35f..0.75f)
                Row {
                    Text("Permissivo: unisce di più", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("Severo: separa di più", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text("Veloce", fontWeight = FontWeight.Bold)
                Text("Riconfronta i volti senza nome con le persone che hai già nominato e ricompone i gruppi. Qualche secondo.",
                    style = MaterialTheme.typography.bodySmall)
                Button({ Impostazioni.setSoglia(ctx, soglia); vm.ricalcolaGruppi(); chiudi() }) { Text("Riorganizza ora") }
                Spacer(Modifier.height(12.dp))
                Text("Approfondita", fontWeight = FontWeight.Bold)
                Text("Riapre in alta risoluzione le foto con volti senza nome: trova anche volti piccoli o di sfondo e li riconosce meglio. " +
                    "Può richiedere parecchio tempo; continua in background con una notifica.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ Impostazioni.setSoglia(ctx, soglia); Indicizzatore.avvia(ctx, rianalisiVolti = true); chiudi() }) {
                    Text("Rianalizza in alta risoluzione")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(chiudi) { Text("Chiudi") } },
    )
}

@Composable
fun DialogoFiltriPersona(iniziali: Filtri, applica: (Filtri) -> Unit, chiudi: () -> Unit) {
    var f by remember { mutableStateOf(iniziali) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Filtra i gruppi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Valori stimati su tutti i volti del gruppo.", style = MaterialTheme.typography.bodySmall)
                Scelte("Sesso", listOf("donna" to "Donna", "uomo" to "Uomo"), f.sesso) { f = f.copy(sesso = it) }
                Scelte("Età", NOMI_ETA.toList(), f.eta) { f = f.copy(eta = it) }
                Scelte("Capelli", NOMI_CAPELLI.toList(), f.capelli) { f = f.copy(capelli = it) }
            }
        },
        confirmButton = { TextButton({ applica(f); chiudi() }) { Text("Applica") } },
        dismissButton = { TextButton({ applica(Filtri()); chiudi() }) { Text("Azzera") } },
    )
}

@Composable
fun RigaPersona(o: Persona, somiglianza: Float?, scegli: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = scegli).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        VoltoMini(o, 44.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(o.nome ?: "Senza nome", fontWeight = if (o.nome != null) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${o.foto} foto" + (somiglianza?.takeIf { it > 0f }?.let { " · somiglianza ${(it * 100).toInt()}%" } ?: ""),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SchermataPersona(vm: AppVM, id: Long, apriFoto: (List<FotoIdx>, Int) -> Unit, indietro: () -> Unit) {
    val persone by vm.persone.collectAsStateWithLifecycle()
    val foto by vm.foto.collectAsStateWithLifecycle()
    val p = persone.firstOrNull { it.id == id }
    if (p == null) { LaunchedEffect(Unit) { indietro() }; return }
    val sue = remember(foto, id) { vm.comprimiVideo(foto.filter { id in it.persone }) }
    var dialogoNome by remember { mutableStateOf(false) }
    var dialogoUnisci by remember { mutableStateOf(false) }
    var unisciIn by remember { mutableStateOf<Persona?>(null) }
    var sel by remember(id) { mutableStateOf(setOf<Long>()) }
    fun tutteSel() = vm.espandi(sel, sue)
    var confermaTogli by remember { mutableStateOf(false) }
    var spostaIn by remember { mutableStateOf(false) }
    if (sel.isNotEmpty()) androidx.activity.compose.BackHandler { sel = emptySet() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(indietro) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            VoltoMini(p, 48.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(p.nome ?: "Senza nome", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${sue.size} foto", style = MaterialTheme.typography.bodySmall)
            }
            IconButton({ dialogoNome = true }) { Icon(Icons.Default.Edit, "Rinomina") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ dialogoNome = true }) { Text(if (p.nome == null) "Dai un nome" else "Rinomina") }
            OutlinedButton({ dialogoUnisci = true }) { Text("Unisci a…") }
            OutlinedButton({ vm.nascondi(p.id, !p.nascosta) }) { Text(if (p.nascosta) "Mostra" else "Nascondi") }
        }
        var simili by remember(p.id, persone.size) { mutableStateOf<Map<Long, Float>>(emptyMap()) }
        LaunchedEffect(p.id, persone.size) { simili = vm.somiglianze(p.id) }
        var daUnire by remember { mutableStateOf<Persona?>(null) }
        val candidati = persone.filter { it.id != p.id && it.nome == null && (simili[it.id] ?: 0f) >= 0.45f }
            .sortedByDescending { simili[it.id] }.take(12)
        if (candidati.isNotEmpty()) {
            Text("Potrebbe essere anche… (tocca per unire)", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(candidati, key = { it.id }) { o ->
                    Column(Modifier.width(72.dp).clickable { daUnire = o }, horizontalAlignment = Alignment.CenterHorizontally) {
                        VoltoMini(o, 56.dp)
                        Text("${((simili[o.id] ?: 0f) * 100).toInt()}% · ${o.foto}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        daUnire?.let { o ->
            DialogoUnioneSelettiva(vm, foto, sorgente = o, destinazione = p,
                titolo = "È ${p.nome ?: "questa persona"}?",
                nota = "Gruppo senza nome, somiglianza ${((simili[o.id] ?: 0f) * 100).toInt()}%.") { daUnire = null }
        }
        if (sel.isEmpty()) {
            Text("Tieni premuta una foto sbagliata per selezionarla, poi toglila o spostala nella persona giusta.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 6.dp))
        } else {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ sel = emptySet() }) { Icon(Icons.Default.Close, "Annulla selezione") }
                Text("${sel.size}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton({ sel = sue.map { it.id }.toSet() }) { Text("Tutte") }
                TextButton({ confermaTogli = true }) { Text("Togli") }
                Button({ spostaIn = true }) { Text("Sposta in…") }
            }
        }
        GrigliaFoto(sue, selezionate = sel, onLongClick = { f -> sel = if (f.id in sel) sel - f.id else sel + f.id }) { i ->
            if (sel.isNotEmpty()) { val fid = sue[i].id; sel = if (fid in sel) sel - fid else sel + fid } else apriFoto(sue, i)
        }
    }

    if (dialogoNome) {
        var testo by remember { mutableStateOf(p.nome ?: "") }
        val q = Testo.normalizza(testo)
        val uguali = if (q.length < 2) emptyList() else
            persone.filter { it.id != p.id && it.nome != null && Testo.normalizza(it.nome).contains(q) }.take(5)
        AlertDialog(
            onDismissRequest = { dialogoNome = false },
            title = { Text("Nome della persona") },
            text = {
                Column {
                    OutlinedTextField(testo, { testo = it }, singleLine = true, placeholder = { Text("es. Mario") })
                    if (uguali.isNotEmpty()) {
                        Text("Esiste già? Tocca per unire questo gruppo:", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                        for (o in uguali) RigaPersona(o, null) { unisciIn = o; dialogoNome = false }
                    }
                }
            },
            confirmButton = { TextButton({ vm.rinomina(p.id, testo); dialogoNome = false }) { Text("Salva come nuovo nome") } },
            dismissButton = { TextButton({ dialogoNome = false }) { Text("Annulla") } },
        )
    }
    if (dialogoUnisci) {
        var simili by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
        LaunchedEffect(p.id) { simili = vm.somiglianze(p.id) }
        DialogoScegliPersona("È la stessa persona di…", persone, p.id, simili, { dialogoUnisci = false }) { o ->
            unisciIn = o; dialogoUnisci = false
        }
    }
    unisciIn?.let { o ->
        DialogoUnioneSelettiva(vm, foto, sorgente = p, destinazione = o, titolo = "Unisci a «${o.nome ?: "gruppo senza nome"}»") { unisciIn = null }
    }
    if (confermaTogli) {
        val n = if (sel.size == 1) "questa foto" else "${sel.size} foto"
        AlertDialog(
            onDismissRequest = { confermaTogli = false },
            title = { Text("Non è ${p.nome ?: "questa persona"}?") },
            text = { Text("Tolgo $n dal gruppo. Se sai chi è, usa invece «Sposta in…».") },
            confirmButton = { TextButton({ vm.escludiFoto(tutteSel(), p.id); sel = emptySet(); confermaTogli = false }) { Text("Togli") } },
            dismissButton = { TextButton({ confermaTogli = false }) { Text("Annulla") } },
        )
    }
    if (spostaIn) {
        var simili by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
        var nuovoNome by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(sel) { simili = vm.somiglianzeFoto(tutteSel(), p.id) }
        val n = if (sel.size == 1) "questa foto" else "${sel.size} foto"
        if (nuovoNome != null) AlertDialog(
            onDismissRequest = { spostaIn = false },
            title = { Text("Nuova persona") },
            text = { OutlinedTextField(nuovoNome!!, { nuovoNome = it }, singleLine = true, placeholder = { Text("Nome") }) },
            confirmButton = {
                TextButton({
                    if (nuovoNome!!.isNotBlank()) { vm.nuovaPersonaDaFoto(tutteSel(), p.id, nuovoNome!!); sel = emptySet(); spostaIn = false }
                }) { Text("Crea e sposta") }
            },
            dismissButton = { TextButton({ spostaIn = false }) { Text("Annulla") } },
        ) else DialogoScegliPersona("Sposta $n in…", persone, p.id, simili, { spostaIn = false },
            extra = { TextButton({ nuovoNome = "" }) { Text("+ Nuova persona") } }) { o ->
            vm.spostaFoto(tutteSel(), p.id, o); sel = emptySet(); spostaIn = false
        }
    }
}

/**
 * Unione con scelta delle foto: parte tutto selezionato, tocchi per togliere quelle che non c'entrano.
 * Se le lasci tutte unisce i gruppi; altrimenti sposta solo le scelte e le altre restano dove sono.
 */
@Composable
fun DialogoUnioneSelettiva(
    vm: AppVM, foto: List<FotoIdx>, sorgente: Persona, destinazione: Persona, titolo: String, nota: String? = null, chiudi: () -> Unit,
) {
    val sue = remember(foto, sorgente.id) { vm.comprimiVideo(foto.filter { sorgente.id in it.persone }) }
    var scelte by remember(sorgente.id) { mutableStateOf(sue.map { it.id }.toSet()) }
    val tutte = scelte.size == sue.size
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(titolo) },
        text = {
            Column {
                nota?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Tocca le foto per togliere quelle che non sono ${destinazione.nome ?: "questa persona"}: " +
                    "resteranno nel gruppo di partenza.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${scelte.size} di ${sue.size}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton({ scelte = sue.map { it.id }.toSet() }, enabled = !tutte) { Text("Tutte") }
                    TextButton({ scelte = emptySet() }, enabled = scelte.isNotEmpty()) { Text("Nessuna") }
                }
                GrigliaFoto(sue, Modifier.heightIn(max = 380.dp), selezionate = scelte,
                    onLongClick = { f -> scelte = if (f.id in scelte) scelte - f.id else scelte + f.id }) { i ->
                    val fid = sue[i].id; scelte = if (fid in scelte) scelte - fid else scelte + fid
                }
            }
        },
        confirmButton = {
            TextButton({
                if (tutte) vm.unisci(destinazione.id, sorgente.id) else vm.spostaFoto(vm.espandi(scelte, sue), sorgente.id, destinazione)
                chiudi()
            }, enabled = scelte.isNotEmpty()) { Text(if (tutte) "Unisci tutto" else "Unisci ${scelte.size} foto") }
        },
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

// ---------------------------------------------------------------- INDICE

@Composable
fun CardVideo(vm: AppVM) {
    val ctx = LocalContext.current
    var video by remember { mutableStateOf(Impostazioni.video(ctx)) }
    var sempre by remember { mutableStateOf(Impostazioni.videoSempre(ctx)) }
    var permesso by remember { mutableStateOf(haPermessoVideo(ctx)) }
    val attesa by vm.videoInAttesa.collectAsStateWithLifecycle()
    val richiesta = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permesso = haPermessoVideo(ctx); if (permesso) Indicizzatore.avvia(ctx, sostituisci = true)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Analizza anche i video", fontWeight = FontWeight.Bold)
                    Text("Un fotogramma ogni pochi secondi, analizzato come una foto: trovi persone, oggetti e scene col minuto esatto.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(video, { video = it; Impostazioni.setVideo(ctx, it); if (it) Indicizzatore.avvia(ctx, sostituisci = true) })
            }
            if (video) {
                if (!permesso) {
                    Text("Serve il permesso di accesso ai video.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    Button({
                        richiesta.launch(if (android.os.Build.VERSION.SDK_INT >= 34) arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            else arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO))
                    }) { Text("Consenti l'accesso ai video") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Anche senza caricatore")
                        Text("Di norma i video vengono analizzati solo mentre il telefono è in carica, per non consumare la batteria.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(sempre, { sempre = it; Impostazioni.setVideoSempre(ctx, it); if (it) Indicizzatore.avvia(ctx, sostituisci = true) })
                }
                if (attesa > 0 && !sempre) Text("$attesa video in attesa: verranno analizzati da soli quando metti il telefono in carica.",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun SchermataIndice(vm: AppVM, chiediPermessi: () -> Unit) {
    val ctx = LocalContext.current
    val av by vm.avanzamento.collectAsStateWithLifecycle()
    val (nFoto, nVolti, nPersone) = vm.conteggi.collectAsStateWithLifecycle().value
    val ricalcolo by vm.ricalcolo.collectAsStateWithLifecycle()
    var ocr by remember { mutableStateOf(Impostazioni.ocr(ctx)) }
    var soglia by remember { mutableFloatStateOf(Impostazioni.soglia(ctx)) }
    var confermaAzzera by remember { mutableStateOf(false) }
    val versione = remember { BuildConfig.VERSION_NAME }
    val cartelle by vm.cartelle.collectAsStateWithLifecycle()
    val sceltaCartella = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> u?.let { vm.aggiungiCartella(it) } }
    val esporta = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u -> u?.let { vm.esporta(it) } }
    val importa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importa(it) } }

    LaunchedEffect(av.attivo) { vm.ricarica() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Indice", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val nVideo by vm.nVideo.collectAsStateWithLifecycle()
                Text("Foto analizzate: $nFoto")
                if (nVideo > 0) Text("Video analizzati: $nVideo")
                Text("Volti trovati: $nVolti")
                Text("Persone riconosciute: $nPersone")
                Spacer(Modifier.height(8.dp))
                when {
                    av.attivo && av.fase == "volti" -> { Text("Raggruppo i volti…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    av.attivo && av.fase == "volti_hd" && av.totale > 0 -> {
                        Text("Rianalisi volti in alta risoluzione: ${av.fatte} di ${av.totale}")
                        LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    av.attivo && av.fase == "completamento" && av.totale > 0 -> {
                        Text("Aggiorno le foto già analizzate (colori, età, capelli, abiti): ${av.fatte} di ${av.totale}")
                        LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    av.attivo && av.fase == "video" && av.totale > 0 -> {
                        Text("Analisi video ${av.fatte + 1} di ${av.totale}")
                        LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    av.attivo && av.totale > 0 -> {
                        Text("Analisi foto ${av.fatte} di ${av.totale}")
                        LinearProgressIndicator(progress = { av.fatte / av.totale.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                    av.attivo -> { Text("Lettura della galleria…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> Text("Indice aggiornato. Le foto nuove vengono analizzate ogni volta che apri l'app.")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (av.attivo) OutlinedButton({ Indicizzatore.ferma(ctx) }) { Text("Ferma") }
                    else Button({ Indicizzatore.avvia(ctx) }) { Text("Aggiorna indice") }
                }
                Text("La prima analisi può durare a lungo (circa 2-4 foto al secondo): continua anche a schermo spento, con una notifica.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }

        CardVideo(vm)

        if (accessoParziale(ctx)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Accesso limitato ad alcune foto", fontWeight = FontWeight.Bold)
                    Text("Hai concesso solo alcune foto: l'app cerca solo tra quelle.")
                    TextButton(chiediPermessi) { Text("Cambia accesso") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Leggi il testo nelle foto", fontWeight = FontWeight.Bold)
                        Text("Permette di cercare parole scritte (scontrini, cartelli, documenti). Rende l'analisi un po' più lenta.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(ocr, { ocr = it; Impostazioni.setOcr(ctx, it) })
                }
                Text("Vale per le foto analizzate da qui in avanti.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Raggruppamento volti", fontWeight = FontWeight.Bold)
                Text("Se persone diverse finiscono nello stesso gruppo, sposta verso «Severo». Se la stessa persona è divisa in tanti gruppi, verso «Permissivo».",
                    style = MaterialTheme.typography.bodySmall)
                Slider(soglia, { soglia = it }, valueRange = 0.35f..0.75f,
                    onValueChangeFinished = { Impostazioni.setSoglia(ctx, soglia) })
                Row { Text("Permissivo", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("Severo", style = MaterialTheme.typography.bodySmall) }
                Button({ vm.ricalcolaGruppi() }, enabled = !ricalcolo && !av.attivo) {
                    Text(if (ricalcolo) "Ricalcolo in corso…" else "Ricalcola gruppi")
                }
                Text("I gruppi a cui hai dato un nome vengono mantenuti.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Cartelle e unità esterne", fontWeight = FontWeight.Bold)
                BannerUnita(vm, inIndice = true)
                Text("La galleria (memoria interna e scheda SD) è già inclusa. Qui puoi aggiungere chiavette e dischi USB-C o altre cartelle: quando li ricolleghi, le foto nuove vengono cercate da sole.",
                    style = MaterialTheme.typography.bodySmall)
                val collegate = remember(cartelle) { Unita.uuidCollegati(ctx) }
                for (c in cartelle) Row(verticalAlignment = Alignment.CenterVertically) {
                    val uuid = Unita.uuidDi(c)
                    val scollegata = uuid != null && uuid != "primary" && uuid != "home" && uuid !in collegate
                    Text(nomeCartella(c) + if (scollegata) "  (scollegata)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton({ vm.rimuoviCartella(c) }) { Icon(Icons.Default.Close, "Rimuovi") }
                }
                OutlinedButton({ sceltaCartella.launch(null) }) { Text("Aggiungi cartella o unità esterna") }
                Text("Se scolleghi l'unità, le sue foto restano cercabili; per aprirle va ricollegata.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Scambio con Cerca Foto per Windows", fontWeight = FontWeight.Bold)
                Text("Esporta un file con le persone a cui hai dato un nome e le tue correzioni (niente foto). " +
                    "Importandolo sull'altro dispositivo, le stesse persone vengono riconosciute anche lì. Funziona nei due sensi.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ esporta.launch("persone.cercafoto") }, enabled = !av.attivo) { Text("Esporta") }
                    OutlinedButton({ importa.launch(arrayOf("*/*")) }, enabled = !av.attivo) { Text("Importa") }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("🔒 Privacy", fontWeight = FontWeight.Bold)
                Text("L'app non ha il permesso Internet: Android le impedisce di collegarsi alla rete. Foto, volti e indice restano solo su questo telefono e non vengono inclusi nei backup.")
                Text("Puoi verificarlo in Impostazioni › App › Cerca Foto › Autorizzazioni.", style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedButton({ confermaAzzera = true }, enabled = !av.attivo) { Text("Rianalizza tutto da capo") }
        Text("Cerca Foto $versione · Pony10", style = MaterialTheme.typography.bodySmall)
        Text("Modelli: Google ML Kit (on-device), FaceNet (Apache 2.0).", style = MaterialTheme.typography.bodySmall)
    }

    if (confermaAzzera) {
        AlertDialog(
            onDismissRequest = { confermaAzzera = false },
            title = { Text("Rianalizzare tutto?") },
            text = { Text("Cancello l'indice e rianalizzo tutte le foto. I nomi dati alle persone e le etichette aggiunte a mano andranno persi.") },
            confirmButton = { TextButton({ vm.azzeraEReindicizza(); confermaAzzera = false }) { Text("Rianalizza") } },
            dismissButton = { TextButton({ confermaAzzera = false }) { Text("Annulla") } },
        )
    }
}
