package it.ponte

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.ponte.ui.ArchiveScreen
import it.ponte.ui.Canone
import it.ponte.ui.CanoneType
import it.ponte.ui.ClipScreen
import it.ponte.ui.DevicesScreen
import it.ponte.ui.AudioScreen
import it.ponte.ui.PonteTheme
import it.ponte.ui.SettingsScreen
import it.ponte.ui.Space

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Ponte.ensure(this).clipLog.onForeground()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PonteService.start(this)
        val router = Ponte.ensure(this)
        setContent { PonteTheme { PonteApp(router) } }
    }
}

private val TABS = listOf("Dispositivi", "Appunti", "Archivio", "Audio", "Impostazioni")

@Composable
private fun PonteApp(r: Router) {
    val c = Canone.colors
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (r.prefs.token.isEmpty()) tab = TABS.indexOf("Impostazioni")
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            0 -> DevicesScreen(r)
            1 -> ClipScreen(r)
            2 -> ArchiveScreen(r)
            3 -> AudioScreen(r)
            else -> SettingsScreen(r)
        }
    }

    // Il layout segue la finestra, non il dispositivo: barra laterale da 1024, barra in basso sotto.
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bgCanvas).statusBarsPadding()) {
        if (maxWidth >= 1024.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(240.dp).fillMaxHeight().padding(Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    Text("Ponte", style = CanoneType.title3, color = c.labelPrimary, modifier = Modifier.padding(Space.s3, Space.s4, Space.s3, Space.s5))
                    TABS.forEachIndexed { i, t -> TabItem(t, tab == i, Modifier.fillMaxWidth()) { tab = i } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                Row(
                    Modifier.fillMaxWidth().background(c.bgSurface).navigationBarsPadding().padding(horizontal = Space.s2, vertical = Space.s2),
                    horizontalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    TABS.forEachIndexed { i, t -> TabItem(t, tab == i, Modifier.weight(1f)) { tab = i } }
                }
            }
        }
    }
}

/** Voce della TabBar: solo testo, selezione con tinta e peso. */
@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Canone.colors
    Box(
        modifier.height(44.dp).clip(CircleShape).background(if (selected) c.bgSunken else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, maxLines = 1,
            style = if (selected) CanoneType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) else CanoneType.footnote,
            color = if (selected) c.labelPrimary else c.labelSecondary,
        )
    }
}
