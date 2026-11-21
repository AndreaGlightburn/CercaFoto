# Cerca Foto — Handoff

Stato del progetto al 21/11/2026. Autore delle app: **Pony10** (Andrea, IZ2OII). Repository privato: `github.com/AndreaGlightburn/CercaFoto`.

Due app collegate che cercano le foto **solo in locale** (nessun dato in rete):

- **Android** (Kotlin + Jetpack Compose), APK nella release `ultima`.
- **Windows** (Kotlin + Compose Multiplatform Desktop + ONNX Runtime), MSI e ZIP portatile nella release `windows`.

Le due app si scambiano persone e correzioni tramite un file `.cercafoto`.

---

## 1. Principi da non rompere

1. **Privacy**: l'APK **non ha il permesso INTERNET**. Il manifest lo rimuove con `tools:node="remove"`, e la GitHub Action fallisce se `aapt dump permissions` lo trova. Sul PC nessun codice apre connessioni. I modelli sono inclusi nei pacchetti.
2. **Lingua**: interfaccia e codice in italiano (nomi di classi e variabili inclusi).
3. **Mai perdere il lavoro dell'utente**: nomi delle persone, etichette manuali, esclusioni e collegamenti a mano.
   - Sopravvivono a rianalisi, ricalcolo dei gruppi, aggiornamenti dell'app e unità scollegate.
   - Ogni modifica allo schema del DB va fatta con una **migrazione**.
   - Le build sono firmate con una **chiave fissa**, così l'app si installa sopra la precedente.
4. **Indicizzazione incrementale**: si analizzano solo le foto nuove o modificate. Le nuove funzioni di analisi si aggiungono con un "completamento" sulle foto già indicizzate, senza rifare tutto.
5. **Build solo in CI**: nell'ambiente di sviluppo non c'è Android SDK né Maven. Si compila con GitHub Actions e si legge il log con `gh run view --log-failed`.

---

## 2. Struttura del repository

```
.github/workflows/
  build.yml          APK Android → release "ultima" (paths-ignore desktop/**)
  windows.yml        MSI + ZIP Windows → release "windows" (paths desktop/**); scarica i modelli CLIP dalla release "modelli-clip"
  modelli-clip.yml   esporta CLIP in ONNX int8 → release "modelli-clip"
app/                 Android
  build.gradle.kts   AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 31, solo arm64-v8a, firma da cercafoto.p12.b64
  cercafoto.p12.b64  chiave di firma fissa (password "cercafoto"), creata dalla prima Action
  src/main/assets/   facenet_512.tflite, eta.tflite, sesso.tflite
  src/main/java/eu/iz2oii/cercafoto/*.kt
desktop/             Windows (progetto Gradle separato)
  build.gradle.kts   Kotlin 2.0.21, Compose 1.7.3, onnxruntime 1.20.0, sqlite-jdbc, org.json, metadata-extractor, TwelveMonkeys
  icona.ico
  strumenti/esporta_clip.py
  src/main/resources/modelli/  facenet_512.onnx, eta.onnx, sesso.onnx, ultraface-640.onnx (+ clip_*.onnx e vocab.txt scaricati in CI, non nel repo)
  src/main/kotlin/eu/iz2oii/cercafoto/*.kt
NOTICE               licenze dei modelli
```

**File condivisi, identici tra Android e Windows**: `Dizionario.kt`, `Ricerca.kt` (contiene anche `Filtri`) e `ScambioFormato.kt`. Se ne modifichi uno, copialo anche nell'altra app.

---

## 3. App Android

### 3.1 File

| File | Contenuto |
|---|---|
| `MainActivity.kt` | permessi (READ_MEDIA_IMAGES, accesso parziale, POST_NOTIFICATIONS, ACCESS_MEDIA_LOCATION), navigazione Cerca/Persone/Indice, visore, conferme di sistema (IntentSender), snackbar |
| `AppVM.kt` | ViewModel: stato, ricerca con debounce, operazioni su persone, filtri, copia/sposta/elimina, esporta/importa, unità USB |
| `Db.kt` | SQLiteOpenHelper **versione 6**, data class `FotoIdx`, `Persona`, `VoltoAttr`, `VoltoInfo` |
| `Indicizzatore.kt` | `CoroutineWorker` in primo piano (dataSync): galleria via MediaStore + cartelle SAF, completamento versione, analisi, raggruppamento, rianalisi volti in alta risoluzione |
| `Analizzatore.kt` | ML Kit (etichette, volti, testo) + FaceNet + età/sesso, attributi del volto |
| `Colori.kt` | colori dominanti, colore di capelli e abiti (euristiche HSV) |
| `Raggruppa.kt` | clustering dei volti |
| `Ricerca.kt` | ricerca in italiano + `Filtri` (condiviso) |
| `Dizionario.kt` | ~440 etichette ML Kit tradotte, categorie, normalizzazione del testo (condiviso) |
| `Operazioni.kt` | dettagli e EXIF, copia/sposta/elimina (MediaStore e SAF) |
| `Scambio.kt` / `ScambioFormato.kt` | esporta/importa `.cercafoto` |
| `Unita.kt` | unità USB-C (StorageVolume), receiver `UnitaCollegata` su MEDIA_MOUNTED |
| `Schermate.kt` | tutta l'interfaccia Compose |

### 3.2 Modelli (on-device)

- **ML Kit "bundled"**, inclusi nell'APK:
  - `image-labeling:17.0.9`, soglia 0.6
  - `face-detection:16.1.7`: ACCURATE, CLASSIFICATION_ALL, minFaceSize 0.05
  - `text-recognition:16.0.1`: OCR latino, disattivabile
- **FaceNet 512**: `facenet_512.tflite`, 24 MB, Apache 2.0, da shubham0204/FaceRecognition_With_FaceNet_Android.
  - Input 160×160 RGB standardizzato per immagine (media/deviazione), output 512 normalizzato L2.
  - Volti con lato < 40 px: nessun vettore.
- **Età/sesso**: `eta.tflite` (input 200×200, RGB/255, output ×116 = anni) e `sesso.tflite` (input 128×128, output [maschio, femmina]). Licenza MIT, da shubham0204/Age-Gender_Estimation_TF-Android, addestrati su UTKFace. Il ritaglio del volto ha un margine del 10%.

### 3.3 Schema DB (indice.db, v6)

```
foto(id PK = MediaStore _ID, oppure id negativo FNV-1a dell'URI per SAF, data, modificata, nome, album,
     etichette "Label:pct;…", volti, sorriso, testo, colori "nome:pct;…", versione (analisi, oggi 2),
     uri (solo SAF), cartella (tree SAF), manuali "a;b", rimosse "Label;…", sha, volume)
volto(id PK, foto_id, l,t,r,b normalizzati 0..1, area, emb BLOB float32 LE, persona_id,
      eta, maschio, capelli, abiti, manuale 0/1)
persona(id PK, nome, copertina (volto id), foto, nascosta, uid)
riferimento(persona_id, emb)          -- campioni dei volti importati dall'altro dispositivo
```

**Valori speciali di `persona_id`**:
- `0` = non assegnato
- `-2` (`Db.ESCLUSO`) = tolto a mano, non entra nei raggruppamenti
- `manuale=1` = collegamento foto↔persona senza volto: riquadro 0.05–0.95, area 0, niente vettore. Sopravvive a `salva()` e alla rianalisi.

**Migrazioni**:
- v2: colori, versione, età/sesso/capelli/abiti, uri, cartella, manuali, rimosse
- v3: `manuale`
- v4: `uid`, `sha`
- v5: `volume`
- v6: tabella `riferimento`

`ricontaInterno()` cancella solo le persone **senza nome** rimaste senza foto. Le persone con nome restano anche vuote, perché servono allo scambio.

### 3.4 Indicizzazione

1. **Lettura**: galleria `MediaStore.VOLUME_EXTERNAL` (memoria interna + SD, con `VOLUME_NAME`) e cartelle SAF dalle preferenze. Le unità scollegate vengono saltate.
2. **Rimozioni**: solo per le foto davvero sparite.
   - Galleria: solo se il loro volume è montato. Una SD tolta non cancella niente.
   - SAF: solo se la cartella è raggiungibile, oppure se è stata tolta dall'elenco.
3. **Completamento**: le foto con `versione < 2` ricevono solo colori e attributi dei volti già noti.
4. **Analisi completa**: per le foto nuove o modificate. `loadThumbnail` a 1280 px, con l'EXIF già applicato. Per le foto SAF la data viene dall'EXIF.
5. **Raggruppamento**: se qualcosa è cambiato.
6. **Rianalisi volti in alta risoluzione** (pulsante "Riorganizza volti senza nome"):
   - riguarda solo le foto con volti senza nome, a 2560 px, minFaceSize 0.025
   - `sostituisciVolti` conserva i volti con nome e gli esclusi abbinandoli per IoU ≥ 0.35

Su Android 15+ un worker dataSync dura al massimo 6 ore: se si interrompe, riparte all'apertura dell'app e continua da dove era arrivato.

### 3.5 Raggruppamento (Raggruppa.kt)

- Le persone con nome restano fisse. I loro centri (media normalizzata dei vettori) comprendono anche i **campioni importati** (`riferimento`).
- Le persone senza nome vengono cancellate e i loro volti ricalcolati.
- **Passaggio 1**: i volti liberi, ordinati per area decrescente, vanno al centro più simile se coseno ≥ soglia (preferenza utente, predefinita **0.5**, cursore 0.35–0.75). Altrimenti formano un gruppo nuovo.
- **Passaggio 2**: riassegnazione al centro migliore (margine 0.02).
- **Avvertenza**: un volto spostato in un gruppo **senza nome** può tornare indietro al ricalcolo successivo. L'interfaccia lo segnala.

### 3.6 Ricerca (Ricerca.kt, condiviso)

Ogni parola deve essere soddisfatta (AND), dopo aver tolto le parole vuote. L'ordine di riconoscimento è:

1. frasi delle etichette manuali di più parole
2. nomi delle persone (anche di più parole)
3. termini composti del dizionario
4. scansione parola per parola:
   - **vincoli sul volto**, che devono valere sulla **stessa persona**: donna, anziana, ragazzo, "capelli biondi", "maglia rossa"
   - **colori**: soglia 12% della foto, oppure il colore degli abiti
   - **anni, mesi e stagioni**
   - **parole speciali**: persone, gruppo, coppia, sorriso, selfie, screenshot, testo, documenti, colorate, bambini, chiavetta/usb
   - **etichette** del dizionario, con radici per singolare/plurale
   - **altre etichette e testo**: etichette manuali, testo OCR, nome file, album

**Regole sui volti**:
- età e sesso contano solo per i volti "affidabili" (area ≥ 0.003, circa 70 px su 1280)
- donna = maschio < 0.4, uomo = maschio > 0.6

**`Filtri`** (pannello): numero di persone, sesso, fascia d'età (0-12 / 13-29 / 30-59 / 60+), capelli, colore abiti, cartella.

### 3.7 Funzioni dell'interfaccia (Android)

- **Cerca**: barra di ricerca, suggerimenti (nomi + categorie), pannello filtri, banner per le unità USB, griglia.
- **Selezione multipla** (pressione lunga): condividi, copia, sposta, elimina, aggiungi a persona.
- **Visore** (pager), pannello ⓘ:
  - data, dettagli (cartella, file, dimensione, risoluzione, fotocamera, GPS con pulsante Mappa)
  - volti con il loro gruppo (✋ = collegamento a mano)
  - stime per volto, etichette, colori, testo letto
  - menu: copia/sposta/elimina, modifica etichette, aggiungi a persona, altre foto della cartella
- **Volto nel visore**: apri gruppo, sposta in un'altra persona (ricerca + somiglianza col volto), crea persona, togli dal gruppo.
- **Persone**:
  - ricerca per nome, filtri senza nome/con nome, filtri per sesso/età/capelli sul profilo del gruppo (età mediana, sesso medio, capelli più frequenti), volti singoli e nascosti
  - "Riorganizza volti senza nome": veloce con severità scelta, oppure alta risoluzione
- **Pagina persona**:
  - rinomina (propone di unire se il nome esiste già), unisci (ricerca + 6 gruppi più simili), nascondi
  - "Potrebbe essere anche…": gruppi senza nome con somiglianza ≥ 45% e anteprima
  - selezione multipla → togli / sposta in…, con suggerimenti per somiglianza
- **Indice**:
  - stato e avanzamento, ferma/aggiorna, unità USB e cartelle, OCR sì/no, severità e ricalcolo
  - scambio con Windows (esporta/importa), privacy, rianalizza tutto
- **Copia/sposta/elimina**:
  - consensi di sistema `createWriteRequest`, `createTrashRequest`, `createDeleteRequest`
  - spostare dentro la galleria cambia solo `RELATIVE_PATH`, quindi l'id e l'indice restano
  - una copia nella galleria duplica la voce dell'indice
  - verso un'unità SAF si copia e poi si elimina l'originale
- **Unità USB-C**:
  - `Unita.daProporre` = unità rimovibili montate non indicizzate da MediaStore e non ancora aggiunte → banner "Aggiungi" con `createOpenDocumentTreeIntent()`
  - nomi leggibili salvati nelle preferenze
  - aggiornamento automatico su MEDIA_MOUNTED

---

## 4. App Windows

### 4.1 File

| File | Contenuto |
|---|---|
| `Main.kt` | finestra, NavigationRail (Cerca, Persone, Filtri, Cartelle), Cerca, Visore (frecce, Esc), Persone, pagina persona, Cartelle/indice |
| `Stato.kt` | stato globale (come il ViewModel): ricerca, filtri, filtri personalizzati, operazioni |
| `Db.kt` | SQLite JDBC in `%LOCALAPPDATA%\CercaFoto\indice.db` (oppure `~/.cercafoto`). Miniature in `miniature\{id}.jpg`, ritagli dei volti in `volti\{voltoId}.jpg` |
| `Analisi.kt` | lettura immagini (sottocampionamento + orientamento EXIF), UltraFace, FaceNet, età/sesso, colori |
| `Clip.kt` | CLIP multilingue: tokenizzatore WordPiece in Kotlin, vettore immagine, etichette zero-shot, ricerca libera |
| `Indicizzatore.kt` | `Impostazioni` (properties), scansione delle cartelle, analisi parallela (metà dei core), completamento CLIP, rianalisi volti in alta risoluzione |
| `Raggruppa.kt` | come Android, con i riferimenti |
| `Scambio.kt` | esporta/importa `.cercafoto` |
| `FiltriUtente.kt` / `SchermataFiltri.kt` | filtri personalizzati |
| `Componenti.kt` | cache delle immagini, griglia con barra di scorrimento e zoom (Ctrl+rotellina), dialoghi condivisi |

**Schema DB**: come Android, con queste differenze:
- `foto` ha in più `percorso` (UNIQUE), `cartella`, `dimensione`, `clip` BLOB, `etichette`; `id` è AUTOINCREMENT
- c'è la tabella `filtro`
- un `clip` vuoto (0 byte) significa "file illeggibile, non riprovare"

### 4.2 Modelli ONNX

- **UltraFace RFB-640** (MIT):
  - input 1×3×480×640, `(x-127)/128`, l'immagine viene stirata
  - output `scores[1,17640,2]` e `boxes` normalizzati
  - soglia 0.7 (0.6 nella rianalisi), NMS con IoU 0.3
- **FaceNet / età / sesso**: convertiti da TFLite con `tf2onnx` (opset 13). Coseno con il TFLite pari a **0.99995**, quindi i vettori di telefono e PC sono confrontabili.
- **CLIP**, esportato da `desktop/strumenti/esporta_clip.py` in GitHub Actions:
  - **testo**: `sentence-transformers/clip-ViT-B-32-multilingual-v1` (DistilBERT multilingue + mean pooling + Dense 768→512), ONNX int8 da 135 MB. La tabella del vocabolario va quantizzata (Gather), altrimenti pesa 412 MB.
  - **immagine**: `openai/clip-vit-base-patch32` con `attn_implementation="eager"`, int8 solo MatMul/Gemm da 96 MB. ConvInteger non esiste su CPU.
  - fedeltà rispetto all'fp32: testo 0.993–0.996, immagine 0.983
- **Pre-elaborazione immagine CLIP**:
  - riduzione a metà per passi fino a < 448 (fa da anti-aliasing)
  - lato corto a 224 bicubico, ritaglio centrale
  - normalizzazione media `[0.48145466, 0.4578275, 0.40821073]`, deviazione `[0.26862954, 0.26130258, 0.27577711]`
  - coseno con PIL 0.97–0.99
- **Tokenizzatore**: BERT cased (niente minuscole, accenti mantenuti), CLS=101, SEP=102, UNK=100, massimo 128 token. Verificato identico a HF sulle frasi di prova.
- **Soglie CLIP**:
  - ricerca libera: ≥ 0.215 e ≥ (migliore − 0.06), al massimo 1000 foto
  - etichette zero-shot: prompt "a photo of {label}", prime 5, ≥ 0.245 e ≥ (migliore − 0.03)

### 4.3 Filtri personalizzati (FiltriUtente.kt)

- **Da esempi**:
  - regressione logistica a 512 dimensioni sui vettori CLIP, 400 iterazioni, lr 2.0, L2 0.002
  - **positivi** = foto scelte
  - **negativi** = foto escluse a mano (peso 0.6) + 600 foto casuali come "sfondo" (peso 1.0, oppure 0.4 se ci sono esclusioni)
  - le due classi sono bilanciate
  - punteggio = sigmoide; **sensibilità iniziale 0.28**, cursore 0.05–0.98
- **Da descrizione**: vettore medio delle frasi, punteggio = coseno, soglia 0.22, cursore 0.15–0.35.
- **Correzioni**:
  - "Non c'entrano" → negativi, "Esempi perfetti" → positivi, poi riaddestramento
  - le correzioni su un filtro da descrizione lo trasformano in filtro da esempi, partendo dalle sue prime 30 foto
- **Uso**: pulsanti ★ in Cerca; il nome del filtro scritto nella ricerca vale come parola chiave.
- **File `.cercafiltro`** (JSON): `{formato:"cercafiltro", versione:1, modello:"clip-vit-b32-multilingual-v1", nome, tipo, soglia, bias, pesi (base64 float32 LE), frasi}`.
- **Test su 418 foto ImageNet** (118 cani), con 10 esempi a 0.28: 104/108 cani trovati, 5 intrusi su 300. Il filtro da descrizione: 114/118 cani, 40 intrusi.

### 4.4 Interfaccia Windows

- **Cerca**: "Parole chiave" / "Descrizione libera", filtri, filtri ★, suggerimenti, selezione con pressione lunga (aggiungi a persona, crea filtro da esempi, aggiungi esempi), zoom.
- **Visore**: frecce, Esc, apri, mostra in Esplora risorse, volti (sposta/crea/togli), etichette.
- **Persone** e **pagina persona**: come Android.
- **Filtri**: scheda dedicata.
- **Cartelle**: aggiungi con JFileChooser, avanzamento, scambio, privacy.
- **Non presenti** rispetto ad Android: OCR, copia/sposta/elimina file, HEIC.

---

## 5. Scambio `.cercafoto` (ScambioFormato.kt)

Il file è JSON compresso con gzip:

```json
{ "formato":"cercafoto", "versione":1, "origine":"android|windows", "creato":ms,
  "persone":[{"uid","nome","nascosta","campioni":[base64 float32 LE ×512, max 24]}],
  "foto":[{"nome","dimensione","data","sha256"?,"etichette"?,"rimosse"?,
           "volti":[{"box":[l,t,r,b],"persona":uid?,"escluso"?,"manuale"?}]}] }
```

- **Esportazione**:
  - persone con nome; campioni = volti più grandi, completati con i riferimenti se sono pochi
  - foto con correzioni o con volti assegnati a persone con nome
  - l'**SHA viene incluso solo se è già stato calcolato**: leggere ogni file rendeva l'export lentissimo e produceva file vuoti
- **Importazione**:
  1. **Persone**: per uid, poi per nome normalizzato, altrimenti nuove. I campioni vanno salvati in `riferimento`.
  2. **Foto**, con `scegliFoto`: per nome, poi dimensione uguale, poi data ±2 s, poi SHA (solo se ≤ 5 candidate). Si applicano etichette (unione), rimosse, collegamenti manuali e assegnazioni dei volti con `abbinaRiquadro`: distanza dei centri < 0.5 × lato del volto, rapporto d'area 0.2–5.
  3. **Volti** non assegnati a persone con nome: confronto col massimo dei campioni, soglia = preferenza − 0.05.
  4. **Raggruppamento**.
- Un file vuoto o non gzip dà un messaggio esplicito.
- **Verificato in jshell**: esporta/importa tra due DB, importazione su un indice vuoto seguita da indicizzazione → riconosciuto grazie ai riferimenti.

---

## 6. Build e rilascio

- **Android**: push su `main`, poi `build.yml` (Gradle 8.10.2, JDK 17), `assembleRelease`, verifica dei permessi, release `ultima` con `CercaFoto.apk` (~97 MB). Il `versionCode` è il numero di esecuzione della Action.
- **Windows**: push in `desktop/**`, poi `windows.yml` su windows-latest. Scarica i modelli CLIP, esegue `createDistributable packageMsi` e crea la release `windows` con `CercaFoto-Windows.msi` (~370 MB, per utente, upgradeUuid fisso) e `CercaFoto-Windows-portatile.zip`.
- **Modelli CLIP**: push di `esporta_clip.py`, poi `modelli-clip.yml` (torch 2.4.1 CPU, transformers 4.44.2, sentence-transformers 3.0.1, onnx 1.16.2, onnxruntime 1.19.2) e la release `modelli-clip` con `riferimenti.json` per la verifica.
- **Test del motore Windows senza Windows**: scarica lo ZIP portatile, poi `jshell --class-path <tutti i jar tranne skiko> -R-Duser.home=/tmp/x`, chiamando gli oggetti Kotlin tramite `INSTANCE`.
- **Consegna all'utente**:
  - il MSI va mandato dentro uno ZIP (il download diretto di .msi è bloccato)
  - link: `…/releases/download/windows/CercaFoto-Windows.msi`, con accesso GitHub perché il repo è privato
- **Attribuzione dei commit**: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## 7. Limiti noti e cose da verificare

- **Scambio telefono ↔ PC con foto vere**: non ancora confermato. L'utente ha segnalato che i nomi non comparivano. La causa (import prima dell'indicizzazione + export vuoto) è stata corretta nelle build Android 16 e Windows 10+. Serve una prova con un file reale. Se fallisce, chiedere il file e il messaggio di fine importazione.
- **Interfaccia Windows mai vista girare**: lo sviluppo è avvenuto senza Windows. Dimensione e comportamento dei dialoghi sono da verificare.
- **Soglie tarate su pochi dati**: facce di prova (stesso volto 0.97–1.00, persone diverse ≤ 0.40), ImageNet. Da regolare sulle foto dell'utente:
  - volti 0.5
  - CLIP 0.215 / 0.245
  - filtri 0.28
- **Stime euristiche**: età/sesso/capelli/abiti sono approssimativi; capelli e abiti sono euristiche di colore su zone sopra e sotto il volto.
- **Copie nella galleria Android**: finiscono sempre nella memoria interna (`VOLUME_EXTERNAL_PRIMARY`).
- **Cartelle SAF aggiunte prima della build 1.0.3**: sono in sola lettura. Per spostare o eliminare file vanno riaggiunte.
- **Windows: funzioni mancanti**: copia/sposta/elimina file, OCR, HEIC.
- **Dimensioni**: APK ~97 MB, MSI ~370 MB (`includeAllModules` + onnxruntime per tutti i sistemi operativi: si può alleggerire).

## 8. Idee già discusse e non ancora fatte

- Pacchetti di filtri pronti (piante, animali, situazioni di protezione civile), generati da elenchi pubblici di categorie.
- "Importa modello ONNX" per classificatori specializzati (Hugging Face, da valutare per licenza).
- Filtri personalizzati e ricerca libera anche su Android (+~200 MB).
- Versione Linux (stesso codice desktop: basta un job CI con AppImage/.deb).
- Analisi a ritagli per i dettagli piccoli (anelli, tatuaggi piccoli).
