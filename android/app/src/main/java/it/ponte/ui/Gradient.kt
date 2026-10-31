package it.ponte.ui

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Il gradiente ASCII di Ponte (assets/gradient.html, lo stesso dell'app Windows), dietro la testata e l'onboarding.
 * Si ferma quando l'app va in background, così non consuma batteria.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GradientView(modifier: Modifier, shape: String = "hero", cell: Int = 18, intro: Boolean = true, touch: Boolean = true, ascii: Int = -1, bg: androidx.compose.ui.graphics.Color? = null) {
    val dark = isSystemInDarkTheme()
    val web = remember { mutableStateOf<WebView?>(null) }
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                if (!touch) setOnTouchListener { _, _ -> false }
                val q = "shape=$shape&cell=$cell&intro=${if (intro) 1 else 0}&light=${if (dark) 0 else 1}&touch=${if (touch) 1 else 0}${if (ascii >= 0) "&ascii=$ascii" else ""}${if (bg != null) "&bg=%06X".format(bg.toArgb() and 0xFFFFFF) else ""}"
                settings.domStorageEnabled = true
                // Le risorse (pagina e font) arrivano da un indirizzo https finto servito dagli asset: contesto sicuro, il font si carica.
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val u = request.url
                        if (u.host != "ponte.app") return null
                        val name = u.path.orEmpty().trimStart('/').ifEmpty { "gradient.html" }
                        return try {
                            val mime = when (name.substringAfterLast('.')) { "html" -> "text/html"; "ttf" -> "font/ttf"; "js" -> "text/javascript"; else -> "application/octet-stream" }
                            android.webkit.WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ctx.assets.open(name))
                        } catch (_: Exception) {
                            android.webkit.WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.w("PonteGL", "${m.message()} (${m.lineNumber()})")
                        return true
                    }
                }
                loadUrl("https://ponte.app/gradient.html?$q")
                web.value = this
            }
        },
        onRelease = { it.destroy() },
    )

    LaunchedEffect(dark, bg) {
        val b = if (bg != null) ", bg: '%06X'".format(bg.toArgb() and 0xFFFFFF) else ""
        web.value?.evaluateJavascript("window.ponteSet && ponteSet({ lightMode: ${!dark}$b })", null)
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> web.value?.onPause()
                Lifecycle.Event.ON_RESUME -> web.value?.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}
