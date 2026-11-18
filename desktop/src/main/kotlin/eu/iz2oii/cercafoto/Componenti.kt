package eu.iz2oii.cercafoto

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------- immagini con cache

object Cache {
    private val m = object : LinkedHashMap<String, ImageBitmap>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 1500
    }
    @Synchronized fun get(k: String) = m[k]
    @Synchronized fun put(k: String, v: ImageBitmap) { m[k] = v }
}

private fun leggiJpeg(f: File): ImageBitmap? =
    if (f.exists()) runCatching { org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }.getOrNull() else null

@Composable
fun Miniatura(f: FotoIdx, modifier: Modifier = Modifier) {
    val k = "m${f.id}"
    var img by remember(f.id) { mutableStateOf(Cache.get(k)) }
    LaunchedEffect(f.id) {
        if (img == null) img = withContext(Dispatchers.IO) {
            leggiJpeg(Percorsi.miniatura(f.id)) ?: runCatching {
                // miniatura mancante: la ricrea dall'originale
                val b = Immagini.leggi(File(f.percorso), 360)!!
                Percorsi.miniatura(f.id).writeBytes(Immagini.jpeg(b))
                b.toComposeImageBitmap()
            }.getOrNull()
        }?.also { Cache.put(k, it) }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun VoltoMini(voltoId: Long, dim: Dp) {
    val k = "v$voltoId"
    var img by remember(voltoId) { mutableStateOf(Cache.get(k)) }
    LaunchedEffect(voltoId) { if (img == null && voltoId > 0) img = withContext(Dispatchers.IO) { leggiJpeg(Percorsi.volto(voltoId)) }?.also { Cache.put(k, it) } }
    Box(Modifier.size(dim).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.Person, null)
    }
}

@Composable
fun VoltoMini(p: Persona, dim: Dp) {
    // copertina mancante (es. persona con sole foto collegate a mano): usa la miniatura della foto
    if (p.coverVolto > 0 && Percorsi.volto(p.coverVolto).exists()) VoltoMini(p.coverVolto, dim)
    else {
        val f = Stato.foto.collectAsState().value.firstOrNull { it.id == p.coverFoto }
        if (f != null) Box(Modifier.size(dim).clip(CircleShape)) { Miniatura(f, Modifier.fillMaxSize()) } else VoltoMini(-1L, dim)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GrigliaFoto(lista: List<FotoIdx>, selezionate: Set<Long> = emptySet(), lato: Dp = 150.dp, modifier: Modifier = Modifier,
                onLongClick: ((FotoIdx) -> Unit)? = null, onClick: (Int) -> Unit) {
    val stato = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(lato), modifier.fillMaxSize(), state = stato, contentPadding = PaddingValues(4.dp)) {
        items(lista.size, key = { lista[it].id }) { i ->
            val f = lista[i]; val sel = f.id in selezionate
            Box(Modifier.aspectRatio(1f).padding(2.dp)
                .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                .combinedClickable(onClick = { onClick(i) }, onLongClick = onLongClick?.let { cb -> { cb(f) } })) {
                Miniatura(f, Modifier.fillMaxSize().padding(if (sel) 6.dp else 0.dp))
                if (sel) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---------------------------------------------------------------- filtri (come Android)

val NOMI_ETA = mapOf("bambini" to "Bambini (0-12)", "ragazzi" to "Ragazzi (13-29)", "adulti" to "Adulti (30-59)", "anziani" to "Anziani (60+)")
val NOMI_CAPELLI = mapOf("nero" to "Neri", "castano" to "Castani", "biondo" to "Biondi", "rosso" to "Rossi", "grigio" to "Grigi/bianchi")
val NOMI_PERSONE = mapOf(0 to "Nessuna persona", 1 to "Una persona", 2 to "Due persone", 3 to "Gruppo (3+)")

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
fun PannelloFiltri(iniziali: Filtri, conCartelle: Boolean = true, applica: (Filtri) -> Unit, chiudi: () -> Unit) {
    var f by remember { mutableStateOf(iniziali) }
    val foto by Stato.foto.collectAsState()
    val album = remember(foto) { foto.groupingBy { it.album }.eachCount().entries.filter { it.key.isNotBlank() }.sortedByDescending { it.value } }
    var tutte by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text("Filtri") },
        text = {
            Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                Text("Età, sesso e capelli sono stime: valgono sulla stessa persona e solo per volti abbastanza grandi.", style = MaterialTheme.typography.bodySmall)
                if (conCartelle) Scelte("Persone nella foto", NOMI_PERSONE.toList(), f.persone) { f = f.copy(persone = it) }
                Scelte("Sesso", listOf("donna" to "Donna", "uomo" to "Uomo"), f.sesso) { f = f.copy(sesso = it) }
                Scelte("Età", NOMI_ETA.toList(), f.eta) { f = f.copy(eta = it) }
                Scelte("Capelli", NOMI_CAPELLI.toList(), f.capelli) { f = f.copy(capelli = it) }
                if (conCartelle) {
                    Scelte("Colore abiti", Colori.NOMI.map { it to it }, f.abiti) { f = f.copy(abiti = it) }
                    val el = if (tutte) album else album.take(12)
                    Scelte("Cartella", el.map { it.key to "${it.key} (${it.value})" }, f.album) { f = f.copy(album = it) }
                    if (album.size > 12) TextButton({ tutte = !tutte }) { Text(if (tutte) "Meno cartelle" else "Tutte le cartelle (${album.size})") }
                }
            }
        },
        confirmButton = { TextButton({ applica(f); chiudi() }) { Text("Applica") } },
        dismissButton = { TextButton({ applica(Filtri()); chiudi() }) { Text("Azzera") } },
    )
}

// ---------------------------------------------------------------- persone

@Composable
fun RigaPersona(o: Persona, somiglianza: Float?, scegli: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = scegli).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        VoltoMini(o, 44.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(o.nome ?: "Senza nome", fontWeight = if (o.nome != null) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${o.foto} foto" + (somiglianza?.takeIf { it > 0f }?.let { " · somiglianza ${(it * 100).toInt()}%" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DialogoScegliPersona(titolo: String, persone: List<Persona>, escludi: Long?, simili: Map<Long, Float>, chiudi: () -> Unit,
                         extra: (@Composable () -> Unit)? = null, scegli: (Persona) -> Unit) {
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
            Column(Modifier.width(460.dp)) {
                OutlinedTextField(cerca, { cerca = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Cerca un nome") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (cerca.isNotEmpty()) IconButton({ cerca = "" }) { Icon(Icons.Default.Close, "Cancella") } })
                extra?.invoke()
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    fun sezione(t: String, lista: List<Persona>, k: String) {
                        if (lista.isEmpty()) return
                        item(key = "t$k") { Text(t, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
                        items(lista, key = { "$k${it.id}" }) { o -> RigaPersona(o, simili[o.id]) { scegli(o) } }
                    }
                    if (q.isNotEmpty()) {
                        sezione("Risultati", trovate, "r")
                        if (trovate.isEmpty()) item { Text("Nessun nome trovato.", Modifier.padding(8.dp)) }
                    } else {
                        sezione("Più somiglianti", suggerite, "s"); sezione("Con nome (A-Z)", nominate, "n"); sezione("Senza nome", senzaNome, "x")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

@Composable
fun DialogoNome(titolo: String, conferma: String, chiudi: () -> Unit, ok: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = chiudi,
        title = { Text(titolo) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true, placeholder = { Text("Nome") }) },
        confirmButton = { TextButton({ if (t.isNotBlank()) { ok(t); chiudi() } }) { Text(conferma) } },
        dismissButton = { TextButton(chiudi) { Text("Annulla") } },
    )
}

@Composable
fun DialogoAggiungiPersona(lista: List<Long>, chiudi: () -> Unit, fatto: () -> Unit) {
    val persone by Stato.persone.collectAsState()
    var nuova by remember { mutableStateOf(false) }
    val n = if (lista.size == 1) "questa foto" else "${lista.size} foto"
    if (nuova) DialogoNome("Nuova persona", "Crea", chiudi) { Stato.nuovaPersonaConFoto(lista, it); fatto() }
    else DialogoScegliPersona("Aggiungi $n a…", persone.filter { it.nome != null }, null, emptyMap(), chiudi, extra = {
        Text("Anche se il volto non si vede: la foto comparirà nel gruppo e nelle ricerche per nome.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        TextButton({ nuova = true }) { Text("+ Nuova persona") }
    }) { p -> Stato.associa(lista, p); chiudi(); fatto() }
}

@Composable
fun DialogoVolto(v: VoltoInfo, persone: List<Persona>, chiudi: () -> Unit, apriPersona: (Long) -> Unit) {
    val attuale = persone.firstOrNull { it.id == v.persona }
    var scegli by remember { mutableStateOf(false) }
    var nuovo by remember { mutableStateOf(false) }
    var simili by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    LaunchedEffect(v.id) { simili = withContext(Dispatchers.IO) { Stato.somiglianzeVolto(v.id) } }
    when {
        nuovo -> DialogoNome("Nuova persona", "Crea", chiudi) { Stato.nuovaPersonaDaVolto(v.id, it) }
        scegli -> DialogoScegliPersona("Sposta questo volto in…", persone, v.persona, simili, chiudi,
            extra = { TextButton({ scegli = false; nuovo = true }) { Text("+ Nuova persona") } }) { o -> Stato.assegnaVolto(v.id, o.id, o.nome); chiudi() }
        else -> AlertDialog(
            onDismissRequest = chiudi,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoltoMini(v.id, 56.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(attuale?.nome ?: if (attuale != null) "Gruppo senza nome" else "Nessun gruppo")
                        attuale?.let { Text("${it.foto} foto nel gruppo", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            text = {
                Column {
                    if (v.manuale) {
                        Text("Foto collegata a mano a questa persona.", style = MaterialTheme.typography.bodySmall)
                        if (attuale != null) TextButton({ apriPersona(attuale.id) }) { Text("Apri il gruppo") }
                        TextButton({ Stato.rimuoviAssociazione(v.id); chiudi() }) { Text("Togli da questa persona", color = MaterialTheme.colorScheme.error) }
                    } else {
                        if (!v.conVettore) Text("Volto troppo piccolo per il riconoscimento automatico, ma puoi assegnarlo a mano.", style = MaterialTheme.typography.bodySmall)
                        if (attuale != null) TextButton({ apriPersona(attuale.id) }) { Text("Apri il gruppo") }
                        TextButton({ scegli = true }) { Text(if (attuale != null) "Sposta in un'altra persona…" else "Assegna a una persona…") }
                        TextButton({ nuovo = true }) { Text("Crea una nuova persona") }
                        if (attuale != null) TextButton({ Stato.assegnaVolto(v.id, Db.ESCLUSO, null); chiudi() }) { Text("Togli dal gruppo", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(chiudi) { Text("Chiudi") } },
        )
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
