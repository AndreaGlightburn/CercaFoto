# Prompt per ricreare "Cerca Foto" da zero

Copia tutto il testo qui sotto in una nuova sessione (Claude Code o simile, con accesso a GitHub).

---

Sei uno sviluppatore Kotlin esperto (Android e Compose Multiplatform Desktop) e di modelli di visione on-device. Lavora in autonomia, a passi iterativi, e consegna build funzionanti. Rispondi e scrivi codice, nomi di variabili e interfaccia **in italiano**. Le app sono firmate dall'autore **"Pony10"**.

## Obiettivo

Realizza **Cerca Foto**: due app collegate per cercare le proprie foto per persone, oggetti, scene, colori, testo e date. **Tutto deve funzionare in locale**: nessuna foto né dato esce dal dispositivo, e i modelli sono inclusi nei pacchetti.

1. **App Android nativa** (Kotlin + Jetpack Compose + Material 3 con colori dinamici), minSdk 31, targetSdk 35, solo arm64-v8a. Telefono di riferimento: Samsung Galaxy S24+, Android 17.
2. **App Windows** (Kotlin + Compose Multiplatform Desktop + ONNX Runtime), installer MSI per utente (senza diritti di amministratore) + ZIP portatile.
3. **Scambio** tra le due tramite un file `.cercafoto` con persone e correzioni, senza foto.

## Vincoli tecnici

- **Privacy verificabile**: l'APK **non deve avere il permesso INTERNET**. Rimuovilo nel manifest con `tools:node="remove"`. La GitHub Action deve far fallire la build se `aapt dump permissions` lo trova. Esclusione dal backup (`allowBackup=false`). Spiega nell'interfaccia come verificarlo (Impostazioni › App › Autorizzazioni).
- **Build in GitHub Actions**, non in locale.
  - Android: Gradle 8.10.2, JDK 17, AGP 8.7.3, Kotlin 2.0.21, release "ultima" con l'APK.
  - Windows: job su windows-latest, `createDistributable packageMsi`, release "windows" con MSI + ZIP.
- **Firma Android con chiave fissa**: creata dalla prima Action e salvata nel repo in base64, così le build successive si installano sopra senza perdere i dati.
- **Mai perdere il lavoro dell'utente** (nomi, etichette manuali, esclusioni, collegamenti a mano): migrazioni del DB, rianalisi che conservano le assegnazioni.
- **Indicizzazione incrementale**: le nuove capacità di analisi si aggiungono con un passaggio di "completamento" sulle foto già indicizzate.
- I file `Dizionario.kt`, `Ricerca.kt` e `ScambioFormato.kt` sono **identici** nelle due app (Kotlin puro, org.json).

## Modelli

- **Android**:
  - ML Kit bundled: `image-labeling` (soglia 0.6), `face-detection` (ACCURATE, classificazione sorriso, minFaceSize 0.05), `text-recognition` latino (disattivabile)
  - **FaceNet 512** TFLite: shubham0204/FaceRecognition_With_FaceNet_Android, Apache 2.0. Input 160×160 standardizzato per immagine, output normalizzato L2, solo volti ≥ 40 px.
  - **Età/sesso** TFLite: shubham0204/Age-Gender_Estimation_TF-Android, MIT. Età 200×200 RGB/255 ×116; sesso 128×128 [maschio, femmina]; ritaglio con margine 10%.
- **Windows**:
  - **UltraFace RFB-640** ONNX (MIT): input 640×480, (x−127)/128, soglia 0.7, NMS 0.3
  - **FaceNet ed età/sesso**: convertiti da TFLite con tf2onnx. Verifica che il coseno con il TFLite sia ≈ 1, perché i vettori di telefono e PC devono essere confrontabili.
  - **CLIP multilingue**: testo `sentence-transformers/clip-ViT-B-32-multilingual-v1` (mean pooling + Dense), immagine `openai/clip-vit-base-patch32`.
    - Esportali in ONNX int8 con uno script Python eseguito in una GitHub Action che pubblica una release "modelli-clip". Per l'immagine serve `attn_implementation="eager"`. Quantizza il testo anche su Gather; l'immagine solo MatMul/Gemm.
    - Implementa in Kotlin il tokenizzatore WordPiece cased e la pre-elaborazione (riduzione a passi, bicubico lato corto 224, ritaglio centrale, normalizzazione CLIP). Verificali contro i riferimenti prodotti dallo script.
- Includi un file **NOTICE** con le licenze.

## Funzioni Android

**Indicizzazione**
- WorkManager in primo piano (dataSync), con notifica di avanzamento.
- Sorgenti: galleria MediaStore `VOLUME_EXTERNAL` (memoria + SD) e cartelle o unità esterne scelte col selettore SAF.
- Per ogni foto: etichette, volti con vettore, sorriso, età/sesso, colore dei capelli (zona sopra il volto) e degli abiti (zona sotto), colori dominanti (classificazione HSV in 12 colori), testo OCR.
- Le foto di unità scollegate o di una SD tolta restano nell'indice.

**Persone**
- Raggruppamento per coseno: soglia 0.5 regolabile 0.35–0.75. Assegnazione incrementale dai volti più grandi, poi un passaggio di affinamento.
- I gruppi con nome sono fissi e attirano i volti nuovi.
- Pagina persona: rinomina (proponi di unire se il nome esiste già), unisci (ricerca + suggerimenti per somiglianza), nascondi, "Potrebbe essere anche…" (gruppi senza nome ≥ 45% con anteprima), selezione multipla per togliere o spostare le foto in un'altra persona.
- Lista persone: ricerca per nome, filtri con nome/senza nome, filtri per sesso/età/capelli sul profilo del gruppo.
- "Riorganizza volti senza nome": ricalcolo veloce con severità scelta, oppure rianalisi a 2560 px con volti più piccoli, conservando nomi ed esclusioni.
- **Collegamento manuale foto↔persona** anche senza volto visibile (dal visore o da una selezione multipla), solo verso persone con nome.
- **Volti nel visore** con il loro gruppo: apri gruppo, sposta in un'altra persona, crea persona, togli dal gruppo.

**Ricerca in italiano** (AND sulle parole)
- Nomi delle persone (anche composti), ~440 etichette tradotte con singolare/plurale, categorie (animali, cani, gatti, cibo, mare, montagna, neve, città, feste, matrimonio, documenti, sport…).
- Colori, anni, mesi e stagioni, persone/gruppi/coppia/selfie/sorrisi/screenshot/testo.
- Vincoli sul volto riferiti alla stessa persona: "donna anziana capelli bianchi", "ragazzo biondo maglia rossa". Età e sesso solo per volti con area ≥ 0.003.
- Etichette manuali, testo OCR, nome file e album.
- Pannello filtri: numero di persone, sesso, fascia d'età, capelli, colore abiti, cartella.

**Visore**
- Swipe tra le foto.
- Dettagli: cartella completa, file, dimensione, risoluzione, fotocamera, GPS con pulsante mappa (permesso ACCESS_MEDIA_LOCATION).
- Stime per volto, etichette, colori, testo letto.
- Modifica etichette: togli quelle automatiche, aggiungi le tue a testo libero; conservate nelle rianalisi.

**File**
- Copia, sposta ed elimina (cestino o eliminazione definitiva) su una foto o su una selezione multipla, con i consensi di sistema MediaStore.
- Spostare nella galleria cambia solo `RELATIVE_PATH`, quindi l'indice resta valido. Una copia duplica la voce dell'indice.
- Destinazioni: cartelle della galleria, nuova cartella, unità SAF.
- Condividi, apri in Galleria.

**Unità USB-C**
- Rileva le unità rimovibili collegate non indicizzate da MediaStore e proponi "Aggiungi" con `StorageVolume.createOpenDocumentTreeIntent()`.
- Mostra il nome leggibile dell'unità.
- Aggiorna l'indice su MEDIA_MOUNTED.

**Scheda Indice**: avanzamento, ferma/aggiorna, OCR sì/no, severità, cartelle e unità, scambio con Windows, privacy, rianalizza tutto.

## Funzioni Windows

- **Cartelle** (JFileChooser): scansione parallela (metà dei core); miniature e ritagli dei volti salvati in `%LOCALAPPDATA%\CercaFoto`; date e GPS dall'EXIF (metadata-extractor); JPEG robusti (TwelveMonkeys).
- **Ricerca**: due modalità.
  - **Parole chiave**: come Android. Le etichette degli oggetti vengono da CLIP zero-shot sulle stesse ~440 etichette, con prompt "a photo of …".
  - **Descrizione libera**: CLIP, soglia 0.215 e vicinanza alla migliore.
- Filtri, selezione multipla, visore con frecce ed Esc ("Apri", "Mostra in Esplora risorse").
- Persone come su Android.
- **Barre di scorrimento** e **zoom delle anteprime** (cursore + Ctrl+rotellina, ricordato).
- **Filtri personalizzati** (scheda Filtri):
  - **da esempi**: regressione logistica sui vettori CLIP, positivi scelti, negativi esclusi a mano + 600 foto casuali, sensibilità iniziale 0.28
  - **da descrizione**: frasi
  - correzioni "Non c'entrano / Esempi perfetti" con riaddestramento
  - uso come pulsanti ★ o come parola nella ricerca
  - esporta/importa `.cercafiltro` (JSON con i 512 pesi, senza foto)

## Visore avanzato e video (entrambe le app)

- **Visore**: zoom (pizzico, rotellina, doppio tocco), spostamento nella foto ingrandita con decodifica a piena risoluzione della sola zona visibile (rispettando l'orientamento EXIF), lente d'ingrandimento che segue dito o mouse, regolazioni di luce e colore con matrice colore (auto, luminosità, esposizione, contrasto, saturazione, calore, tonalità, B/N, seppia, negativo) e "salva una copia modificata". Codice condiviso in `Visione.kt`.
- **Unione selettiva dei gruppi**: quando unisci un gruppo scegli quali foto portare; le altre restano dove sono.
- **Video**: ogni fotogramma chiave diventa una riga di `foto` con `video_id` e `tempo`, così tutta l'analisi delle foto vale anche per i video.
  - Windows: FFmpeg LGPL incluso (CI), solo fotogrammi chiave + cambi di scena, scarto dei quasi-duplicati via CLIP, riproduzione dal punto con VLC/ffplay.
  - Android: `MediaMetadataRetriever` ogni pochi secondi, analisi **solo in carica** con un lavoro WorkManager vincolato al caricatore, lettore interno (VideoView) che parte dal minuto.
  - Risultati raggruppati: un video = una voce con ▶ minuto e numero di momenti; filtro Tutto/Foto/Video; nelle persone un video conta una volta; i filtri personalizzati imparano anche dal momento preciso.

## Scambio `.cercafoto`

JSON gzip con:
- **persone con nome**: uid stabile, nome, fino a 24 campioni di vettori volto
- **foto con correzioni**: nome, dimensione, data, eventuale SHA già noto, etichette manuali/rimosse, volti con riquadro normalizzato e persona/escluso/manuale

All'importazione:
1. Abbina le persone per uid o nome e **salva i campioni come riferimenti permanenti**, che attirano anche i volti analizzati dopo.
2. Abbina le foto per nome → dimensione → data ±2 s → SHA.
3. Abbina i volti per vicinanza del centro (non per sovrapposizione esatta: i rilevatori sono diversi).
4. Riconosci per somiglianza (soglia −0.05) e ricalcola i gruppi.

L'esportazione deve essere rapida (niente lettura dei file per l'SHA) e confermare "File pronto". Un file vuoto dà un errore chiaro.

## Modo di lavorare

- Usa task list, esegui senza chiedere conferma per ogni passo e consegna l'APK / il MSI (dentro uno ZIP) a ogni passo importante.
- **Verifica davvero**:
  - confronta i modelli convertiti con gli originali
  - prova il motore desktop in jshell usando i jar della build
  - taratura delle soglie su dataset pubblici (es. le foto campione di ImageNet su GitHub)
- Dichiara sempre i limiti: le stime di età, sesso e capelli sono approssimative, i dettagli piccoli sono difficili per CLIP.
