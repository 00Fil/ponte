# Protocollo Relay, versione 1

Un solo protocollo, tre implementazioni: server Node (`server/`), app Android (`android/`), app Windows (`windows/`). Se cambi qualcosa qui, cambialo in tutte e tre.

## Trasporti

Ogni dispositivo usa **entrambi** i trasporti insieme. Un messaggio che arriva da due strade viene scartato dal secondo ricevimento (dedupe per `id`).

**Hub (VPS)**: WebSocket su `wss://HOST/ws`. Autenticazione con l'header `Authorization: Bearer TOKEN` oppure con `?token=TOKEN`. Messaggi di testo = JSON; messaggi binari = audio (vedi sotto).

**LAN diretta**: ogni dispositivo ascolta su TCP `47800` e annuncia sé stesso in UDP broadcast sulla porta `47801` ogni 5 secondi:

```json
{"p":"ponte","id":"<deviceId>","name":"Fisso","port":47800}
```

Quando vede un dispositivo non ancora collegato, si collega solo se il proprio `id` è lessicograficamente minore di quello dell'altro (così nasce un solo collegamento per coppia). Frame TCP: `[u32 big-endian lunghezza][u8 tipo][payload]`, con tipo `0` = JSON UTF-8 e tipo `1` = audio. La lunghezza conta tipo + payload. Massimo 1 MiB per frame.

Sulla LAN il primo frame è `hello` con il campo `proof = hex(HMAC-SHA256(chiave = TOKEN, messaggio = "ponte-lan|" + id))`. Chi riceve lo verifica con l'`id` dichiarato e chiude se non torna. La LAN non è cifrata: i dati viaggiano in chiaro sulla rete di casa.

## Busta dei messaggi

```json
{"t":"clip","id":"<uuid>","from":"<deviceId>","ts":1761866400000, ...}
```

`id` è obbligatorio e unico, `from` lo imposta l'hub (i client lo mettono comunque), `ts` sono millisecondi Unix. Se c'è `to` (un deviceId) il messaggio è per quel dispositivo soltanto; gli altri lo ignorano.

## Messaggi

| `t` | Campi | Note |
|---|---|---|
| `hello` | `name`, `platform` (`windows`/`android`), `caps[]`, `proof?` | Primo messaggio su ogni collegamento. `caps`: `clip`, `files`, `audio-src`, `audio-sink`, `notif-src`, `notif-sink` |
| `devices` | `list[{id,name,platform,caps}]` | Solo hub → client, a ogni cambio |
| `clip` | `text` | Appunti di testo. Massimo 1 MiB |
| `file.begin` | `fid`, `name`, `kind` (`screenshot`/`file`), `mime`, `size` | `fid`: 8–64 caratteri `[A-Za-z0-9_-]` |
| `file.chunk` | `fid`, `seq`, `data` (base64) | Blocchi da 48 KiB, in ordine |
| `file.end` | `fid` | |
| `notif` | `key`, `app`, `title`, `text` | Notifiche del telefono verso il PC |
| `audio.route` | `to` (la sorgente), `sink` (deviceId o `null`) | Chiede alla sorgente di mandare l'audio a `sink`, o di fermarsi |
| `audio.start` | `sid` (u32), `to`, `rate` 48000, `ch` 2 | La sorgente annuncia il flusso al ricevitore |
| `audio.stop` | `sid`, `to` | |
| `bt.claim` | `hp` (indirizzo Bluetooth, maiuscolo, `AA:BB:CC:DD:EE:FF`), `prio` | Il mittente chiede un posto sulle cuffie (hanno 2 posti). Con un posto libero nessuno scollega niente. Se i posti sono pieni lascia le cuffie chi vale meno tra i dispositivi collegati: non bloccato, poi non in riproduzione, poi usato meno di recente, poi id; tutti applicano la stessa regola sugli stessi dati, quindi se ne va uno solo. |
| `bt.state` | `hp`, `on` (collegate a questo dispositivo), `play` (sta suonando), `ago` (ms dall'ultimo uso) | Annuncia chi occupa i posti. Inviato a ogni cambio e ogni 45 s; una voce senza aggiornamenti per 150 s decade. |
| `focus` | `ago` (ms dall'ultimo input) | Il mittente è il dispositivo in uso. Chi riceve calcola l'ora dell'input come arrivo meno `ago` (senza orologi da sincronizzare): in uso è chi ha l'input più recente, e nessuno se sono passati più di 10 minuti. Inviato al passaggio di mano o dopo 30 s di inattività, al più ogni 15 s. |
| `media.take` | — | Su questo dispositivo è appena partito un contenuto multimediale: gli altri che stanno suonando si mettono in pausa (salvo blocco, audio ricevuto da Relay o partenza simultanea, dove vince l'id minore). Un dispositivo non annuncia nulla per 5 s dopo una pausa ricevuta. |
| `audio.lock` | `by` (id o stringa vuota) | Blocco del passaggio automatico: le cuffie restano a `by` finché non si sblocca. |

## Audio

PCM signed 16 bit little-endian, 48000 Hz, stereo, a pacchetti da 20 ms (3840 byte). Payload binario: `[u32 big-endian sid][PCM]`. Sulla LAN va direttamente alla sorgente → ricevitore se c'è il collegamento; altrimenti passa dall'hub, che ricorda `sid → destinatario` dal messaggio `audio.start`. Il ricevitore tiene un buffer di circa 100 ms e scarta i pacchetti vecchi.

## Ricevere i file

Con due strade in parallelo, `file.begin`, `file.chunk` e `file.end` dello stesso file possono arrivare in ordine diverso. Chi riceve deve quindi:

- scrivere ogni blocco alla posizione `seq × 49152` (i blocchi sono tutti da 49152 byte tranne l'ultimo);
- tenere da parte, per poco, i blocchi e il `file.end` arrivati prima del `file.begin` (al massimo 64 blocchi per file e 16 file);
- completare il file solo quando ha visto `file.end` **e** i byte ricevuti sono pari a `size`; se non tornano, scartarlo.

Un `fid` già presente nell'archivio viene ignorato.

## HTTP dell'hub

Tutte con `Authorization: Bearer TOKEN`, salvo `/healthz` e `/archivio` (la pagina; i dati li chiede con il token passato nel frammento `#token=`).

- `GET /healthz` → `ok`
- `GET /api/files?since=<ms>` → `[{fid,name,kind,mime,size,ts,from}]`, per recuperare ciò che si è perso da offline
- `GET /api/files/<fid>` → il file
- `GET /archivio` → galleria web di screenshot e file


## Indirizzi diretti

`hello` porta anche `addrs` (fino a 8 IPv4, prima quelli della rete privata 100.64.0.0/10) e `port`. Il server li gira nella lista `devices`.
Ogni dispositivo li salva e si collega via TCP diretto (stesse regole della rete locale: si collega chi ha l'id minore, `hello` con `proof`).
Con Headscale/Tailscale il collegamento è diretto e cifrato anche senza Wi-Fi in comune.

## Cronologia degli appunti

`GET /api/clips?since=<ms>&limit=<n>` (Bearer): tutti gli appunti passati dal server, dal più vecchio.

## Riserve senza rete

Quando non c'è né server né rete locale:
- **Bluetooth RFCOMM**, UUID `7f2a0c1e-6b1d-4b8e-9a51-50e7e0a1c0de`, stesse trame del TCP. Il PC espone il servizio, Android chiama i dispositivi associati (computer e telefoni). Tra due Android resta il collegamento aperto da chi ha l'id minore. Solo JSON: l'audio non passa.
- **Wi-Fi Direct** tra Android: nome del gruppo `DIRECT-Pn-` + i primi 8 caratteri esadecimali di SHA-256(`p2p-name|` + token), password i primi 20 di SHA-256(`p2p-pass|` + token). Chi entra si collega in TCP al capo gruppo (porta 47800).

## File negli appunti

`file.begin` può portare `clip: true`: il file è stato copiato negli appunti del mittente (file da Esplora risorse o dalla galleria, immagini, ritagli). Chi lo riceve per intero, in diretta, lo mette anche negli appunti
(Windows: file e, se è un'immagine, anche bitmap; Android: URI del FileProvider). Il recupero da offline non tocca gli appunti. Limite 50 MB. Si ignora il cambio di appunti che il dispositivo stesso ha appena provocato.
