package app.fanfichub

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
private val AD_HOSTS = listOf("googletagmanager.com", "google-analytics.com", "googlesyndication.com", "doubleclick.net", "googleadservices.com",
    "adservice.google.com", "amazon-adsystem.com", "scorecardresearch.com", "quantserve.com", "facebook.net", "connect.facebook.net", "criteo.com",
    "criteo.net", "taboola.com", "outbrain.com", "adnxs.com", "pubmatic.com", "rubiconproject.com", "openx.net", "moatads.com", "chartbeat.com",
    "hotjar.com", "sentry.io", "cdn.permutive.com", "permutive.com", "adsafeprotected.com", "casalemedia.com", "teads.tv", "33across.com", "sharethrough.com",
    "smartadserver.com", "yieldmo.com", "media.net", "gumgum.com", "indexww.com", "lijit.com", "sovrn.com", "id5-sync.com", "btloader.com", "fundingchoicesmessages.google.com")

/** Feuille de style de lecture aux couleurs et à la taille choisies. */
private fun readJs(hub: Hub): String {
    val (_, bg, fg) = THEMES[hub.theme.coerceIn(0, THEMES.size - 1)]
    fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)
    return READ_JS.replace("SIZE", "${hub.size}").replace("BGC", hex(bg)).replace("FGC", hex(fg))
}

/** Mise en page de lecture : fond sombre, texte large et aéré, barres/bannières/pubs masquées (AO3, FF.net, Wattpad, Spirit). */
private const val READ_JS = """(function(){var s=document.getElementById('fhread')||document.createElement('style');s.id='fhread';s.textContent=
'html,body{background:BGC!important;color:FGC!important}'+
'#kudos,#add_comment_placeholder,#comments_placeholder,#new_comment,.new_comment,[id^=add_comment],#show_comments_link,#show_comments_link_top,li.collections,.kudos,#feedback .comment,#feedback h3.landmark,.announcement,[class*=announcement],#admin-banner,.admin-banner,header,nav,#header,#footer,#skiplinks,.landmark,.notice,ul.work.navigation.actions,dl.work.meta,#top,.lc-wrapper,#p_footer,.navbar,#smartbanner,.smartbanner,[class*=smartbanner],[class*=Advertisement],[class*=advert],[class*=ad-container],[data-testid*=ad],[id*=google_ads],.adsbygoogle{display:none!important}'+
'#chapters,.userstuff,#storytext,#storycontent,.texto,#texto,#conteudoTexto,.part-content,pre{max-width:720px!important;margin:0 auto!important;padding:0 18px!important;background:transparent!important}'+
'#chapters p,.userstuff p,#storytext p,#storytext,#storycontent p,.texto p,#texto p,#conteudoTexto p,.part-content p,pre p{font-family:Georgia,"Noto Serif",serif!important;font-size:SIZEpx!important;line-height:1.8!important;color:FGC!important;margin:0 0 1.05em!important;text-align:left!important}'+
'h1,h2,h3,.title{color:FGC!important;line-height:1.3!important}a{color:#b9a7ff!important}img{max-width:100%!important;height:auto!important}'+
'body{padding-bottom:40px!important;-webkit-text-size-adjust:100%!important}'+
// Tous sites : fonds unifiés, tout paragraphe de récit au même style (Wattpad data-p-id, FF.net #storycontent, Spirit .texto…)
'div,section,article,main,pre,td,center{background-color:transparent!important;color:FGC!important;border-color:rgba(128,128,128,.25)!important}'+
'p,[data-p-id],#storycontent,#storycontent p,[class*=texto] p,[class*=capitulo] p,.panel-reading p,pre{font-family:Georgia,"Noto Serif",serif!important;font-size:SIZEpx!important;line-height:1.8!important;color:FGC!important;white-space:normal!important}'+
'#storycontent,.panel-reading,[class*=texto],[class*=part-content]{max-width:720px!important;margin:0 auto!important;padding:0 18px!important}'+
'[id*=banner],[class*=app-banner],[class*=AppBanner],[class*=download-app],[class*=sticky-header],.t_head,#top_menu{display:none!important}'+
'blockquote{margin:0 0 1em!important;padding:0 0 0 10px!important}.preface,.notes,.summary{padding:0 14px!important}'+
'p.jump{display:none!important}.fhfold{display:none!important}.fhmore{color:#b9a7ff;font-size:14px;font-weight:bold;padding:6px 0 12px;cursor:pointer}';
if(!s.parentNode)(document.head||document.documentElement).appendChild(s);
document.querySelectorAll('.notes,.chapter .preface .notes,div[id=notes]').forEach(function(n){var t=(n.innerText||'');if(!n.querySelector('blockquote')||/end of the chapter|fin du chapitre|end of the work/i.test(t)&&t.length<120)n.style.display='none'});
// Résumé et notes repliés : « Tout afficher » pour déplier
document.querySelectorAll('.preface .summary blockquote,.preface .notes blockquote,#work_endnotes blockquote,.end.notes blockquote,[class*=notas]').forEach(function(b){
 if(b.__fh)return;b.__fh=1;b.classList.add('fhfold');var m=document.createElement('div');m.className='fhmore';m.textContent='Afficher ▾';
 m.onclick=function(){var o=b.classList.toggle('fhfold');m.textContent=o?'Afficher ▾':'Masquer ▴'};b.parentNode.insertBefore(m,b.nextSibling)});
})()"""

private const val CLEAN_JS = """(function(){
var P=['Rejoins la plus grande','Join the largest','Cela pourrait aussi te plaire','You may also like','Télécharger l','Download the','Essayer Premium','Try Premium','Signaler cette histoire','Report this story','Directives du Contenu','Content Guidelines','Partenariats avec','Ouvrir dans l','Open in app','Inscris-toi','Sign up','Cadastre-se','Baixe o app','Baixar','Gratuito - em Google Play','Just In Community','Advertisement','Publicité'];
function hide(e){if(e&&e.style)e.style.setProperty('display','none','important')}
function clean(){
 document.querySelectorAll('footer,[role=contentinfo],[id*=cookie],[class*=cookie],[class*=Cookie],[id*=onetrust],ins,iframe').forEach(hide);
 document.querySelectorAll('h1,h2,h3,h4,p,a,span,button,div').forEach(function(e){
  if(e.children.length>3)return;var t=(e.innerText||'').trim();if(!t||t.length>160)return;
  if(!P.some(function(p){return t.indexOf(p)===0}))return;
  var n=e,k=0,lim=innerHeight*0.7;
  while(k<6&&n.parentElement&&n.parentElement!==document.body&&n.parentElement.parentElement!==document.body
   &&(n.parentElement.innerText||'').length<1500&&n.parentElement.getBoundingClientRect().height<lim
   &&!n.parentElement.querySelector('main,article,[role=main],pre'))
   {n=n.parentElement;k++}
  if(n.getBoundingClientRect().height<lim&&!n.querySelector('main,article,[role=main]'))hide(n)});
}
clean();if(!window.__fhObs){window.__fhObs=new MutationObserver(function(){clearTimeout(window.__fhT);window.__fhT=setTimeout(clean,300)});
window.__fhObs.observe(document.documentElement,{childList:true,subtree:true})}})()"""

/** Liste des chapitres de la page (AO3 et FF.net : menu déroulant ; Wattpad et Spirit : liens). */
private const val CHAP_JS = """(function(){var h=location.host,o=[],m;
var sel=document.querySelector('#selected_id,#chap_select,select[name=chapter],select[name=chapters]');
if(sel&&(m=location.pathname.match(/\/works\/(\d+)/)))sel.querySelectorAll('option').forEach(function(x){o.push([x.textContent.trim(),'/works/'+m[1]+'/chapters/'+x.value])});
else if(sel&&(m=location.pathname.match(/\/s\/(\d+)/)))sel.querySelectorAll('option').forEach(function(x){o.push([x.textContent.trim(),'/s/'+m[1]+'/'+x.value+'/'])});
else{var p=h.indexOf('wattpad')>=0?/wattpad\.com\/\d+-/:h.indexOf('spirit')>=0?/\/historia\/[^\/]+\/[^\/?#]+/:/\/s\/\d+\/\d+/;
document.querySelectorAll('a[href]').forEach(function(a){var t=a.textContent.trim();if(p.test(a.href)&&t&&t!=='»')o.push([t,a.href])})}
var s={};o=o.filter(function(x){x[1]=new URL(x[1],location.href).href;if(s[x[1]])return 0;return s[x[1]]=1});
FH.chapters(JSON.stringify(o))})()"""

/** Envoie les textes visibles de la page au traducteur, puis les remplace (mise en page conservée). */
private const val TR_JS = """(function(){
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){
 var p=n.parentNode&&n.parentNode.nodeName;return n.nodeValue.trim().length>1&&!n.__fh&&p!=='SCRIPT'&&p!=='STYLE'&&p!=='NOSCRIPT'?1:3}});
var a=[],n;while((n=w.nextNode())&&a.length<3000)a.push(n);window.__fhNodes=a;
window.fhApply=function(t){t.forEach(function(s,i){var x=window.__fhNodes[i];if(x&&s){x.nodeValue=s;x.__fh=1}})};
FH.batch(JSON.stringify(a.map(function(x){return x.nodeValue})));})()"""

/** Lecture sur le site, dans l'app, avec un navigateur qui se présente comme Chrome (session conservée). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(hub: Hub, url: String, title: String) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var tr by remember { mutableStateOf<String?>(null) } // null = VO, "…" = en cours, "FR" = traduit
    BackHandler { if (canBack) web?.goBack() else hub.pop() }
    var sizing by remember { mutableStateOf(false) }
    var chapList by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    chapList?.let { l -> androidx.compose.ui.window.Dialog(onDismissRequest = { chapList = null }) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Surf).padding(vertical = 10.dp)) {
            items(l.size) { i -> Text(l[i].first, color = Txt, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { chapList = null; web?.loadUrl(l[i].second) }.padding(horizontal = 20.dp, vertical = 12.dp)) }
        }
    } }
    LaunchedEffect(hub.size, hub.theme) { web?.evaluateJavascript(readJs(hub), null) }
    if (sizing) androidx.compose.ui.window.Dialog(onDismissRequest = { sizing = false }) {
        Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(Surf).padding(vertical = 16.dp)) {
            val (_, bg, fg) = THEMES[hub.theme.coerceIn(0, THEMES.size - 1)]
            // Aperçu : même police, même taille, mêmes couleurs que la page
            Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color(bg)).padding(16.dp)) {
                Text("Luffy regarda l'horizon, le chapeau de paille vissé sur la tête. Au-delà des vagues l'attendait la plus grande aventure du monde.",
                    color = Color(fg), fontSize = hub.size.sp, lineHeight = (hub.size * 1.8f).sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
            }
            androidx.compose.material3.Slider(value = hub.size.toFloat(), onValueChange = { v -> hub.setReader(s = v.toInt()) }, valueRange = 14f..30f, steps = 15,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = A1, activeTrackColor = A1), modifier = Modifier.padding(horizontal = 16.dp))
            ReadingSettings(hub)
        }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { if (canBack) web?.goBack() else hub.pop() }
            Text(title, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(when (tr) { null -> "FR"; "FR" -> "VO"; else -> tr!! }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(Grad).clickable {
                    when (tr) {
                        null -> { hub.toggleWebFr(true); tr = "0 %"; web?.evaluateJavascript(TR_JS, null) }
                        "FR" -> { hub.toggleWebFr(false); tr = null; web?.reload() }
                    }
                }.padding(horizontal = 14.dp, vertical = 10.dp))
            Spacer(Modifier.width(8.dp))
            Text("☰", color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Surf2).clickable { web?.evaluateJavascript(CHAP_JS, null) }.padding(horizontal = 12.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Text("Aa", color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(Surf2).clickable { sizing = true }.padding(horizontal = 12.dp, vertical = 10.dp))
            Spacer(Modifier.width(8.dp))
            CircleIcon(Icons.Default.Refresh) { tr = null; web?.reload() }

        }
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (progress in 1..99) Bar(progress / 100f, A1) }
        AndroidView(factory = { c ->
            WebView(c).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = BROWSER_UA
                settings.offscreenPreRaster = true
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = !r.url.scheme.orEmpty().startsWith("http")
                    override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) { canBack = v.canGoBack(); v.evaluateJavascript(readJs(hub), null) }
                    // Pubs et traqueurs bloqués : pages bien plus rapides
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): android.webkit.WebResourceResponse? {
                        val h = r.url.host.orEmpty()
                        return if (AD_HOSTS.any { h == it || h.endsWith(".$it") }) android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
                    }
                    // Style appliqué dès que la page devient visible (pas d'éclair de l'ancienne mise en page)
                    override fun onPageCommitVisible(v: WebView, u: String?) { v.evaluateJavascript(readJs(hub), null) }
                    override fun onPageFinished(v: WebView, u: String?) {
                        v.evaluateJavascript(readJs(hub), null); v.evaluateJavascript(CLEAN_JS, null)
                        v.postDelayed({ v.evaluateJavascript(readJs(hub), null) }, 1500)
                        if ((hub.webFr || hub.autoTr) && tr != "0 %") { tr = "0 %"; v.post { v.evaluateJavascript(TR_JS, null) } }
                    }
                }
                webChromeClient = object : WebChromeClient() { override fun onProgressChanged(v: WebView, p: Int) { progress = p } }
                val view = this
                addJavascriptInterface(object {
                    @JavascriptInterface fun chapters(json: String) {
                        val a = JSONArray(json)
                        val l = (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getString(0) to x.getString(1) } }
                        hub.scope.launch { if (l.isEmpty()) hub.status = "Liste des chapitres introuvable sur cette page" else chapList = l }
                    }
                    @JavascriptInterface fun batch(json: String) {
                        hub.scope.launch {
                            try {
                                val a = JSONArray(json); val items = (0 until a.length()).map { a.getString(it) }
                                val res = Translator.texts(items) { p -> tr = "${(p * 100).toInt()} %" }
                                view.evaluateJavascript("fhApply(" + JSONArray(res).toString() + ")", null)
                                tr = "FR"
                            } catch (e: Exception) { tr = null; hub.status = "Traduction indisponible pour le moment, réessaie dans un instant" }
                        }
                    }
                }, "FH")
                loadUrl(url)
                web = this
            }
        }, modifier = Modifier.fillMaxSize().navigationBarsPadding())
    }
}
