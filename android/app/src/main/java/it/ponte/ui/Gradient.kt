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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Il gradiente ASCII di Ponte (assets/gradient.html, lo stesso dell'app Windows), dietro la testata e l'onboarding.
 * Si ferma quando l'app va in background, così non consuma batteria.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GradientView(modifier: Modifier, shape: String = "horizon", cell: Int = 18, intro: Boolean = true, touch: Boolean = true) {
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
                val q = "shape=$shape&cell=$cell&intro=${if (intro) 1 else 0}&light=${if (dark) 0 else 1}&touch=${if (touch) 1 else 0}"
                loadUrl("file:///android_asset/gradient.html?$q")
                web.value = this
            }
        },
        onRelease = { it.destroy() },
    )

    LaunchedEffect(dark) {
        web.value?.evaluateJavascript("window.ponteSet && ponteSet({ lightMode: ${!dark} })", null)
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
