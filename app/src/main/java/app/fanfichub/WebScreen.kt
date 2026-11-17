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

/** Masque les encarts d'inscription, recommandations, pieds de page et bannières (relancé quand la page change). */
private const val CLEAN_JS = """(function(){
var P=['Rejoins la plus grande','Join the largest','Cela pourrait aussi te plaire','You may also like','Télécharger l','Download the','Essayer Premium','Try Premium','Signaler cette histoire','Report this story','Directives du Contenu','Content Guidelines','Partenariats avec','Ouvrir dans l','Open in app','Inscris-toi','Sign up','Cadastre-se','Baixe o app'];
function hide(e){if(e&&e.style)e.style.setProperty('display','none','important')}
function clean(){
 document.querySelectorAll('footer,[role=contentinfo],[id*=cookie],[class*=cookie],[class*=Cookie],[id*=onetrust],ins,iframe').forEach(hide);
 document.querySelectorAll('h1,h2,h3,h4,p,a,span,button,div').forEach(function(e){
  if(e.children.length>3)return;var t=(e.innerText||'').trim();if(!t||t.length>160)return;
  if(!P.some(function(p){return t.indexOf(p)===0}))return;
  var n=e;while(n.parentElement&&n.parentElement!==document.body&&(n.parentElement.innerText||'').length<1500)n=n.parentElement;hide(n)});
}
clean();if(!window.__fhObs){window.__fhObs=new MutationObserver(function(){clearTimeout(window.__fhT);window.__fhT=setTimeout(clean,300)});
window.__fhObs.observe(document.documentElement,{childList:true,subtree:true})}})()"""

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
                    override fun onPageFinished(v: WebView, u: String?) { v.evaluateJavascript(CLEAN_JS, null) }
                }
                webChromeClient = object : WebChromeClient() { override fun onProgressChanged(v: WebView, p: Int) { progress = p } }
                loadUrl(url)
                web = this
            }
        }, modifier = Modifier.fillMaxSize().navigationBarsPadding())
    }
}
