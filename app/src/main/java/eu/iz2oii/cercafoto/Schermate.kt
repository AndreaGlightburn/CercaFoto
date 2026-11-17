package eu.iz2oii.cercafoto

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val SUGGERIMENTI = listOf(
    "Persone", "Gruppi", "Selfie", "Sorrisi", "Animali", "Cani", "Gatti", "Cibo", "Mare", "Montagna",
    "Natura", "Neve", "Città", "Auto", "Feste", "Bambini", "Documenti", "Scontrini", "Screenshot",
)

// ---------------------------------------------------------------- CERCA

@Composable
fun SchermataCerca(vm: AppVM, apri: (List<FotoIdx>, Int) -> Unit, vaiIndice: () -> Unit) {
    val q by vm.query.collectAsStateWithLifecycle()
    val ris by vm.risultati.collectAsStateWithLifecycle()
    val foto by vm.foto.collectAsStateWithLifecycle()
    val persone by vm.persone.collectAsStateWithLifecycle()
    val av by vm.avanzamento.collectAsStateWithLifecycle()
    val caricato by vm.caricato.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = q, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp, 8.dp),
            placeholder = { Text("cane al mare, Mario, 2024, scontrino…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Default.Close, "Cancella") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        val nomi = persone.filter { it.nome != null && !it.nascosta }.map { it.nome!! }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nomi + SUGGERIMENTI) { s ->
                SuggestionChip(onClick = { vm.query.value = s; focus.clearFocus() }, label = { Text(s) })
            }
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
                if (q.isBlank()) "${ris.size} foto indicizzate" else "${ris.size} foto trovate",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(12.dp, 6.dp)
            )
        }
        GrigliaFoto(ris, onClick = { i -> focus.clearFocus(); apri(ris, i) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrigliaFoto(lista: List<FotoIdx>, modifier: Modifier = Modifier, onLongClick: ((FotoIdx) -> Unit)? = null, onClick: (Int) -> Unit) {
    val ctx = LocalContext.current
    LazyVerticalGrid(GridCells.Adaptive(100.dp), modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp)) {
        items(lista.size, key = { lista[it].id }) { i ->
            val f = lista[i]
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(f.uri).size(300).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.aspectRatio(1f).padding(1.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onClick = { onClick(i) }, onLongClick = onLongClick?.let { cb -> { cb(f) } })
            )
        }
    }
}

// ---------------------------------------------------------------- VISORE

private val formatoData = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ITALIAN)

@Composable
fun Visore(vm: AppVM, lista: List<FotoIdx>, iniziale: Int, chiudi: () -> Unit) {
    val ctx = LocalContext.current
    val persone by vm.persone.collectAsStateWithLifecycle()
    val stato = rememberPagerState(initialPage = iniziale.coerceIn(0, max(0, lista.size - 1))) { lista.size }
    var info by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(stato, Modifier.fillMaxSize(), key = { lista[it].id }) { p ->
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(lista[p].uri).size(2048).build(),
                contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable { info = !info }
            )
        }
        val f = lista.getOrNull(stato.currentPage) ?: return@Box
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(chiudi) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Text("${stato.currentPage + 1} / ${lista.size}", color = Color.White, modifier = Modifier.weight(1f))
            IconButton({ info = !info }) { Icon(Icons.Default.Info, "Dettagli", tint = Color.White) }
            IconButton({
                val i = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, f.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "Condividi"))
            }) { Icon(Icons.Default.Share, "Condividi", tint = Color.White) }
        }
        if (info) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000))
                    .navigationBarsPadding().padding(16.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState())
            ) {
                Text(formatoData.format(Date(f.data)), color = Color.White, fontWeight = FontWeight.Bold)
                Text("${f.album} · ${f.nome}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                val nomi = persone.filter { it.id in f.persone }.map { it.nome ?: "persona senza nome" }
                if (f.volti > 0) Text("Volti: ${f.volti}" + if (nomi.isNotEmpty()) " — ${nomi.joinToString(", ")}" else "", color = Color.White)
                if (f.etichette.isNotEmpty())
                    Text("Contiene: " + f.etichette.entries.sortedByDescending { it.value }.joinToString(", ") { Dizionario.italiano(it.key) },
                        color = Color.White)
                if (f.testoOriginale.isNotBlank())
                    Text("Testo letto: " + f.testoOriginale.take(400).replace('\n', ' '), color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall)
                TextButton({
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { ctx.startActivity(i) }
                }) { Text("Apri nella Galleria") }
            }
        }
    }
}

// ---------------------------------------------------------------- PERSONE

private object CacheVolti { val lru = LruCache<Long, ImageBitmap>(300) }

private fun caricaVolto(ctx: Context, p: Persona): ImageBitmap? {
    val bw = max(p.r - p.l, 0.02f)
    val lato = (240f / bw).toInt().coerceIn(240, 2048)
    var bmp = ctx.contentResolver.loadThumbnail(uriFoto(p.coverFoto), Size(lato, lato), null)
    val w = bmp.width; val h = bmp.height
    val mx = (p.r - p.l) * 0.2f; val my = (p.b - p.t) * 0.2f
    val l = (max(0f, p.l - mx) * w).toInt(); val t = (max(0f, p.t - my) * h).toInt()
    val r = (min(1f, p.r + mx) * w).toInt(); val b = (min(1f, p.b + my) * h).toInt()
    if (r - l < 2 || b - t < 2) return null
    val crop = Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    val out = Bitmap.createScaledBitmap(crop, 200, 200 * (b - t) / (r - l), true)
    if (crop !== bmp) bmp.recycle()
    return out.asImageBitmap()
}

@Composable
fun VoltoMini(p: Persona, dim: Dp) {
    val ctx = LocalContext.current
    var img by remember(p.coverVolto) { mutableStateOf(CacheVolti.lru.get(p.coverVolto)) }
    LaunchedEffect(p.coverVolto) {
        if (img == null && p.coverVolto > 0) {
            img = withContext(Dispatchers.IO) { runCatching { caricaVolto(ctx, p) }.getOrNull() }
            img?.let { CacheVolti.lru.put(p.coverVolto, it) }
        }
    }
    Box(Modifier.size(dim).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, null)
    }
}

@Composable
fun SchermataPersone(vm: AppVM, apri: (Long) -> Unit) {
    val persone by vm.persone.collectAsStateWithLifecycle()
    val av by vm.avanzamento.collectAsStateWithLifecycle()
    var tutte by remember { mutableStateOf(false) }
    val visibili = persone.filter { tutte || (!it.nascosta && (it.nome != null || it.foto >= 2)) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Persone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
        Text(
            "Volti raggruppati automaticamente. Tocca un gruppo per dargli un nome: poi potrai cercarlo nella scheda Cerca.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 4.dp)
        )
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tutte, { tutte = !tutte }, label = { Text("Mostra anche volti singoli e nascosti") })
        }
        if (av.attivo && av.fase == "volti") Text("Sto raggruppando i volti…", Modifier.padding(16.dp, 4.dp))
        if (visibili.isEmpty()) {
            Text("Nessun gruppo ancora: i volti compaiono qui dopo l'indicizzazione.", Modifier.padding(16.dp))
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
}

@Composable
fun SchermataPersona(vm: AppVM, id: Long, apriFoto: (List<FotoIdx>, Int) -> Unit, indietro: () -> Unit) {
    val persone by vm.persone.collectAsStateWithLifecycle()
    val foto by vm.foto.collectAsStateWithLifecycle()
    val p = persone.firstOrNull { it.id == id }
    if (p == null) { LaunchedEffect(Unit) { indietro() }; return }
    val sue = remember(foto, id) { foto.filter { id in it.persone } }
    var dialogoNome by remember { mutableStateOf(false) }
    var dialogoUnisci by remember { mutableStateOf(false) }
    var daEscludere by remember { mutableStateOf<FotoIdx?>(null) }

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
        Text("Tieni premuta una foto se la persona è sbagliata.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp, 6.dp))
        GrigliaFoto(sue, onLongClick = { daEscludere = it }) { i -> apriFoto(sue, i) }
    }

    if (dialogoNome) {
        var testo by remember { mutableStateOf(p.nome ?: "") }
        AlertDialog(
            onDismissRequest = { dialogoNome = false },
            title = { Text("Nome della persona") },
            text = { OutlinedTextField(testo, { testo = it }, singleLine = true, placeholder = { Text("es. Mario") }) },
            confirmButton = { TextButton({ vm.rinomina(p.id, testo); dialogoNome = false }) { Text("Salva") } },
            dismissButton = { TextButton({ dialogoNome = false }) { Text("Annulla") } },
        )
    }
    if (dialogoUnisci) {
        val altre = persone.filter { it.id != p.id && (it.nome != null || it.foto >= 2) }
        AlertDialog(
            onDismissRequest = { dialogoUnisci = false },
            title = { Text("È la stessa persona di…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(altre, key = { it.id }) { o ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.unisci(p.id, o.id); dialogoUnisci = false }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            VoltoMini(o, 44.dp)
                            Text((o.nome ?: "Senza nome") + " · ${o.foto} foto", Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ dialogoUnisci = false }) { Text("Annulla") } },
        )
    }
    daEscludere?.let { f ->
        AlertDialog(
            onDismissRequest = { daEscludere = null },
            title = { Text("Non è questa persona?") },
            text = { Text("Tolgo questa foto dal gruppo «${p.nome ?: "Senza nome"}».") },
            confirmButton = { TextButton({ vm.escludi(f.id, p.id); daEscludere = null }) { Text("Togli") } },
            dismissButton = { TextButton({ daEscludere = null }) { Text("Annulla") } },
        )
    }
}

// ---------------------------------------------------------------- INDICE

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

    LaunchedEffect(av.attivo) { vm.ricarica() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Indice", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Foto analizzate: $nFoto")
                Text("Volti trovati: $nVolti")
                Text("Persone riconosciute: $nPersone")
                Spacer(Modifier.height(8.dp))
                when {
                    av.attivo && av.fase == "volti" -> { Text("Raggruppo i volti…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
            text = { Text("Cancello l'indice e rianalizzo tutte le foto. I nomi dati alle persone andranno persi.") },
            confirmButton = { TextButton({ vm.azzeraEReindicizza(); confermaAzzera = false }) { Text("Rianalizza") } },
            dismissButton = { TextButton({ confermaAzzera = false }) { Text("Annulla") } },
        )
    }
}
