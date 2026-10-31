# Relay

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
Installazione: scarica `Ponte-Setup.exe` dalla release "latest" ed eseguilo. Si apre l'installazione di Relay (niente amministratore): lo mette in `%LOCALAPPDATA%\Programs\Relay`, nel menu Start, se vuoi all'avvio e sul desktop, e in Impostazioni > App con la sua disinstallazione. SmartScreen ("PC protetto da Windows") compare solo sui file scaricati dal browser, che Windows marca con "Mark of the Web". Per non vederlo, scarica l'installer dal tuo server con PowerShell (non lo marca): `iwr https://IL-TUO-SERVER/api/update/setup -Headers @{Authorization='Bearer IL_TOKEN'} -OutFile $env:TEMP\Ponte-Setup.exe; Start-Process $env:TEMP\Ponte-Setup.exe`. Un file già scaricato si sblocca con `Unblock-File` o da Proprietà > "Annulla blocco". Gli aggiornamenti automatici non lo mostrano. Poi si aggiorna da solo. Lo zip resta per l'uso portatile (`Ponte.exe --portable`).

Da sorgente: Visual Studio 2022 con "Sviluppo desktop .NET" e Windows App SDK, apri `windows/Ponte.csproj` e avvia. Oppure `dotnet build -c Release`.
In Strumento di cattura attiva il salvataggio automatico degli screenshot. Alla prima esecuzione consenti il firewall sulle reti private.

## 3. Android
Android Studio: apri `android/`, sincronizza Gradle (nessun wrapper incluso; potrebbe servire aggiornare le versioni), poi Run.
Concedi notifiche, accesso alle immagini e l'accesso alle notifiche (per inoltrarle al PC).

### Appunti in tempo reale su Android
Android non avvisa le app in background quando copi. Relay se ne accorge leggendo i log di sistema e legge gli appunti con una finestra invisibile da 1 pixel. Servono, una volta sola:
1. Impostazioni di Relay, "Mostra sopra altre app".
2. Dal PC con il debug USB attivo: `adb shell pm grant it.ponte.app android.permission.READ_LOGS` (con il root basta il pulsante nelle impostazioni di Relay).

## File e immagini negli appunti
Copia un file (Esplora risorse, galleria), un'immagine o un ritaglio su un dispositivo e incollalo sull'altro: Relay lo trasferisce e lo mette negli appunti dell'altro dispositivo, e resta anche nell'archivio. Fino a 50 MB.
Un ritaglio fatto con Win+Maiusc+S viene mandato una sola volta anche se lo Strumento di cattura lo salva su disco.

## Rete privata (Headscale)
Collegamento diretto e cifrato tra i tuoi dispositivi anche senza Wi-Fi in comune.
1. Dokploy: nuovo Compose da questo repo, percorso `./headscale/docker-compose.yml`. Variabile `HEADSCALE_URL=https://rete.tuodominio.it`. Dominio sul servizio `headscale`, porta 8080, HTTPS.
2. Nel terminale del container: `headscale users create io` e poi `headscale preauthkeys create --user io --reusable --expiration 24h` (copia la chiave).
3. Su ogni dispositivo installa Tailscale:
   - Windows: `tailscale up --login-server https://rete.tuodominio.it --authkey LA_CHIAVE`
   - Android: app Tailscale, menu in alto, "Change server" (o tocca 5 volte la versione nelle impostazioni), inserisci l'indirizzo, poi accedi con la chiave.
4. Fatto: Relay trova da solo gli indirizzi 100.x tramite il server e ci si collega direttamente. In Dispositivi compare "Diretto".
Su Windows consenti Relay anche sulle reti pubbliche quando il firewall lo chiede, altrimenti gli altri non possono collegarsi a lui.

## Passaggio automatico dell'audio
In Audio: segna le cuffie, scegli le "Cuffie preferite qui" e cosa fare se non ci sono (casse o muto). Le cuffie vanno al dispositivo dove fai partire l'audio, se è quello che stai usando (ultimo tocco, mouse o tastiera). "Blocca qui" (anche dal riquadro rapido su Android o dal menu nell'area di notifica su Windows) le tiene ferme.
Su Android spostare le cuffie richiede il root: Relay lo chiede a Magisk/KernelSU la prima volta. Serve anche "Mostra sopra altre app" per capire quando usi il dispositivo.

## Senza internet né Wi-Fi in comune
- **Stesso Wi-Fi o hotspot del telefono**: funziona tutto, anche senza internet.
- **Bluetooth** (telefono e tablet con il PC, e tra loro): basta che siano associati. Appunti, notifiche, cuffie e file piccoli. Su Android concedi "Bluetooth" nelle impostazioni di Ponte.
- **Wi-Fi Direct** tra telefono e tablet: anche file grandi e audio, a schermo acceso. Concedi "Dispositivi Wi-Fi vicini".

## In tutti i dispositivi
Stesso indirizzo del server e **stesso token** in Impostazioni.

## Limiti noti
- Android non lascia leggere gli appunti in background: invia dal riquadro rapido, dalla notifica "Invia appunti" o da Condividi. Ricevere funziona sempre.
- Il traffico in rete locale non è cifrato (il server sì, via HTTPS).
- L'eliminazione non si propaga agli altri dispositivi.
- Audio: se la rete locale cade a metà, va riavviato l'ascolto. Ritardo circa 100 ms.
- Il token su Android è salvato in chiaro nelle preferenze.
- Le cuffie restano collegate al dispositivo che le riceve: Relay cambia da dove arriva l'audio del PC, non l'accoppiamento Bluetooth.
