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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.net.URL
import java.util.Calendar

/** Noir pur (écran OLED : pixels éteints) ou anthracite. */
/** 0 = Noir (OLED), 1 = Sombre, 2 = Clair. */
var themeMode by mutableIntStateOf(0)
val isLight get() = themeMode == 2
val Bg: Color get() = when (themeMode) { 0 -> Color.Black; 1 -> Color(0xFF0E0E14); else -> Color(0xFFF5F5F8) }
val Surf: Color get() = when (themeMode) { 0 -> Color(0xFF0E0E13); 1 -> Color(0xFF17171F); else -> Color.White }
val Surf2: Color get() = when (themeMode) { 0 -> Color(0xFF19191F); 1 -> Color(0xFF22222C); else -> Color(0xFFEBEBF1) }
val Txt: Color get() = if (isLight) Color(0xFF15151B) else Color(0xFFF4F4F8)
val Mut: Color get() = if (isLight) Color(0xFF6A6A77) else Color(0xFF8D8D9C)
val Sub: Color get() = if (isLight) Color(0xFF3C3C46) else Color(0xFFCFCFDA)
val A1 = Color(0xFFA78BFA)
val A2 = Color(0xFFF472B6)
val Grad = Brush.linearGradient(listOf(A1, A2))
val THEMES = listOf(Triple("Noir", 0xFF000000L, 0xFFD4D4DCL), Triple("Nuit", 0xFF0A0A0FL, 0xFFE4E4EEL), Triple("Sépia", 0xFFF4ECD8L, 0xFF3B2F22L), Triple("Papier", 0xFFFFFFFFL, 0xFF1A1A1AL))

fun fandomColor(s: String) = Color.hsv(((s.hashCode() % 360 + 360) % 360).toFloat(), 0.55f, 0.95f)
fun fmt(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}k"; n >= 1000 -> "%.1fk".format(n / 1000.0); else -> "$n" }

@Composable
fun HubApp(hub: Hub) {
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { win ->
            androidx.core.view.WindowCompat.getInsetsController(win, view).apply { isAppearanceLightStatusBars = isLight; isAppearanceLightNavigationBars = isLight }
        }
    }
    MaterialTheme(colorScheme = if (isLight) androidx.compose.material3.lightColorScheme(background = Bg, surface = Surf, primary = A1, onBackground = Txt, onSurface = Txt)
        else darkColorScheme(background = Bg, surface = Surf, primary = A1, onBackground = Txt, onSurface = Txt)) {
        BackHandler(enabled = hub.stack.size > 1) { hub.pop() }
        Box(Modifier.fillMaxSize().background(Bg)) {
            AnimatedContent(
                targetState = hub.stack.last(),
                transitionSpec = { (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 8 }) togetherWith fadeOut(tween(160)) },
                label = "nav",
            ) { s ->
                when (s) {
                    Screen.Home -> Home(hub)
                    is Screen.Login -> Login(hub, s.site)
                    is Screen.Detail -> Detail(hub, s.id)
                    is Screen.Reader -> Reader(hub, s.id)
                    is Screen.Setup -> Setup(hub, s.site)
                    is Screen.Web -> WebScreen(hub, s.url, s.title, s.reader)
                    is Screen.Extensions -> ExtensionsScreen(hub)
                }
            }
            hub.intro?.let { s -> BookIntro(s, remember(hub.works.size) { hub.works.groupingBy { it.site }.eachCount() }) { hub.intro = null; hub.enterSite(s) } }
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
    val shape = RoundedCornerShape(22.dp)
    Row(modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(c.copy(alpha = 0.16f), Surf, Surf)))
        .border(1.dp, Color.White.copy(alpha = 0.05f), shape).clickable(onClick = onClick).padding(14.dp)) {
        BookCover(w, 66)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Pill(w.fandom, c) }
                if (w.newChapters > 0) NewBadge(w.newChapters) else if (w.complete) Pill("Terminée", Mut)
            }
            Text(w.title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
            Text("par ${w.author.ifBlank { "?" }}", color = Mut, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (w.summary.isNotBlank()) Text(w.summary, color = Sub, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (w.words > 0) Text("${fmt(w.words)} mots", color = Mut, fontSize = 12.sp)
                Text("${w.chapters.removeSuffix("/?")} ch.", color = Mut, fontSize = 12.sp)
                if (w.kudos > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, tint = A2, modifier = Modifier.size(12.dp))
                    Text(" ${fmt(w.kudos)}", color = Mut, fontSize = 12.sp)
                }
            }
        }
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

// ---------- Sites ----------
data class SiteUi(val name: String, val logo: String, val color: Color, val desc: String, val how: String)

val ALL_SITE_UI = listOf(
    SiteUi("AO3", "AO3", Color(0xFFE5484D), "Favoris, à lire plus tard et abonnements", "Connecte-toi avec ton identifiant AO3 : le pseudo est détecté tout seul."),
    SiteUi("Wattpad", "W", Color(0xFFFF7A1A), "Bibliothèque et listes de lecture", "Cherche ton compte et choisis-le : tes listes de lecture publiques seront récupérées, sans connexion."),
    SiteUi("FanFiction.net", "FF", Color(0xFF3E7BFA), "Histoires favorites de ton profil", "Cherche ton nom d'auteur ou colle le lien de ton profil : tes histoires favorites seront récupérées."),
    SiteUi("Spirit Fanfics", "S", Color(0xFF22C3A6), "Ta bibliothèque Spirit", "Connecte-toi sur Spirit, puis indique ton pseudo."),
)
/** Sites dont l'extension est activée. */
val SITE_UI get() = ALL_SITE_UI.filter { Ext.on(it.name) }
fun siteUi(name: String?) = ALL_SITE_UI.firstOrNull { it.name == name }
    ?: Ext.byName(name ?: "")?.let { SiteUi(it.name, it.logo, Color(it.color), "Extension", "") } ?: ALL_SITE_UI[0]

@Composable
fun SiteLogo(site: String, size: Int, modifier: Modifier = Modifier) {
    val s = siteUi(site)
    Box(modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(Brush.linearGradient(listOf(s.color, s.color.copy(alpha = 0.6f)))),
        contentAlignment = Alignment.Center) {
        Text(s.logo, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size * if (s.logo.length > 2) 0.3f else 0.42f).sp)
    }
}

/** Premier lancement : choisir son site. */
@Composable
fun SitePicker(hub: Hub) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(22.dp)) {
        Text("FANFIC HUB", style = TextStyle(brush = Grad, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp), modifier = Modifier.padding(top = 24.dp))
        Text("Où lis-tu ?", color = Txt, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        Text("Choisis un site pour commencer. Tu pourras passer de l'un à l'autre à tout moment.", color = Mut, fontSize = 15.sp, lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp))
        SITE_UI.forEach { s ->
            Row(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(s.color.copy(alpha = 0.28f), Surf, Surf))).border(1.dp, s.color.copy(alpha = 0.25f), RoundedCornerShape(26.dp))
                .clickable { hub.pickSite(s.name, animate = true); if (hub.account(s.name) == null) hub.push(Screen.Setup(s.name)) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SiteLogo(s.name, 54)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(s.name, color = Txt, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(s.desc, color = Mut, fontSize = 13.sp, lineHeight = 18.sp)
                }
                hub.account(s.name)?.let { Pill("Connecté", s.color) }
            }
        }
        Text("Tout voir", color = A1, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).clip(CircleShape).clickable { hub.pickSite("ALL") }.padding(14.dp))
    }
}

/** Sélecteur de site toujours visible en haut. */
@Composable
fun SiteChips(hub: Hub) {
    val counts = remember(hub.works.size, hub.syncing, hub.hidden.size) { hub.works.filter { it.id !in hub.hidden }.groupingBy { it.site }.eachCount() }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Plus de « Tous » : on reste toujours sur un site (le plus fourni par défaut)
        LaunchedEffect(hub.site) { if (hub.site == "ALL") hub.pickSite(SITE_UI.maxByOrNull { counts[it.name] ?: 0 }?.name ?: "AO3") }
        // Sites intégrés + extensions qui ont des histoires dans la bibliothèque
        (SITE_UI + Ext.installed.filter { (counts[it.name] ?: 0) > 0 }.map { siteUi(it.name) }).forEach { s ->
            val on = hub.site == s.name
            Row(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(if (on) s.color else Surf2).clickable { hub.pickSite(s.name) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SiteLogo(s.name, 24)
                Text(fmt(counts[s.name] ?: 0), color = if (on) Color.White else Mut, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

fun Hub.visible() = works.filter { (site == "ALL" || it.site == site) && it.id !in hidden }

/** Bandeau de synchronisation, visible sur tous les onglets. */
@Composable
fun SyncPill(hub: Hub, modifier: Modifier) {
    // Synchro discrète : un fin trait lumineux tout en haut.
    if (hub.syncing) Box(modifier.statusBarsPadding().fillMaxWidth().height(2.dp)) {
        val p = hub.syncProgress
        if (p != null) Bar(p, A1) else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxSize(), color = A1, trackColor = Color.Transparent)
    }
}

// ---------- Accueil ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(hub: Hub) {
    LaunchedEffect(Unit) { hub.startCheck(); if (hub.syncDue(2)) hub.startSync(quick = true) }
    LaunchedEffect(hub.pendingImport) { hub.pendingImport?.let { hub.pendingImport = null; hub.startImport(it) } }
    Box(Modifier.fillMaxSize()) {
        var pulling by remember { mutableStateOf(false) }
        val pullScope = rememberCoroutineScope()
        PullToRefreshBox(isRefreshing = pulling, onRefresh = {
            pulling = true
            hub.startSync(only = hub.site?.takeIf { it != "ALL" }, quick = true)
            pullScope.launch { delay(700); pulling = false } // l'indicateur se range, la synchro continue en silence
        }, modifier = Modifier.fillMaxSize()) {
            when (hub.tab) { 0 -> Feed(hub); 1 -> Library(hub); 2 -> SearchTab(hub); else -> Accounts(hub) }
        }
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Bg))
        BottomBar(hub, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun BottomBar(hub: Hub, modifier: Modifier) {
    val items = listOf(Icons.Default.Home to "Accueil", Icons.Default.Star to "Bibliothèque", Icons.Default.Search to "Recherche", Icons.Default.Person to "Réglages")
    val shape = RoundedCornerShape(30.dp)
    Row(modifier.navigationBarsPadding().padding(bottom = 14.dp).clip(shape).background(Surf2.copy(alpha = 0.96f))
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
fun Title(small: String, big: String) {
    Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 14.dp)) {
        Text(big, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    }
}

@Composable
fun UpdateCard(hub: Hub) {
    Column(Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(A2.copy(alpha = 0.35f), A1.copy(alpha = 0.25f), Surf))).padding(22.dp)) {
        Text("Nouvelle version disponible", color = Txt, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Version ${hub.newVersion} · tu as la ${hub.version}", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        hub.updating?.let { Bar(it, Color.White, Modifier.padding(bottom = 12.dp)) }
        GradButton(hub.updating?.let { "Téléchargement… ${(it * 100).toInt()} %" } ?: "Mettre à jour") { hub.startUpdate() }
    }
}

/** Carte d'état du site sélectionné : clair sur ce qu'il faut faire. */
@Composable
fun SiteStateCard(hub: Hub) {
    val name = hub.site ?: return
    if (name == "ALL") return
    val s = siteUi(name)
    val acc = hub.account(name)
    Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(s.color.copy(alpha = 0.22f), Surf))).clickable { hub.push(Screen.Setup(name)) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SiteLogo(name, 40)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(if (acc == null) "Connecte ton compte ${s.name}" else acc, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(hub.siteStatus[name] ?: if (acc == null) "Appuie ici, c'est rapide" else "Tire vers le bas pour synchroniser ce site", color = Mut, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mut)
    }
}

@Composable
fun Feed(hub: Hub) {
    val fresh = remember(hub.works.size, hub.syncing) { hub.works.filter { it.newChapters > 0 }.sortedByDescending { it.newChapters } }
    val reading = remember(hub.works.size, hub.syncing) { hub.works.filter { it.progress > 0.01f && it.progress < 0.985f }.sortedByDescending { it.lastRead } }
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { Title("FANFIC HUB", (if (h in 5..17) "Bonjour" else "Bonsoir") + (hub.user?.let { ", $it" } ?: "")) }
        item { ShelfStrip(hub) }
        if (hub.newVersion != null) item { UpdateCard(hub) }
        if (hub.user == null && hub.users.isEmpty()) item {
            Row(Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surf).clickable { hub.tab = 3 }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Configure tes comptes", color = Txt, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mut)
            }
        }
        if (reading.isNotEmpty()) {
            item { Section("Reprendre la lecture") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(reading, key = { it.id }) { w -> Book3D(w, 100, Modifier.clickable { hub.push(Screen.Web(w.link, w.title)) }) }
                }
            }
        }
        item { Section(if (fresh.isEmpty()) "Nouveaux chapitres" else "Nouveaux chapitres · ${fresh.size}") }
        if (fresh.isEmpty()) item { Empty(if (hub.works.isEmpty()) "Touche un volume." else "À jour ✓") }
        items(fresh, key = { it.id }) { w ->
            WorkCard(w, Modifier.padding(horizontal = 20.dp, vertical = 6.dp).animateItem()) { hub.push(Screen.Detail(w.id)) }
        }
    }
}

// ---------- Bibliothèque ----------
@Composable
fun SearchBox(v: String, hint: String = "Titre, auteur, fandom, tag…", on: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(Surf2).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = Mut)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            if (v.isEmpty()) Text(hint, color = Mut, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(v, on, singleLine = true, textStyle = TextStyle(color = Txt, fontSize = 16.sp), cursorBrush = SolidColor(A1), modifier = Modifier.fillMaxWidth())
        }
        if (v.isNotEmpty()) Icon(Icons.Default.Close, null, tint = Mut, modifier = Modifier.clickable { on("") })
    }
}

@Composable
fun Library(hub: Hub) {
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var f by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var col by remember(hub.site) { mutableStateOf<String?>(null) }
    val filters = listOf("Tout", "En cours", "Nouveautés", "Terminées", "Hors ligne")
    val site = hub.site ?: "ALL"
    // Calculs mémorisés : ne se refont que si les données ou les filtres changent.
    // Recalcul seulement si les données changent (plus à chaque retour d'une fiche)
    val visible = remember(hub.works.size, hub.syncing, site, hub.hidden.size) { hub.visible() }
    var selecting by remember { mutableStateOf(false) }
    val sel = remember { mutableStateListOf<String>() }
    fun open(w: Work) { if (selecting) { if (w.id in sel) sel.remove(w.id) else sel.add(w.id) } else hub.push(Screen.Detail(w.id)) }
    val shelves0 = remember(visible) {
        if (site == "ALL") visible.groupBy { it.site }.map { (k, v) -> k to v }.sortedByDescending { it.second.size }
        else if (site == "AO3" || site == "FanFiction.net") visible.groupBy { fandomKey(it) }.map { (k, v) -> k to v }.sortedByDescending { it.second.size }
        else visible.flatMap { w -> w.sources.map { it to w } }.groupBy({ it.first }, { it.second }).map { (k, v) -> k to v }.sortedByDescending { it.second.size }
    }
    // Ordre des étagères selon le tri : A–Z, récentes, ou crossovers d'abord
    val shelves = remember(shelves0, hub.sortMode) {
        when (hub.sortMode) {
            0 -> shelves0.sortedBy { collectionName(it.first).lowercase() }
            2 -> shelves0.sortedWith(compareByDescending<Pair<String, List<Work>>> { crossName(collectionName(it.first)) }
                .thenByDescending { p -> p.second.count { crossOf(it) } * 1f / p.second.size }.thenByDescending { it.second.size })
            else -> shelves0.sortedByDescending { p -> p.second.maxOfOrNull { dateOf(it) } ?: 0 }
        }.let { l ->
            fun key(n: String) = collectionName(n).lowercase().replace(Regex("[^a-z]"), "")
            l.groupBy { key(it.first) }.values.flatten().map { (k, v) -> k to sortWorks(v, hub.sortMode) } // étagères déjà triées
        }
    }

    val list = remember(visible, q, f, col, hub.downloaded, hub.sortMode) {
        visible.filter { w ->
            col.let { c -> c == null || c in w.sources || c == w.site || ((w.site == "AO3" || w.site == "FanFiction.net") && c == fandomKey(w)) } &&
            (q.isBlank() || listOf(w.title, w.author, w.fandoms.joinToString(), w.tags.joinToString()).any { it.contains(q, true) }) && when (f) {
                1 -> w.progress > 0.01f && w.progress < 0.985f
                2 -> w.newChapters > 0
                3 -> w.complete
                4 -> hub.hasText(w.id)
                else -> true
            }
        }.let { sortWorks(it, hub.sortMode) }
    }
    val listMode = searching || col != null || f != 0
    val gridMode = col != null && !searching && f == 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 22.dp, end = 14.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Nombre de fics du site : chaque histoire comptée une fois, même si elle est dans plusieurs listes
                    val stats = remember(visible.size, site) {
                        val unique = visible.distinctBy { it.title.lowercase().trim() + "|" + it.author.lowercase().trim() }.size
                        val inLists = visible.sumOf { w -> w.sources.count { it != "Recherche" }.coerceAtLeast(1) }
                        Triple(visible.size, unique, inLists)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BIBLIOTHÈQUE", style = TextStyle(brush = Grad, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
                        Text("  ${fmtFull(stats.second)} fanfictions", color = Mut, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text(col?.let { collectionName(it) } ?: if (site == "ALL") "Tout" else site, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Tri : bouton discret, les choix s'ouvrent au toucher
                if (!selecting) Box {
                    var sortOpen by remember { mutableStateOf(false) }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable { sortOpen = true }, contentAlignment = Alignment.Center) {
                        Text("⇅", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(sortOpen, { sortOpen = false }, modifier = Modifier.background(Surf2)) {
                        listOf("A–Z", "Récent", "Crossovers d'abord").forEachIndexed { i, t ->
                            DropdownMenuItem(text = { Text((if (hub.sortMode == i) "✓  " else "     ") + t, color = if (hub.sortMode == i) Txt else Mut, fontWeight = if (hub.sortMode == i) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { hub.setSort(i); sortOpen = false })
                        }
                    }
                }
                if (!selecting) Spacer(Modifier.width(8.dp))
                if (!selecting) CircleIcon(Icons.Default.Search) { searching = !searching; if (!searching) q = "" }
                Spacer(Modifier.width(8.dp))
                CircleIcon(if (selecting) Icons.Default.Close else Icons.Default.Delete) { selecting = !selecting; sel.clear() }
            }
        }
        // Mode sélection : tout sélectionner / supprimer ; annuler, rétablir, tout restaurer
        if (selecting) item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterPill("Tout (${(if (col != null || searching || f != 0) list else visible).size})", false) {
                    val all = (if (col != null || searching || f != 0) list else visible).map { it.id }
                    if (sel.size >= all.size) sel.clear() else { sel.clear(); sel.addAll(all) } }
                Spacer(Modifier.weight(1f))
                Text("Supprimer (${sel.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(if (sel.isEmpty()) SolidColor(Surf2) else SolidColor(Color(0xFFE5484D)))
                        .clickable(enabled = sel.isNotEmpty()) { hub.hide(sel.toSet()); sel.clear() }.padding(horizontal = 16.dp, vertical = 9.dp))
            }
        }
        if (selecting && (hub.canUndo || hub.canRedo || hub.hidden.isNotEmpty())) item {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hub.canUndo) FilterPill("↶ Annuler", false) { hub.undo() }
                if (hub.canRedo) FilterPill("↷ Rétablir", false) { hub.redo() }
                if (hub.hidden.isNotEmpty()) FilterPill("Tout restaurer (${hub.hidden.size})", false) { hub.restoreAll() }
            }
        }
        item { SiteChips(hub) }
        if (adding) item {
            Column(Modifier.padding(top = 12.dp)) {
                SearchBox(link, "Colle le lien d'une fanfiction") { link = it }
                val okLink = Regex("archiveofourown\\.org/works/\\d+").containsMatchIn(link) || (link.contains("http") && Sites.find(link) != null)
                if (link.isNotBlank()) Text(if (okLink) "✓ Lien reconnu" else "✗ Lien non reconnu", color = if (okLink) Color(0xFF4ADE80) else Color(0xFFF87171),
                    fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
                Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { GradButton("Ajouter") { if (okLink) { val l = link; link = ""; adding = false; hub.startImport(l) } } }
            }
        }
        if (searching) item { Box(Modifier.padding(top = 12.dp)) { SearchBox(q) { q = it } } }
        if (col != null) item {
            Text("‹ Toutes les étagères", color = A1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp).clip(CircleShape).clickable { col = null }.padding(10.dp))
        }
        if ((listMode || searching) && !gridMode) item {
            LazyRow(Modifier.padding(vertical = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(filters) { i, s -> FilterPill(s, i == f) { f = i } }
            }
        }
        if (!listMode) {
            item { Spacer(Modifier.height(16.dp)) }
            if (shelves.isEmpty()) item { Empty("Vide. Configure un compte.") }
            items(shelves, key = { it.first }) { (k, v) ->
                BookShelf(if (site == "ALL") k else collectionName(k), v, onAll = { col = k }, isSel = { it.id in sel }) { w -> open(w) }
            }
        } else if (gridMode) {
            item { Spacer(Modifier.height(8.dp)) }
            items(list.chunked(12), key = { it.first().id }) { part -> BookRows(part, isSel = { it.id in sel }) { w -> open(w) } }
        } else {
            if (list.isEmpty()) item { Empty("Aucune fanfiction ici.") }
            items(list, key = { it.id }) { w ->
                Box(Modifier.animateItem()) {
                    WorkCard(w, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { open(w) }
                    if (w.id in sel) SelMark(Modifier.align(Alignment.TopEnd).padding(14.dp))
                }
            }
        }
    }
}

// ---------- Comptes ----------
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 26.dp, top = 26.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surf), content = content)
}

@Composable
private fun Line() { Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(Surf2)) }

@Composable
private fun SettingRow(lead: @Composable () -> Unit, title: String, sub: String? = null, subColor: Color = Mut, trail: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { lead() }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = Txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            sub?.let { Text(it, color = subColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trail?.invoke() ?: if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Mut) else Unit
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, c: Color = A1) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Switch(on: Boolean) {
    Box(Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(if (on) Grad else SolidColor(Surf2)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) { Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White)) }
}

@Composable
fun Accounts(hub: Hub) {
    var page by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 130.dp)) {
        Title("", page ?: "Réglages")
        // Réglages en pages : un menu qui tient sur l'écran, chaque rubrique s'ouvre à part
        if (page == null) {
            val tiles = listOf("Comptes" to Icons.Default.Person, "Synchronisation" to Icons.Default.Refresh, "Apparence" to Icons.Default.Star, "Commentaires & messages" to Icons.Default.Email, "Extensions" to Icons.Default.Add, "Application" to Icons.Default.Info)
            tiles.chunked(2).forEach { row ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (n, icon) ->
                        Column(Modifier.weight(1f).height(108.dp).clip(RoundedCornerShape(22.dp)).background(Surf).clickable { page = n }.padding(16.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            RoundIcon(icon)
                            Text(n, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else Text("‹ Réglages", color = A1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp).clip(CircleShape).clickable { page = null }.padding(10.dp))
        if (page == "Comptes") Group("Comptes") {
            SITE_UI.forEachIndexed { i, s ->
                if (i > 0) Line()
                val acc = hub.account(s.name)
                val st = hub.siteStatus[s.name]
                SettingRow({ SiteLogo(s.name, 40) }, s.name,
                    sub = st ?: acc?.let { "Connecté · " + it.substringAfterLast("/u/").trimEnd('/') } ?: "Non connecté",
                    subColor = when { st?.startsWith("Erreur") == true -> Color(0xFFEF4444); acc != null -> s.color; else -> Mut }) { hub.push(Screen.Setup(s.name)) }
            }
        }
        if (page == "Synchronisation") Group("Synchronisation") {
            // État en direct : étape en cours + résultat par site
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(if (hub.syncing) "En cours" else "À l'arrêt", color = if (hub.syncing) A1 else Mut, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                hub.syncInfo?.let { Text(it, color = Txt, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
                if (hub.syncing) androidx.compose.material3.LinearProgressIndicator(
                    progress = { hub.syncProgress ?: 0f }, color = A1, trackColor = Surf2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(CircleShape))
                (listOf("AO3") + Sites.all.map { it.name }).forEach { n -> hub.siteStatus[n]?.let { st ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(n, color = Mut, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Text(st, color = Txt, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } } }
            }
            Line()
            SettingRow({ RoundIcon(Icons.Default.Refresh) }, "Vérifier les nouveautés") { hub.startSync(quick = true) }
            Line()
            SettingRow({ RoundIcon(Icons.Default.Refresh, A2) }, "Synchronisation complète") { hub.startSync(full = true) }
        }
        if (page == "Apparence") Group("Apparence") {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("Noir", Color.Black, Color(0xFFF4F4F8)), Triple("Sombre", Color(0xFF17171F), Color(0xFFF4F4F8)), Triple("Clair", Color(0xFFF5F5F8), Color(0xFF15151B)))
                    .forEachIndexed { i, (name, bg, fg) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(bg)
                            .border(2.dp, if (themeMode == i) A1 else Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                            .clickable { hub.setAppTheme(i) }.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Grad))
                            Text(name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
            }
            Line()
            SettingRow({ RoundIcon(Icons.Default.Star) }, "Animation du livre", trail = { Switch(hub.bookAnim) }) { hub.toggleBookAnim(!hub.bookAnim) }
        }
        // Commentaires et messages : pages officielles des sites (connexion du navigateur intégré), sans mise en page de lecture
        if (page == "Commentaires & messages") Group("Commentaires & messages") {
            listOf("AO3" to "https://archiveofourown.org/users/${hub.user ?: "login"}/inbox", "Wattpad" to "https://www.wattpad.com/notifications",
                "FanFiction.net" to "https://www.fanfiction.net/pm2/inbox.php", "Spirit Fanfics" to "https://www.spiritfanfiction.com/notificacoes")
                .filter { Ext.on(it.first) }.forEach { (n, u) ->
                    SettingRow({ SiteLogo(n, 40) }, n, "Commentaires, réponses, messages") { hub.push(Screen.Web(u, "$n · messages", reader = false)) }
                }
        }
        if (page == "Extensions") Group("Extensions") {
            SettingRow({ RoundIcon(Icons.Default.Add) }, "Extensions", "${Ext.enabled.size} sites · ${Ext.installed.size} de la communauté") { hub.push(Screen.Extensions) }
        }
        if (page == "Application") Group("Application") {
            SettingRow({ RoundIcon(Icons.Default.Info) }, "Version ${hub.version}",
                if (hub.newVersion != null) "Version ${hub.newVersion} disponible" else "Touche pour vérifier",
                trail = { if (hub.newVersion != null) FilterPill(hub.updating?.let { "${(it * 100).toInt()} %" } ?: "Mettre à jour", true) { hub.startUpdate() } }) {
                if (hub.newVersion != null) hub.startUpdate() else hub.startCheck(manual = true)
            }
            DIAGS.forEach { (k, label) ->
                SettingRow({ RoundIcon(Icons.Default.Info) }, "Diagnostic · $label",
                    if (hub.diagRunning && hub.diagKind == k) "En cours… touche pour annuler" else if (hub.diagRunning) "Un autre diagnostic est en cours" else null) { if (!hub.diagRunning || hub.diagKind == k) hub.runDiag(k) }
            }
        }
    }
}

/** Configuration d'un site en 3 étapes numérotées. */
@Composable
fun Setup(hub: Hub, name: String) {
    val s = siteUi(name)
    val acc = hub.account(name)
    var v by remember(acc) { mutableStateOf(acc ?: "") }
    var check by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    @Composable
    fun Step(n: Int, title: String, body: String, content: @Composable () -> Unit) {
        Row(Modifier.padding(bottom = 14.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Surf).padding(18.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(s.color), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, color = Txt, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(body, color = Mut, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                content()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(s.color.copy(alpha = 0.55f), s.color.copy(alpha = 0.1f), Bg)))) {
                Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 76.dp, bottom = 18.dp)) {
                    SiteLogo(name, 64)
                    Text(s.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp))
                    Text(acc?.let { "Connecté · $it" } ?: s.desc, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    hub.siteStatus[name]?.let { Text(it, color = if (it.startsWith("Erreur")) Color(0xFFFCA5A5) else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            Column(Modifier.padding(20.dp).navigationBarsPadding()) {
                Text(s.how, color = Sub, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 18.dp))
                if (Sites.of(name)?.searchable != true) Step(1, "Se connecter sur le site", "Une page du vrai site s'ouvre. Connecte-toi avec ton e-mail et ton mot de passe (le bouton Google ne marche pas ici).") {
                    FilterPill("Ouvrir ${s.name}", true) { hub.push(Screen.Login(name)) }
                }
                val site0 = Sites.of(name)
                if (site0?.searchable == true) Step(1, "Trouve ton compte", "Tape ton pseudo ou ton nom, puis appuie sur ton profil dans la liste.") {
                    var results by remember { mutableStateOf<List<Account>>(emptyList()) }
                    var searching by remember { mutableStateOf(false) }
                    var searchErr by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(v) {
                        searchErr = null
                        if (v.trim().length < 2 || v == acc) { results = emptyList(); return@LaunchedEffect }
                        delay(600); searching = true
                        val r = kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { site0.searchUsers(v) } }
                        results = r?.getOrNull() ?: emptyList()
                        searchErr = when {
                            r == null -> "Le site ne répond pas (vérification anti-robot ?). Ouvre-le une fois ci-dessous, ou colle le lien de ton profil."
                            r.isFailure -> r.exceptionOrNull()?.message ?: "Recherche impossible"
                            else -> null
                        }
                        searching = false
                    }
                    Box(Modifier.offset(x = (-20).dp)) { SearchBox(v, "Pseudo ou nom") { v = it; check = null } }
                    when {
                        searching -> Text("Recherche…", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        searchErr != null -> Text("✗ $searchErr", color = Color(0xFFF87171), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                        v.trim().length >= 2 && v != acc && results.isEmpty() -> Text("✗ Aucun compte trouvé", color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    results.forEach { a ->
                        Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surf2)
                            .clickable {
                                if (checking) return@clickable
                                v = a.id; results = emptyList(); checking = true; check = null
                                hub.scope.launch {
                                    check = try { val r = site0.verify(a.id); hub.saveSiteUser(name, a.id); true to r } catch (e: Exception) { false to (e.message ?: "Vérification impossible") }
                                    checking = false
                                }
                            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (a.avatar.isNotEmpty()) NetImage(a.avatar, 40) else Box(Modifier.size(40.dp).clip(CircleShape).background(s.color), contentAlignment = Alignment.Center) {
                                Text(a.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(a.name.ifBlank { a.handle }, color = Txt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.handle + if (a.info.isNotEmpty()) " · ${a.info}" else "", color = Mut, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (checking) Text("Vérification…", color = Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    check?.let { (ok, msg) -> Text((if (ok) "✓ " else "✗ ") + msg, color = if (ok) Color(0xFF4ADE80) else Color(0xFFF87171), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
                    if (acc != null) Row(Modifier.padding(top = 10.dp)) { FilterPill("Retirer ce compte", false) { hub.saveSiteUser(name, null); check = null } }
                    if (name == "FanFiction.net") Text("Le site demande une vérification ? Ouvre-le une fois ici.", color = A1, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp).clickable { hub.push(Screen.Login(name)) })
                }
                else if (name != "AO3") Step(2, if (name == "FanFiction.net") "Ton profil" else "Ton pseudo", if (name == "FanFiction.net") "Colle le lien de ton profil." else "Exactement comme sur ton profil.") {
                    val site = Sites.of(name)!!
                    val fmtErr = if (v.isBlank()) null else site.format(v)
                    Box(Modifier.offset(x = (-20).dp)) { SearchBox(v, if (name == "FanFiction.net") "fanfiction.net/u/…" else "Pseudo") { v = it; check = null } }
                    fmtErr?.let { Text("✗ $it", color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    check?.let { (ok, msg) -> Text((if (ok) "✓ " else "✗ ") + msg, color = if (ok) Color(0xFF4ADE80) else Color(0xFFF87171), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPill(if (checking) "Vérification…" else "Vérifier et enregistrer", fmtErr == null && v.isNotBlank()) {
                            if (checking || v.isBlank() || fmtErr != null) return@FilterPill
                            checking = true; check = null
                            hub.scope.launch {
                                check = try { val r = site.verify(v); hub.saveSiteUser(name, v); true to r } catch (e: Exception) { false to (e.message ?: "Vérification impossible") }
                                checking = false
                            }
                        }
                        if (acc != null) FilterPill("Retirer", false) { hub.saveSiteUser(name, null); check = null }
                    }
                }
                Step(if (name == "AO3" || Sites.of(name)?.searchable == true) 2 else 3, "Synchroniser", "Récupère tes fanfictions. Tu peux changer de page pendant ce temps.") {
                    FilterPill(if (hub.syncing) "En cours…" else "Synchroniser", true) { hub.pickSite(name); hub.startSync(only = name); hub.pop(); hub.tab = 0 }
                }
                if (name == "AO3" && acc != null) GhostButton("Se déconnecter d'AO3") { hub.logout() }
            }
        }
        CircleIcon(Icons.AutoMirrored.Filled.ArrowBack, Modifier.statusBarsPadding().padding(14.dp)) { hub.pop() }
    }
}

// ---------- Connexion ----------
@Composable
fun Login(hub: Hub, site: String) {
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    fun finish(name: String?) {
        if (done) return
        done = true
        CookieManager.getInstance().flush()
        if (site != "AO3") { hub.pop(); return }
        hub.scope.launch { if (name != null) hub.saveUser(name) else hub.detectUser(); hub.pop(); hub.pop(); hub.pickSite("AO3"); hub.tab = 0; hub.startSync(only = "AO3") }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { hub.pop() }
            Text(if (site == "AO3") "Connexion à AO3" else site, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
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
                        if (site == "AO3" && !url.contains("/users/login") && (logged || (name != null && name != "login"))) finish(name?.takeIf { it != "login" })
                    }
                }
                loadUrl(if (site == "AO3") Ao3.BASE + "/users/login" else Sites.of(site)?.home ?: Ao3.BASE)
            }
        }, modifier = Modifier.fillMaxSize().navigationBarsPadding())
    }
}

// ---------- Fiche ----------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Detail(hub: Hub, id: String) {
    val w = hub.work(id) ?: return
    LaunchedEffect(id) { hub.ensureDetails(w) }
    val c = fandomColor(w.fandom)
    val sc = siteUi(w.site).color
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(c.copy(alpha = 0.6f), c.copy(alpha = 0.15f), Bg)))) {
                    Column(Modifier.statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 76.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) { SiteLogo(w.site, 26); Text(w.site, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
                        Text(w.fandoms.joinToString(" · ").uppercase(), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(w.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 37.sp, modifier = Modifier.padding(top = 10.dp))
                        if (w.author.isNotBlank()) Text("par ${w.author}", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
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
                    StatBox("Mots", if (w.words > 0) fmt(w.words) else "—", Modifier.weight(1f))
                    StatBox("Chapitres", w.chapters, Modifier.weight(1f))
                    StatBox("Kudos", fmt(w.kudos), Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    GradButton("Lire") {
                        if (w.site == "Wattpad") hub.scope.launch { hub.push(Screen.Web(Wattpad.firstPart(w.link) ?: w.link, w.title)) }
                        else hub.push(Screen.Web(w.link, w.title))
                    }
                    if (!hub.inLibrary(id)) { Spacer(Modifier.height(10.dp)); GhostButton("+ Ajouter à ma bibliothèque") { hub.addToLibrary(w) } }
                    if (w.summary.isNotBlank()) {
                        // Résumé seul ; traduit si la traduction auto est active. Toucher le texte bascule VO / FR.
                        var showFr by remember(id) { mutableStateOf(hub.autoTr) }
                        var pre by remember(id) { mutableStateOf<String?>(null) } // préchargé, jamais enregistré
                        LaunchedEffect(id) { runCatching { Translator.texts(listOf(w.summary)).first() }.onSuccess { pre = it } }
                        Text((if (showFr) pre else null) ?: w.summary, color = Sub, fontSize = 15.sp, lineHeight = 23.sp,
                            modifier = Modifier.padding(top = 22.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showFr = !showFr })
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


/** Réglages de lecture (thème, traduction automatique, taille) : affichés pendant la lecture, plus dans Réglages. */
@Composable
fun ReadingSettings(hub: Hub) {
    Column {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                THEMES.forEachIndexed { i, (name, bg, fg) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(bg))
                        .border(2.dp, if (hub.theme == i) A1 else Color.Gray.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).clickable { hub.setReader(t = i) }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aa", color = Color(fg), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(name, color = Color(fg).copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
            Line()
            SettingRow({ RoundIcon(Icons.Default.Info, A2) }, "Traduction automatique", trail = { Switch(hub.autoTr) }) { hub.toggleAutoTr(!hub.autoTr) }
            Line()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Taille du texte", color = Txt, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("A−", color = Txt, fontSize = 17.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size - 1) }.padding(12.dp))
                Text("${hub.size}", color = Mut, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Text("A+", color = Txt, fontSize = 21.sp, modifier = Modifier.clip(CircleShape).clickable { hub.setReader(s = hub.size + 1) }.padding(12.dp))
            }
            }
}


/** Extensions : sites intégrés activables + catalogue communautaire (dépôts GitHub). */
@Composable
fun ExtensionsScreen(hub: Hub) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { msg = Ext.refresh() }
    LazyColumn(Modifier.fillMaxSize().background(Bg), contentPadding = PaddingValues(bottom = 60.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(Icons.AutoMirrored.Filled.ArrowBack) { hub.pop() }
                Text("Extensions", color = Txt, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            }
        }
        item { Text("SITES INTÉGRÉS", color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 6.dp)) }
        items(ALL_SITE_UI) { s ->
            SettingRow({ SiteLogo(s.name, 40) }, s.name, s.desc, trail = { Switch(Ext.on(s.name)) }) { Ext.toggle(s.name) }
        }
        item { Text("COMMUNAUTÉ", color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 6.dp)) }
        items(Ext.installed.toList(), key = { "i" + it.id }) { e ->
            SettingRow({ Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(e.color)), contentAlignment = Alignment.Center) {
                Text(e.logo, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp) } }, e.name, "Installée · v${e.version} · touche pour retirer") { Ext.uninstall(e.id) }
        }
        items(Ext.catalog.filter { c -> Ext.installed.none { it.name == c.first } }, key = { "c" + it.second }) { (name, url) ->
            SettingRow({ RoundIcon(Icons.Default.Add) }, name, "Disponible · touche pour installer") { scope.launch { msg = Ext.install(url) } }
        }
        if (Ext.installed.isEmpty() && Ext.catalog.isEmpty()) item { Empty("Aucune extension dans les dépôts.") }
        item { Text("DÉPÔTS GITHUB", color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 6.dp)) }
        items(Ext.repos.toList()) { r ->
            Text(r.removePrefix("https://raw.githubusercontent.com/"), color = Sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { if (r != Ext.DEFAULT_REPO) Ext.removeRepo(r) }.padding(horizontal = 22.dp, vertical = 8.dp))
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                SearchBox(repo, "Lien de l'index.json d'un dépôt") { repo = it }
                Spacer(Modifier.height(10.dp))
                GhostButton("Ajouter le dépôt") { Ext.addRepo(repo); repo = ""; scope.launch { msg = Ext.refresh() } }
                msg?.let { Text(it, color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

/** 1744 → « 1 744 » (séparateur des milliers). */
fun fmtFull(n: Int) = "%,d".format(n).replace(',', '\u202F')
