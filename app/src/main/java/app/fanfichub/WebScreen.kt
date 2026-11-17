package app.fanfichub

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Lecture sur le site, dans l'app, avec un navigateur qui se présente comme Chrome (session conservée). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(hub: Hub, url: String, title: String) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    BackHandler { if (canBack) web?.goBack() else hub.pop() }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { if (canBack) web?.goBack() else hub.pop() }
            Text(title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            CircleIcon(Icons.Default.Refresh) { web?.reload() }
            Spacer(Modifier.width(8.dp))
            CircleIcon(Icons.Default.Share) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: url))) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (progress in 1..99) Bar(progress / 100f, A1) }
        AndroidView(factory = { c ->
            WebView(c).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = BROWSER_UA
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.scheme.orEmpty().startsWith("http")
                    override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) { canBack = v.canGoBack() }
                }
                webChromeClient = object : WebChromeClient() { override fun onProgressChanged(v: WebView, p: Int) { progress = p } }
                loadUrl(url)
                web = this
            }
        }, modifier = Modifier.fillMaxSize().navigationBarsPadding())
    }
}
