package it.ponte.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import it.ponte.Router

/** Pagina a tutto schermo: spazio sul server, backup completo, verifica e ripristino. */
@Composable
fun BackupScreen(r: Router, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = Canone.colors
    val b = r.backup
    val st by b.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onClose)

    LaunchedEffect(Unit) { b.refreshStorage(); b.prepare() }
    LaunchedEffect(tick) { if (tick > 0) b.prepare() }

    val askRuntime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun fmt(n: Long) = Formatter.formatFileSize(ctx, n)
    fun day(ms: Long) = DateFormat.format("d MMM yyyy, HH:mm", ms).toString()

    @Composable
    fun Perm(title: String, note: String, granted: Boolean, action: () -> Unit) {
        CRow(title, note) { GrantMark(granted, action) }
    }

    val running = b.busy
    val storage = st.storage
    val plan = st.plan

    Box(Modifier.fillMaxSize().background(c.bgCanvas).statusBarsPadding().navigationBarsPadding()) {
        ScreenList("Backup") {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Backup del telefono", style = CanoneType.title1, color = c.labelPrimary)
                    CButton("Chiudi", onClose, kind = ButtonKind.Tertiary)
                }
            }
            item {
                CCard {
                    SectionTitle("Spazio sul server")
                    if (storage != null) {
                        val usedFrac = (storage.total - storage.free).toFloat() / storage.total.coerceAtLeast(1)
                        GlassProgress(usedFrac)
                        CRow("${fmt(storage.free)} liberi su ${fmt(storage.total)}", "Backup di questo telefono sul server: ${fmt(storage.deviceBytes)} (${storage.deviceFiles} file)")
                    } else CText(if (st.storageError.isNotEmpty()) "Non riesco a leggere lo spazio: ${st.storageError}" else "Controllo il server…")
                    CButton("Aggiorna", { r.scope.launch { b.refreshStorage() } }, kind = ButtonKind.Tertiary)
                }
            }
            item {
                @Suppress("UNUSED_EXPRESSION") tick
                CCard {
                    SectionTitle("Cosa viene copiato")
                    Perm("Tutti i file", "Foto, video, documenti, download: tutta la memoria tranne i dati delle app", b.hasAllFiles()) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                    }
                    CDivider()
                    Perm("Contatti", "Salvati come vCard, il formato standard di Android", b.hasContacts) { askRuntime.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                    CDivider()
                    Perm("SMS", "Formato compatibile con «SMS Backup & Restore»", b.hasSms) { askRuntime.launch(arrayOf(Manifest.permission.READ_SMS)) }
                    CDivider()
                    Perm("Registro chiamate", "Stesso formato di «SMS Backup & Restore»", b.hasCalls) { askRuntime.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) }
                    CDivider()
                    CRow("App installate", "Elenco di tutte; gli APK solo di quelle non prese da Play Store")
                    if (!b.hasAllFiles()) CButton("Aggiorna i permessi", { tick++ }, kind = ButtonKind.Tertiary)
                }
            }
            item {
                CCard {
                    SectionTitle("Backup")
                    if (plan != null) {
                        CRow("${plan.files} file sul telefono", "Da copiare: ${plan.toSend} (${fmt(plan.toSendBytes)}). Quelli già sul server vengono saltati.")
                        if (storage != null && plan.toSendBytes + storage.reserve > storage.free) {
                            CText("Sul server non c'è abbastanza posto: servono ${fmt(plan.toSendBytes)} più ${fmt(storage.reserve)} di riserva, ne restano ${fmt(storage.free)}.")
                        }
                    }
                    if (st.lastBackup > 0) CText("Ultimo backup: ${day(st.lastBackup)}", tertiary = true)
                    if (running) {
                        GlassProgress(if (st.total > 0) st.done.toFloat() / st.total else -1f)
                        CText(buildString {
                            append(st.step)
                            if (st.total > 0) append("\n${st.done}/${st.total} file · ${fmt(st.bytes)}")
                        })
                        CButton("Ferma", { b.cancel() }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive)
                    } else {
                        if (st.step.isNotEmpty()) CText(st.step)
                        CButton("Esegui il backup completo", { b.start() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary,
                            enabled = b.hasAllFiles() && (storage == null || plan == null || plan.toSendBytes + storage.reserve <= storage.free))
                        CButton("Verifica il backup", { b.verify() }, Modifier.fillMaxWidth())
                    }
                    if (st.failed.isNotEmpty()) {
                        CDivider()
                        CRow("${st.failed.size} elementi non copiati", "Riprova il backup: riparte da quelli mancanti")
                        st.failed.take(5).forEach { CText(it, tertiary = true) }
                    }
                }
            }
            val rep = st.report
            if (rep != null) item {
                CCard {
                    SectionTitle(if (rep.ok) "Verifica superata" else "Verifica non superata")
                    rep.lines.forEach { CText(it) }
                    CText(if (rep.ok) "Ogni file è identico sul server (dimensione e data) e ogni caricamento è stato confermato dal controllo SHA-256. Resta da fare la lista qui sotto." else "Non sbloccare il bootloader: correggi quanto indicato e rifai backup e verifica.", tertiary = true)
                }
            }
            item {
                CCard {
                    SectionTitle("Prima di sbloccare il bootloader")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        CText(if (st.lastVerifiedOk > 0) "Backup verificato il ${day(st.lastVerifiedOk)}" else "Backup non ancora verificato", tertiary = true)
                    }
                    CText("Lo sblocco cancella TUTTO il telefono. Relay copia ciò che Android lascia leggere; i dati interni delle app (chat, database, accessi) non si possono copiare senza root. Per quelli fai, nell'ordine:")
                    listOf(
                        "Impostazioni → Google → Backup: «Esegui backup adesso» (app, impostazioni, rete Wi-Fi, foto se vuoi) e controlla che sia riuscito",
                        "WhatsApp: Impostazioni → Chat → Backup delle chat, poi tieni lo stesso numero. Telegram, Signal (backup locale) ecc. vanno fatti dalle loro app",
                        "App di autenticazione (2FA): esporta o aggiungi un secondo dispositivo PRIMA, altrimenti perdi gli accessi",
                        "Chiavi di accesso/passkey e password: assicurati che siano sul tuo account Google o gestore password",
                        "eSIM: se usi un'eSIM, chiedi al gestore come trasferirla; la SIM fisica non cambia",
                        "Bluetooth: gli abbinamenti si rifanno, la lista dei dispositivi non si salva",
                        "Magisk: scarica ora l'APK e l'immagine boot/init_boot su PC, prima di cancellare",
                    ).forEach { CText("• $it") }
                }
            }
            item {
                CCard {
                    SectionTitle("Dopo la reinstallazione")
                    CText("1. Ripristina da Google durante la prima configurazione.\n2. Installa Relay, inserisci server e token.\n3. Concedi «Tutti i file» e premi Ripristina.")
                    CButton("Ripristina dal server", { b.restore() }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary, enabled = !running && b.hasAllFiles())
                    CText("I file tornano dove erano. Cartella ${b.restoreFolder()}: contatti.vcf (apri con Contatti → Importa), sms.xml e chiamate.xml (apri con «SMS Backup & Restore» → Ripristina), apk/ (le app non di Play Store: installale da qui), app-da-reinstallare.txt (le altre, da Play Store).", tertiary = true)
                }
            }
        }
    }
}

