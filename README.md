# Ponte

Appunti, screenshot, file, audio e notifiche condivisi tra PC Windows e Android.
Due strade insieme: rete locale diretta e server (per quando sei fuori casa). Spec in `PROTOCOLLO.md`.

## Stato onesto
- `server/`: testato (7 test passano). Il build Docker non è stato provato.
- `android/` e `windows/`: scritti ma **mai compilati**. Aspettati qualche errore di compilazione o di versione al primo build.

## 1. Server (Dokploy)
1. Crea un'applicazione da questa cartella `server/` (Dockerfile o docker-compose).
2. Variabile obbligatoria: `PONTE_TOKEN` (almeno 16 caratteri). Opzionali: `PORT`, `PONTE_DATA`, `PONTE_MAX_FILE_MB`.
3. Assegna un dominio con HTTPS alla porta 8080 e un volume su `/data`.
4. Archivio web: `https://DOMINIO/archivio#token=IL_TUO_TOKEN`.

Prova locale: `cd server && npm install && PONTE_TOKEN=0123456789abcdef npm start`.

## 2. Windows
Visual Studio 2022 con "Sviluppo desktop .NET" e Windows App SDK, apri `windows/Ponte.csproj` e avvia. Oppure `dotnet build -c Release`.
In Strumento di cattura attiva il salvataggio automatico degli screenshot. Alla prima esecuzione consenti il firewall sulle reti private.

## 3. Android
Android Studio: apri `android/`, sincronizza Gradle (nessun wrapper incluso; potrebbe servire aggiornare le versioni), poi Run.
Concedi notifiche, accesso alle immagini e l'accesso alle notifiche (per inoltrarle al PC).

### Appunti in tempo reale su Android
Android non avvisa le app in background quando copi. Ponte se ne accorge leggendo i log di sistema e legge gli appunti con una finestra invisibile da 1 pixel. Servono, una volta sola:
1. Impostazioni di Ponte, "Mostra sopra altre app".
2. Dal PC con il debug USB attivo: `adb shell pm grant it.ponte android.permission.READ_LOGS` (con il root basta il pulsante nelle impostazioni di Ponte).

## In tutti i dispositivi
Stesso indirizzo del server e **stesso token** in Impostazioni.

## Limiti noti
- Android non lascia leggere gli appunti in background: invia dal riquadro rapido, dalla notifica "Invia appunti" o da Condividi. Ricevere funziona sempre.
- Il traffico in rete locale non è cifrato (il server sì, via HTTPS).
- L'eliminazione non si propaga agli altri dispositivi.
- Audio: se la rete locale cade a metà, va riavviato l'ascolto. Ritardo circa 100 ms.
- Il token su Android è salvato in chiaro nelle preferenze.
- Le cuffie restano collegate al dispositivo che le riceve: Ponte cambia da dove arriva l'audio del PC, non l'accoppiamento Bluetooth.
