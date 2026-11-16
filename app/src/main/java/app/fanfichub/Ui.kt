package app.fanfichub

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.util.Calendar

val Bg = Color(0xFF0A0A0F)
val Surf = Color(0xFF15151E)
val Surf2 = Color(0xFF1F1F2B)
val Txt = Color(0xFFF4F4F8)
val Mut = Color(0xFF8D8D9C)
val A1 = Color(0xFFA78BFA)
val A2 = Color(0xFFF472B6)
val Grad = Brush.linearGradient(listOf(A1, A2))
val THEMES = listOf(Triple("Nuit", 0xFF0A0A0FL, 0xFFE4E4EEL), Triple("Sépia", 0xFFF4ECD8L, 0xFF3B2F22L), Triple("Papier", 0xFFFFFFFFL, 0xFF1A1A1AL))

fun fandomColor(s: String) = Color.hsv(((s.hashCode() % 360 + 360) % 360).toFloat(), 0.55f, 0.95f)
fun fmt(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}k"; n >= 1000 -> "%.1fk".format(n / 1000.0); else -> "$n" }

@Composable
fun HubApp(hub: Hub) {
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = A1, onBackground = Txt, onSurface = Txt)) {
        BackHandler(enabled = hub.stack.size > 1) { hub.pop() }
        Box(Modifier.fillMaxSize().background(Bg)) {
            AnimatedContent(
                targetState = hub.stack.last(),
                transitionSpec = { (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith fadeOut(tween(160)) },
                label = "nav",
            ) { s ->
                when (s) {
                    Screen.Home -> Home(hub)
                    Screen.Login -> Login(hub)
                    is Screen.Detail -> Detail(hub, s.id)
                    is Screen.Reader -> Reader(hub, s.id)
                }
            }
        }
    }
}

// ---------- Composants ----------
@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Text(text, color = if (filled) Color.White else color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = if (filled) 0.9f else 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun NewBadge(n: Int) {
    Text("+$n chapitre" + if (n > 1) "s" else "", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(Grad).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun Bar(p: Float, c: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(c))
    }
}

@Composable
fun GradButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(Grad).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CircleIcon(icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun FilterPill(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Color.White else Mut, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (on) Grad else SolidColor(Surf2)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
fun Section(title: String) {
    Text(title, color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 12.dp))
}

@Composable
fun Empty(msg: String) {
    Text(msg, color = Mut, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Surf).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Mut, fontSize = 12.sp)
    }
}

@Composable
fun WorkCard(w: Work, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = fandomColor(w.fandom)
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(c.copy(alpha = 0.20f), Surf, Surf)))
        .border(1.dp, Color.White.copy(alpha = 0.06f), shape).clickable(onClick = onClick).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { Pill(w.fandom, c) }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (w.newChapters > 0) NewBadge(w.newChapters) else if (w.complete) Pill("Terminée", Mut)
        }
        Spacer(Modifier.height(12.dp))
        Text(w.title, color = Txt, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("par ${w.author}", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        if (w.summary.isNotBlank()) Text(w.summary, color = Color(0xFFB9B9C6), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${fmt(w.words)} mots", color = Mut, fontSize = 13.sp)
            Text("${w.chapters} ch.", color = Mut, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null, tint = A2, modifier = Modifier.size(14.dp))
                Text(" ${fmt(w.kudos)}", color = Mut, fontSize = 13.sp)
            }
        }
        if (w.progress > 0.01f) Bar(w.progress, c, Modifier.padding(top = 14.dp))
    }
}

@Composable
fun ContinueCard(w: Work, onClick: () -> Unit) {
    val c = fandomColor(w.fandom)
    Column(Modifier.width(250.dp).height(164.dp).clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(c.copy(alpha = 0.9f), c.copy(alpha = 0.3f), Surf))).clickable(onClick = onClick).padding(18.dp)) {
        Text(w.fandom.uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(w.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Bar(w.progress, Color.White, Modifier.padding(top = 10.dp))
        Text("${(w.progress * 100).toInt()} % lu", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

// ---------- Accueil ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(hub: Hub) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = hub.syncing, onRefresh = { scope.launch { hub.sync() } }, modifier = Modifier.fillMaxSize()) {
            when (hub.tab) { 0 -> Feed(hub); 1 -> Library(hub); else -> Account(hub) }
        }
        BottomBar(hub, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun BottomBar(hub: Hub, modifier: Modifier) {
    val items = listOf(Icons.Default.Notifications to "Nouveautés", Icons.Default.Star to "Bibliothèque", Icons.Default.Person to "Compte")
    val shape = RoundedCornerShape(30.dp)
    Row(modifier.navigationBarsPadding().padding(bottom = 14.dp).clip(shape).background(Surf2.copy(alpha = 0.95f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, (ic, label) ->
            val on = hub.tab == i
            Row(Modifier.clip(RoundedCornerShape(24.dp)).background(if (on) Grad else SolidColor(Color.Transparent)).clickable { hub.tab = i }
                .animateContentSize().padding(horizontal = if (on) 18.dp else 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, label, tint = if (on) Color.White else Mut, modifier = Modifier.size(22.dp))
                if (on) Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun Header(hub: Hub, title: String? = null) {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 4.dp)) {
        Text("FANFIC HUB", style = TextStyle(brush = Grad, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
        Text(title ?: ((if (h in 5..17) "Bonjour" else "Bonsoir") + (hub.user?.let { ",\n$it" } ?: "")),
            color = Txt, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, modifier = Modifier.padding(top = 6.dp))
        hub.status?.let { Text(it, color = Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun ConnectCard(hub: Hub) {
    Column(Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(A1.copy(alpha = 0.35f), A2.copy(alpha = 0.2f), Surf)))
        .padding(22.dp)) {
        Text("Connecte ton compte AO3", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Tes favoris, ta liste « à lire » et tes abonnements arrivent ici, avec une alerte à chaque nouveau chapitre.",
            color = Color(0xFFCFCFDA), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        GradButton("Se connecter") { hub.push(Screen.Login) }
    }
}

@Composable
fun Feed(hub: Hub) {
    val fresh = hub.works.filter { it.newChapters > 0 }.sortedByDescending { it.newChapters }
    val reading = hub.works.filter { it.progress > 0.01f && it.progress < 0.985f }.sortedByDescending { it.lastRead }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Header(hub) }
        if (hub.user == null) item { ConnectCard(hub) }
        if (reading.isNotEmpty()) {
            item { Section("Reprendre la lecture") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(reading, key = { it.id }) { w -> ContinueCard(w) { hub.push(Screen.Reader(w.id)) } }
                }
            }
        }
        item { Section(if (fresh.isEmpty()) "Nouveaux chapitres" else "Nouveaux chapitres · ${fresh.size}") }
        if (fresh.isEmpty()) item {
            Empty(if (hub.works.isEmpty()) "Tire l'écran vers le bas pour synchroniser ta bibliothèque." else "Tout est à jour. Tire vers le bas pour vérifier.")
        }
        items(fresh, key = { it.id }) { w ->
            WorkCard(w, Modifier.padding(horizontal = 20.dp, vertical = 7.dp).animateItem()) { hub.push(Screen.Detail(w.id)) }
        }
    }
}

// ---------- Bibliothèque ----------
@Composable
fun SearchBox(v: String, on: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(Surf2).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = Mut)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (v.isEmpty()) Text("Titre, auteur, fandom, tag…", color = Mut, fontSize = 16.sp)
            BasicTextField(v, on, singleLine = true, textStyle = TextStyle(color = Txt, fontSize = 16.sp), cursorBrush = SolidColor(A1), modifier = Modifier.fillMaxWidth())
        }
        if (v.isNotEmpty()) Icon(Icons.Default.Close, null, tint = Mut, modifier = Modifier.clickable { on("") })
    }
}

@Composable
fun Library(hub: Hub) {
    var q by remember { mutableStateOf("") }
    var f by remember { mutableIntStateOf(0) }
    val filters = listOf("Tout", "En cours de lecture", "Nouveautés", "Abonnement", "Favori", "À lire", "Terminées", "Hors ligne")
    val list = hub.works.filter { w ->
        (q.isBlank() || listOf(w.title, w.author, w.fandoms.joinToString(), w.tags.joinToString()).any { it.contains(q, true) }) && when (f) {
            1 -> w.progress > 0.01f && w.progress < 0.985f
            2 -> w.newChapters > 0
            3, 4, 5 -> filters[f] in w.sources
            6 -> w.complete
            7 -> hub.hasText(w.id)
            else -> true
        }
    }.sortedWith(compareByDescending<Work> { it.newChapters > 0 }.thenByDescending { it.lastRead }.thenBy { it.title.lowercase() })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Header(hub, "Bibliothèque") }
        item { Text("${hub.works.size} fanfictions · AO3", color = Mut, fontSize = 14.sp, modifier = Modifier.padding(start = 22.dp, bottom = 16.dp)) }
        item { SearchBox(q) { q = it } }
        item {
            LazyRow(Modifier.padding(vertical = 14.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(filters) { i, s -> FilterPill(s, i == f) { f = i } }
            }
        }
        if (list.isEmpty()) item { Empty("Aucune fanfiction ici.") }
        items(list, key = { it.id }) { w ->
            WorkCard(w, Modifier.padding(horizontal = 20.dp, vertical = 7.dp).animateItem()) { hub.push(Screen.Detail(w.id)) }
        }
    }
}

// ---------- Compte ----------
@Composable
fun Account(hub: Hub) {
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        Header(hub, "Compte")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Surf).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Grad), contentAlignment = Alignment.Center) {
                    Text(hub.user?.take(1)?.uppercase() ?: "?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(hub.user ?: "Non connecté", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Archive of Our Own", color = Mut, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (hub.user == null) {
                GradButton("Se connecter à AO3") { hub.push(Screen.Login) }
                Text("Ou entre seulement ton pseudo (favoris publics, sans abonnements) :", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                SearchBox(manual) { manual = it }
                Spacer(Modifier.height(10.dp))
                GhostButton("Valider le pseudo") { if (manual.isNotBlank()) { hub.saveUser(manual); scope.launch { hub.sync() } } }
            } else {
                GradButton(if (hub.syncing) "Synchronisation…" else "Synchroniser maintenant") { scope.launch { hub.sync() } }
                Spacer(Modifier.height(10.dp))
                GhostButton("Se déconnecter") { hub.logout() }
            }
            Text("Lecture", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 30.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                THEMES.forEachIndexed { i, (name, bg, fg) ->
                    val on = hub.theme == i
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(bg))
                        .border(2.dp, if (on) A1 else Color.Transparent, RoundedCornerShape(18.dp)).clickable { hub.setReader(t = i) }.padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aa", color = Color(fg), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(name, color = Color(fg).copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surf).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A−", color = Txt, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size - 1) }.padding(14.dp))
                Text("Taille du texte : ${hub.size}", color = Mut, fontSize = 14.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("A+", color = Txt, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size + 1) }.padding(14.dp))
            }
            Text("Bientôt", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 30.dp, bottom = 12.dp))
            listOf("Spirit Fanfics", "Wattpad", "FanFiction.net").forEach { s ->
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surf).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(fandomColor(s)))
                    Text(s, color = Txt, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Pill("En préparation", Mut)
                }
            }
        }
    }
}

// ---------- Connexion ----------
@Composable
fun Login(hub: Hub) {
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    fun finish(name: String?) {
        if (done) return
        done = true
        CookieManager.getInstance().flush()
        scope.launch { if (name != null) hub.saveUser(name) else hub.detectUser(); hub.pop(); hub.tab = 0; hub.sync() }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { hub.pop() }
            Text("Connexion à AO3", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            Text("Terminé", color = A1, fontWeight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).clickable { finish(null) }.padding(12.dp))
        }
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        val name = Regex("^https://archiveofourown\\.org/users/([^/?#]+)").find(url)?.groupValues?.get(1)
                        val logged = CookieManager.getInstance().getCookie(Ao3.BASE)?.contains("user_credentials") == true
                        if (!url.contains("/users/login") && (logged || (name != null && name != "login"))) finish(name?.takeIf { it != "login" })
                    }
                }
                loadUrl(Ao3.BASE + "/users/login")
            }
        }, modifier = Modifier.fillMaxSize().navigationBarsPadding())
    }
}

// ---------- Fiche ----------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Detail(hub: Hub, id: String) {
    val w = hub.work(id) ?: return
    val c = fandomColor(w.fandom)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.copy(alpha = 0.6f), c.copy(alpha = 0.15f), Bg)))) {
                    Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 76.dp, bottom = 10.dp)) {
                        Text(w.fandoms.joinToString(" · ").uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(w.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 37.sp, modifier = Modifier.padding(top = 10.dp))
                        Text("par ${w.author}", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (w.newChapters > 0) NewBadge(w.newChapters)
                            if (w.rating.isNotEmpty()) Pill(w.rating, Color.White)
                            Pill(if (w.complete) "Terminée" else "En cours", Color.White)
                            w.sources.forEach { Pill(it, Color.White) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Mots", fmt(w.words), Modifier.weight(1f))
                    StatBox("Chapitres", w.chapters, Modifier.weight(1f))
                    StatBox("Kudos", fmt(w.kudos), Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    GradButton(when {
                        busy -> "Téléchargement…"
                        w.progress > 0.01f -> "Continuer · ${(w.progress * 100).toInt()} %"
                        else -> "Commencer la lecture"
                    }) {
                        if (busy) return@GradButton
                        if (hub.hasText(id) && w.newChapters == 0) hub.push(Screen.Reader(id))
                        else scope.launch {
                            busy = true; err = null
                            try { hub.download(id); hub.push(Screen.Reader(id)) } catch (e: Exception) { err = e.message ?: "Erreur réseau" }
                            busy = false
                        }
                    }
                    err?.let { Text(it, color = Color(0xFFF87171), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
                    Spacer(Modifier.height(10.dp))
                    GhostButton("Ouvrir sur AO3") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${Ao3.BASE}/works/$id"))) }
                    if (w.summary.isNotBlank()) {
                        Text("Résumé", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
                        Text(w.summary, color = Color(0xFFCFCFDA), fontSize = 15.sp, lineHeight = 23.sp)
                    }
                    if (w.tags.isNotEmpty()) {
                        Text("Tags", color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { w.tags.forEach { Pill(it, Mut) } }
                    }
                    if (w.updated.isNotBlank()) Text("Mise à jour : ${w.updated}", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 22.dp))
                }
            }
        }
        CircleIcon(Icons.AutoMirrored.Filled.ArrowBack, Modifier.statusBarsPadding().padding(14.dp)) { hub.pop() }
    }
}
