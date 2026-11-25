# Cerca Foto — Handoff

Stato del progetto al 25/11/2026 (sezione 9 = novità: visore avanzato, unione selettiva, video). Autore delle app: **Pony10** (Andrea, IZ2OII). Repository privato: `github.com/AndreaGlightburn/CercaFoto`.

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

**File condivisi, identici tra Android e Windows**: `Dizionario.kt`, `Ricerca.kt` (contiene anche `Filtri`), `ScambioFormato.kt` e `Visione.kt` (zoom, lente, regolazioni). Se ne modifichi uno, copialo anche nell'altra app.

---

## 3. App Android

### 3.1 File

| File | Contenuto |
|---|---|
| `MainActivity.kt` | permessi (READ_MEDIA_IMAGES, accesso parziale, POST_NOTIFICATIONS, ACCESS_MEDIA_LOCATION), navigazione Cerca/Persone/Indice, visore, conferme di sistema (IntentSender), snackbar |
| `AppVM.kt` | ViewModel: stato, ricerca con debounce, operazioni su persone, filtri, copia/sposta/elimina, esporta/importa, unità USB |
| `Db.kt` | SQLiteOpenHelper **versione 7** (v7 = video), data class `FotoIdx`, `Persona`, `VoltoAttr`, `VoltoInfo` |
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
- **Video**: non ancora provati su un telefono vero (MediaMetadataRetriever, rotazione dei fotogrammi, lavoro "in carica") né sull'interfaccia Windows; il motore Windows è stato provato in jshell (vedi 9.3).
- **Dimensioni**: APK ~97 MB, MSI ~430 MB con FFmpeg (`includeAllModules` + onnxruntime per tutti i sistemi operativi: si può alleggerire).

## 8. Idee già discusse e non ancora fatte

- Pacchetti di filtri pronti (piante, animali, situazioni di protezione civile), generati da elenchi pubblici di categorie.
- "Importa modello ONNX" per classificatori specializzati (Hugging Face, da valutare per licenza).
- Filtri personalizzati e ricerca libera anche su Android (+~200 MB).
- Versione Linux (stesso codice desktop: basta un job CI con AppImage/.deb).
- Analisi a ritagli per i dettagli piccoli (anelli, tatuaggi piccoli).
- Video, passi successivi: trascrizione del parlato (Whisper sul PC, modello leggero sul telefono) e video nel file di scambio (abbinamento per nome del video + istante).
- **Raspberry Pi 5 (16 GB) — rimandato, da riprendere.** Fattibile con il codice desktop: serve una build Linux ARM64 (Compose/skiko e onnxruntime hanno aarch64; FFmpeg da apt). Consigliati SSD NVMe (non microSD) e raffreddamento attivo. Stima: 1–3 foto/s con CLIP, 1 h di video ≈ 15–30 min (H.264 decodificato in software, HEVC in hardware). Due strade da scegliere: (a) Pi usato come PC con monitor = solo build Linux ARM; (b) Pi come server sempre acceso con interfaccia web in LAN protetta da password, che indicizza di notte un disco con foto/video. Eventuale acceleratore Hailo AI HAT+ solo in un secondo momento (richiede conversione modelli in HEF).

---

## 9. Novità del 25/11/2026

### 9.1 Visore avanzato (Android e Windows)

- `Visione.kt` (condiviso): `ZoomStato` + `VistaZoom` (Canvas Compose) e `Regolazioni` + `PannelloRegolazioni`.
  - Zoom: pizzico, rotellina (desktop), doppio tocco/clic (adatta ↔ max(2.5, 1:1)), massimo = 4 pixel schermo per pixel originale. Spostamento solo se ingrandita (dopo touchSlop), limiti ai bordi.
  - **Dettaglio a piena risoluzione**: oltre la risoluzione dell'anteprima (2048 Android, 2400 PC) dopo 220 ms decodifica solo la zona visibile (+20%) con passo potenza di 2. Android: `BitmapRegionDecoder` su `ParcelFileDescriptor` (`VisoreZoom.kt`), PC: `ImageReader.sourceRegion` (`Immagini.zona`). `rettangoloGrezzo` converte le coordinate per gli orientamenti EXIF 1/3/6/8 (specchiati: niente dettaglio).
  - **Lente** ×2.5, raggio 80 dp: segue il dito (disegnata sopra il dito) o il mouse; con la lente il pager Android non scorre.
  - **Regolazioni** con matrice colore 4×5: auto-livelli per canale (percentili 0.4/99.6 sull'anteprima), esposizione 2^(1.5e), luminosità ±80, contrasto, saturazione (luminanza Rec.709), tonalità (rotazione), calore, effetti B/N, seppia, negativo. Solo visualizzazione; "Salva una copia modificata": Android in `Pictures/CercaFoto/` (max 4096 px, a righe di 64), PC accanto all'originale `…_modificata.jpg` (piena risoluzione, in place).
  - PC: tasti +/−/0, frecce = sposta se ingrandita (Pag su/giù cambia foto), L = lente, Esc a scalare.

### 9.2 Unione selettiva dei gruppi (Android e Windows)

`DialogoUnioneSelettiva`: usata da "Potrebbe essere anche…", "Unisci a…" e "nome già esistente". Parte con tutte le foto selezionate; se restano tutte → `unisci`, altrimenti `spostaFoto` delle sole scelte.

### 9.3 Video

**Modello dati (entrambe)**: ogni fotogramma chiave è una riga di `foto` con `video_id` e `tempo` (ms); tabella `video` (percorso/uri, durata, data, modificata, cartella, volume, fotogrammi). L'immagine del fotogramma è salvata dall'app (PC `%LOCALAPPDATA%\CercaFoto\fotogrammi\{id}.jpg`, Android `filesDir/fotogrammi/{-id}.jpg`, id = `idEsterno("v:<uri>#<ms>")` negativo). Così volti, persone, etichette, colori, OCR, CLIP e filtri funzionano senza codice nuovo.
- `FotoIdx`: `video`, `tempo`, `durata`, `momenti` (+ `isVideo`); sul PC `file` = fotogramma, `fileVideo` = filmato.
- I fotogrammi sono esclusi dalla scansione/rimozione delle foto (`video_id = 0`), dai conteggi delle foto e dallo scambio `.cercafoto` (export e import).
- `ricontaInterno`: un video conta una volta sola per persona.
- **Raggruppamento**: `comprimiVideo` (Stato/AppVM) → ogni video una voce, la prima in classifica, con `momenti` = tutti i suoi fotogrammi nella lista; `espandi` per le operazioni su persone (togli, sposta, nuova persona, unione selettiva) e per le esclusioni dei filtri.
- Ricerca: filtro Tutto/Foto/Video; le parole "video, filmato/i, filmino/i" (e "clip" sul PC) attivano il filtro video.

**Windows** (`Video.kt`):
- FFmpeg build **LGPL shared** di BtbN (`ffmpeg-master-latest-win64-lgpl-shared.zip`) scaricata in CI in `desktop/risorse/windows/ffmpeg` (`appResourcesRootDir`), letta da `compose.application.resources.dir`; in sviluppo usa l'ffmpeg nel PATH. La CI prova l'estrazione su un video generato.
- `info`: parsing di `ffmpeg -i` (durata, dimensioni con rotazione, `creation_time`).
- Estrazione: `-skip_frame nokey` (solo fotogrammi chiave, velocissimo) + `select='isnan(prev_selected_t)+gte(t-prev_selected_t,IV)+gt(scene,0.3)*gte(t-prev_selected_t,1.5)'`, scala max 1600, `showinfo` per i tempi, `-fps_mode vfr`. IV = max(3 s, durata/600). Se i fotogrammi chiave sono troppo radi rifà senza `-skip_frame`.
- Analisi parallela dei fotogrammi; scarta quelli con CLIP > 0.95 rispetto al precedente e stesso numero di volti.
- Riproduzione dal punto: VLC (`--start-time`) se installato, altrimenti `ffplay -ss`, altrimenti app predefinita.
- Visore: "Momenti del video" (clic = mostra quel momento), "▶ Riproduci da…"; copia modificata salvata come `video_mm-ss_modificata.jpg`.
- Filtri: pagina del filtro con video raggruppati; nel visore "È un esempio di… / Non è…" sul momento preciso (anche per le foto).
- Impostazione `video` (interruttore in Cartelle).
- **Verificato in jshell** con la build: video di prova (persona, caffè, gatto, persona) → 4 momenti, volto nello stesso gruppo della foto (persona = 2 elementi), "gatto"/"caffè"/"tazza" e descrizione libera trovano il secondo giusto.

**Android**:
- Permesso `READ_MEDIA_VIDEO` (chiesto all'avvio e con un pulsante nella scheda Indice per chi aggiorna).
- Sorgenti: `MediaStore.Video` + video nelle cartelle SAF (`leggiCartella(…, video)`).
- `MediaMetadataRetriever.getScaledFrameAtTime(OPTION_CLOSEST_SYNC, 1280)` ogni max(3 s, durata/300); ruota il fotogramma se il dispositivo non lo fa; data da `METADATA_KEY_DATE`; scarta i fotogrammi con impronta 16×16 quasi uguale e stesso numero di volti.
- **Solo in carica** (salvo "Anche senza caricatore"): se non è in carica il lavoro normale salta i video e accoda `indicizza_video` (stesso worker, `solo_video=true`, vincolo `setRequiresCharging`); un `Mutex` evita due analisi video insieme; il numero in attesa è nelle preferenze e mostrato in Indice.
- `caricaBitmap` gestisce gli uri `file://` dei fotogrammi (miniature volti, rianalisi HD).
- Visore: ▶ in alto e "Riproduci da…" → `LettoreVideo` (VideoView + MediaController, `seekTo(SEEK_CLOSEST)`); fila dei momenti (tocco = riproduci da lì, in giallo quelli trovati); "Apri video"; condividi manda il filmato; copia/sposta/elimina non si applicano ai video.

### 9.4 Indice portatile sul disco

- `IndicePortatile.kt` (condiviso): JSON gzip in `<radice>/.cercafoto/indice-<windows|android>.gz` (un file per tipo di dispositivo, così non si sovrascrivono). Contiene persone con nome (uid), e per ogni foto: percorso relativo (`/`), dimensione, data, etichette, colori, testo OCR, sorriso, volti (riquadro, vettore FaceNet **float16 base64**, età/sesso/capelli/abiti, uid persona, escluso, manuale), CLIP (solo PC, float16), etichette manuali/rimosse; video con i momenti (tempo + stessa analisi). Abbinamento per **percorso relativo + dimensione** (le date dei file cambiano tra file system). Il campo `dispositivo` (uuid nelle impostazioni) fa ignorare il proprio indice (es. dopo "rianalizza tutto").
- **Windows** (`Portatile.kt`): scrive l'indice per ogni cartella con «Indice sul disco» (di norma attivo fuori dal disco di sistema), a fine indicizzazione e solo se la `firma` (conteggi/somme/nomi/etichette) è cambiata; "Aggiorna ora" forza. Legge l'indice più recente di un altro dispositivo: `Portatile.usa` salva la foto senza modelli (solo lettura per miniatura e ritagli dei volti), riporta persone (`mappaPersone` per uid/nome) ed esclusioni; senza CLIP la passata CLIP successiva lo calcola. I video dell'indice del telefono non sono importati (il PC li analizza).
- **Android** (`Portatile.kt`): `leggiCartella` raccoglie gli indici trovati in qualunque `.cercafoto` della cartella scelta (prefisso = cartella che lo contiene) e le dimensioni dei file (`COLUMN_SIZE`); percorso relativo dal document id SAF. Foto abbinate → `db.salva` con il `Risultato` dell'indice (niente OCR per quelle del PC); video abbinati → `importaVideo` estrae solo le immagini ai tempi indicati, subito (senza attendere il caricatore). Scrive `indice-android.gz` (solo foto) con `DocumentsContract.createDocument` + `openOutputStream("wt")`; errore di scrittura → messaggio nella scheda Indice (cartella aggiunta senza permesso di scrittura).
- **Verificato in jshell** (due "PC" con home diverse sullo stesso disco di prova): A scrive 14 KB per 4 foto + 1 video; B riprende le 4 foto dall'indice in pochi secondi, con nome della persona, etichetta manuale, etichette oggetti e vettori CLIP. La parte Android non è ancora provata su un telefono.
