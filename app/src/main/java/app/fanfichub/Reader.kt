package app.fanfichub

import android.annotation.SuppressLint
import android.content.Intent
import android.text.TextUtils
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private fun esc(s: String) = TextUtils.htmlEncode(s)
private fun hex(c: Long) = String.format("#%06X", c and 0xFFFFFFL)

fun readerHtml(w: Work, body: String, theme: Int, size: Int): String {
    val t = THEMES[theme]
    return """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><style>
body{margin:0;background:${hex(t.second)};color:${hex(t.third)};font:${size}px/1.75 Georgia,'Noto Serif',serif;padding:96px 24px 180px;-webkit-tap-highlight-color:transparent}
.k{font:800 12px sans-serif;letter-spacing:3px;color:#A78BFA;text-transform:uppercase}
h1{font:800 30px/1.2 sans-serif;margin:12px 0 8px}.by{opacity:.6;font:15px sans-serif;margin-bottom:56px}
h2{font:700 21px/1.3 sans-serif;margin:76px 0 28px;background:linear-gradient(90deg,#A78BFA,#F472B6);-webkit-background-clip:text;color:transparent}
p{margin:0 0 1.15em}hr{border:0;height:1px;background:currentColor;opacity:.2;margin:44px 0}a{color:#A78BFA}img{max-width:100%;height:auto}
blockquote{margin:1em 0;padding-left:16px;border-left:3px solid #A78BFA;opacity:.85}
.end{text-align:center;opacity:.45;font:14px sans-serif;margin-top:90px}
</style><div class=k>${esc(w.fandom)}</div><h1>${esc(w.title)}</h1><div class=by>par ${esc(w.author)}</div>$body
<div class=end>Fin du texte disponible</div>
<script>
function H(){return document.documentElement.scrollHeight-innerHeight}
var t=0;addEventListener('scroll',function(){clearTimeout(t);t=setTimeout(function(){NV.progress(H()>0?Math.min(1,scrollY/H()):1)},120)},{passive:true});
function go(p){scrollTo(0,H()*p)}
document.addEventListener('click',function(e){if(!e.target.closest('a'))NV.tap()});
</script>"""
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Reader(hub: Hub, id: String) {
    val w = hub.work(id) ?: return
    val titles = remember(id) { hub.titles(id) }
    var progress by remember { mutableFloatStateOf(w.progress) }
    var panel by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableIntStateOf(-1) }
    val t = THEMES[hub.theme]
    val bg = Color(t.second)
    val fg = Color(t.third)
    val key = hub.theme * 100 + hub.size

    DisposableEffect(id) {
        onDispose { hub.work(id)?.let { hub.update(it.copy(progress = progress, lastRead = System.currentTimeMillis(), newChapters = 0)) } }
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(object {
                        @JavascriptInterface fun progress(p: Float) { post { progress = p } }
                        @JavascriptInterface fun tap() { post { panel = !panel; toc = false } }
                    }, "NV")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript("go($progress)", null) }
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, r.url)) }; return true
                        }
                    }
                    web = this
                }
            },
            update = { v ->
                if (loaded != key) {
                    loaded = key
                    v.setBackgroundColor(bg.toArgb())
                    v.loadDataWithBaseURL(Ao3.BASE, readerHtml(w, hub.text(id), hub.theme, hub.size), "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Barre de progression permanente
        Box(Modifier.statusBarsPadding().fillMaxWidth().height(3.dp).background(fg.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Grad))
        }

        AnimatedVisibility(panel, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            Row(Modifier.fillMaxWidth().background(Surf.copy(alpha = 0.96f)).statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { hub.pop() }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(w.title, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${(progress * 100).toInt()} % · ${w.chapters} chapitres", color = Mut, fontSize = 12.sp)
                }
                if (titles.size > 1) CircleIcon(Icons.AutoMirrored.Filled.List) { toc = !toc }
            }
        }

        AnimatedVisibility(panel, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Row(Modifier.navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(26.dp)).background(Surf.copy(alpha = 0.96f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                THEMES.forEachIndexed { i, (_, b, f) ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(b)).border(2.dp, if (hub.theme == i) A1 else Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable { hub.setReader(t = i) }, contentAlignment = Alignment.Center) { Text("A", color = Color(f), fontWeight = FontWeight.Bold) }
                }
                Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.12f)))
                Text("A−", color = Txt, fontSize = 16.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size - 1) }.padding(10.dp))
                Text("${hub.size}", color = Mut, fontSize = 14.sp)
                Text("A+", color = Txt, fontSize = 20.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size + 1) }.padding(10.dp))
            }
        }

        AnimatedVisibility(toc && panel, modifier = Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            LazyColumn(Modifier.padding(24.dp).fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(24.dp)).background(Surf2).padding(8.dp)) {
                itemsIndexed(titles) { i, s ->
                    Text(s, color = Txt, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable { web?.evaluateJavascript("document.getElementById('c$i').scrollIntoView()", null); toc = false; panel = false }.padding(14.dp))
                }
            }
        }
    }
}
