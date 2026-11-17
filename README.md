# Cerca Foto

App Android (Kotlin + Compose) per cercare le foto del telefono per contenuto, persone e testo.
**Tutto in locale**: l'APK non ha il permesso `INTERNET` (verificato a ogni build dalla GitHub Action).

- Riconosce ~440 categorie (ML Kit Image Labeling), con ricerca in italiano: "cane al mare", "gruppi 2024", "selfie agosto"
- Trova i volti (ML Kit Face Detection) e li raggruppa per persona (FaceNet 512, TFLite); i gruppi si possono nominare, unire, correggere
- Legge il testo nelle foto (ML Kit Text Recognition) per cercare scontrini, documenti, cartelli
- Indicizzazione in background (WorkManager, servizio in primo piano), incrementale

L'APK firmato è nella release **ultima** del repository, generata a ogni push su `main`.
