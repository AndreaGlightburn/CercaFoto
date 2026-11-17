package eu.iz2oii.cercafoto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

fun haPermessoFoto(ctx: Context): Boolean {
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= 33)
        ok(Manifest.permission.READ_MEDIA_IMAGES) || (Build.VERSION.SDK_INT >= 34 && ok(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    else ok(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun accessoParziale(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scuro = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (scuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

enum class Scheda { CERCA, PERSONE, INDICE }

@Composable
fun App(vm: AppVM = viewModel()) {
    val ctx = LocalContext.current
    var permesso by remember { mutableStateOf(haPermessoFoto(ctx)) }
    val richiesta = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permesso = haPermessoFoto(ctx)
        if (permesso) { Indicizzatore.avvia(ctx); vm.ricarica() }
    }
    fun chiedi() {
        val p = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        richiesta.launch(p)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permesso = haPermessoFoto(ctx)
        if (permesso) Indicizzatore.avvia(ctx)
    }

    if (!permesso) { SchermataPermessi(::chiedi); return }

    var scheda by rememberSaveable { mutableStateOf(Scheda.CERCA) }
    var visore by remember { mutableStateOf<Pair<List<FotoIdx>, Int>?>(null) }
    var personaAperta by rememberSaveable { mutableStateOf<Long?>(null) }

    val apri: (List<FotoIdx>, Int) -> Unit = { l, i -> visore = l to i }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(scheda == Scheda.CERCA, { scheda = Scheda.CERCA },
                        { Icon(Icons.Default.Search, null) }, label = { Text("Cerca") })
                    NavigationBarItem(scheda == Scheda.PERSONE, { scheda = Scheda.PERSONE },
                        { Icon(Icons.Default.Person, null) }, label = { Text("Persone") })
                    NavigationBarItem(scheda == Scheda.INDICE, { scheda = Scheda.INDICE },
                        { Icon(Icons.Default.Settings, null) }, label = { Text("Indice") })
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (scheda) {
                    Scheda.CERCA -> SchermataCerca(vm, apri, vaiIndice = { scheda = Scheda.INDICE })
                    Scheda.PERSONE -> {
                        val pa = personaAperta
                        if (pa == null) SchermataPersone(vm) { personaAperta = it }
                        else {
                            BackHandler { personaAperta = null }
                            SchermataPersona(vm, pa, apri) { personaAperta = null }
                        }
                    }
                    Scheda.INDICE -> SchermataIndice(vm, chiediPermessi = ::chiedi)
                }
            }
        }
        visore?.let { (l, i) ->
            BackHandler { visore = null }
            Visore(vm, l, i) { visore = null }
        }
    }
}

@Composable
fun SchermataPermessi(chiedi: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Cerca Foto", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Per cercare tra le tue foto l'app deve poterle leggere.\n\n" +
                "Tutta l'analisi (volti, oggetti, testo) avviene sul telefono: l'app non ha il permesso Internet e nessun dato esce dal dispositivo.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = chiedi) { Text("Consenti accesso alle foto") }
    }
}
