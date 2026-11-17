package app.fanfichub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Paper = Color(0xFFF3EBDD)
private const val DEPTH = 12 // profondeur 3D (dp)

private data class BookSpec(val site: String, val w: Int, val h: Int)
private val BOOKS = listOf(BookSpec("AO3", 60, 232), BookSpec("Wattpad", 56, 214), BookSpec("FanFiction.net", 62, 240), BookSpec("Spirit Fanfics", 54, 206))

private fun Color.shade(f: Float) = lerp(this, Color.Black, f)
private fun Color.light(f: Float) = lerp(this, Color.White, f)

/** Volume de manga vu de 3/4 : tranche arrondie, dessus (pages) et côté ombré. */
@Composable
private fun MangaSpine(site: String, w: Int, h: Int, vol: Int) {
    val s = siteUi(site)
    Box(Modifier.size((w + DEPTH).dp, (h + DEPTH).dp)) {
        Canvas(Modifier.matchParentSize()) {
            val d = DEPTH.dp.toPx(); val W = w.dp.toPx(); val H = size.height
            // Dessus : tranche des pages
            drawPath(Path().apply { moveTo(0f, d); lineTo(d, 0f); lineTo(W + d, 0f); lineTo(W, d); close() }, Paper)
            for (i in 1..7) { val x = W * i / 8f; drawLine(Color(0x26000000), Offset(x, d), Offset(x + d, 0f), 1.2f) }
            // Côté droit, dans l'ombre
            drawPath(Path().apply { moveTo(W, d); lineTo(W + d, 0f); lineTo(W + d, H - d); lineTo(W, H); close() }, s.color.shade(0.55f))
            // Tranche avant arrondie (reflet)
            drawRect(Brush.horizontalGradient(listOf(s.color.shade(0.35f), s.color.light(0.18f), s.color, s.color.shade(0.3f)), startX = 0f, endX = W),
                Offset(0f, d), Size(W, H - d))
            // Bandeau blanc (haut) et bandeau éditeur noir (bas), typiques des mangas
            drawRect(Brush.horizontalGradient(listOf(Color(0xFFDCD6CB), Color(0xFFFBF8F2), Color(0xFFDCD6CB))), Offset(0f, d), Size(W, (H - d) * 0.17f))
            drawRect(Color(0xFF141414), Offset(0f, H - (H - d) * 0.09f), Size(W, (H - d) * 0.09f))
            drawLine(Color(0xFFE8C26A), Offset(0f, d + (H - d) * 0.17f), Offset(W, d + (H - d) * 0.17f), 2.5f)
        }
        Column(Modifier.offset(y = DEPTH.dp).size(w.dp, h.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height((h * 0.17f).dp), contentAlignment = Alignment.Center) { SiteLogo(site, (w * 0.52f).toInt()) }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (site == "FanFiction.net") "FanFiction" else site.substringBefore(' '), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black,
                    softWrap = false, maxLines = 1, style = TextStyle(letterSpacing = 1.sp),
                    modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = 90f })
            }
            Box(Modifier.padding(bottom = 6.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                Text("$vol", color = s.color.shade(0.3f), fontSize = if (vol > 99) 9.sp else 12.sp, fontWeight = FontWeight.Black)
            }
            Box(Modifier.height((h * 0.09f).dp), contentAlignment = Alignment.Center) {
                Text("FH", color = Color(0xFFE8C26A), fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Planche en bois en 3D. */
@Composable
private fun Plank(modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(46.dp)) {
        val d = DEPTH.dp.toPx(); val front = 18.dp.toPx(); val W = size.width
        drawPath(Path().apply { moveTo(0f, d); lineTo(d, 0f); lineTo(W, 0f); lineTo(W - d, d); close() }, Brush.verticalGradient(listOf(Color(0xFF9A6B45), Color(0xFF7E5535))))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6E4A2F), Color(0xFF4A3120)), startY = d, endY = d + front), Offset(0f, d), Size(W - d, front))
        for (i in 0..5) { val y = d + front * (0.25f + i * 0.13f); drawLine(Color(0x22000000), Offset(W * 0.05f * i, y), Offset(W - d - W * 0.04f * i, y), 1f) }
        drawRect(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent), startY = d + front, endY = size.height), Offset(0f, d + front), Size(W - d, size.height - d - front))
    }
}

/** Couverture du manga au sol, vue en perspective, qui s'ouvre. */
@Composable
private fun OpenManga(site: String, vol: Int, open: Float, bounce: Float, modifier: Modifier) {
    val s = siteUi(site)
    Box(modifier.graphicsLayer {
        rotationX = 50f; cameraDistance = 14f * density
        scaleX = 0.88f + 0.12f * bounce; scaleY = 0.88f + 0.12f * bounce
        translationX = size.width * 0.5f * open
    }) {
        Column(Modifier.width(132.dp).height(186.dp).clip(RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)).background(Paper).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(11) { i -> Box(Modifier.fillMaxWidth(if (i % 4 == 3) 0.55f else 1f).height(3.dp).clip(CircleShape).background(Color(0x30000000))) }
        }
        val back = open > 0.5f
        Box(Modifier.width(132.dp).height(186.dp).graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -178f * open; cameraDistance = 14f * density
        }.clip(RoundedCornerShape(5.dp))
            .background(if (back) Brush.linearGradient(listOf(Paper, Color(0xFFE3D6C0))) else Brush.linearGradient(listOf(s.color.light(0.1f), s.color.shade(0.35f))))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(5.dp))) {
            if (!back) {
                Box(Modifier.fillMaxWidth().height(30.dp).background(Color(0xFFF7F3EA)), contentAlignment = Alignment.Center) {
                    Text("FANFIC HUB", color = Color(0xFF141414), fontSize = 11.sp, fontWeight = FontWeight.Black, style = TextStyle(letterSpacing = 2.sp))
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    SiteLogo(site, 58)
                    Text(s.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 10.dp))
                }
                Text("VOL. $vol", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF141414)).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

/**
 * Scène : étagère avec un manga par site. Toucher un volume (ou [auto]) le fait pencher, tomber,
 * rebondir puis s'ouvrir au sol ; [onFinished] est appelé ensuite.
 */
@Composable
private fun ShelfScene(counts: Map<String, Int>, auto: String?, header: Boolean, onAll: (() -> Unit)?, onFinished: (String) -> Unit) {
    var falling by remember { mutableStateOf(auto) }
    var landed by remember { mutableStateOf(false) }
    val tilt = remember { Animatable(0f) }
    val fall = remember { Animatable(0f) }
    val open = remember { Animatable(0f) }
    val bounce = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val dens = LocalDensity.current
    LaunchedEffect(falling) {
        val f = falling ?: return@LaunchedEffect
        tilt.animateTo(1f, tween(420, easing = FastOutSlowInEasing))                      // il penche
        fall.animateTo(1f, tween(600, easing = CubicBezierEasing(0.55f, 0f, 1f, 0.5f)))  // gravité
        landed = true
        bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 520f))
        open.animateTo(1f, tween(780, easing = FastOutSlowInEasing))
        delay(420)
        fade.animateTo(0f, tween(240))
        onFinished(f)
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Bg)
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { if (auto != null) onFinished(auto) }) {
        val shelfTop: Dp = if (header) 150.dp else maxHeight * 0.18f
        val booksBottom = shelfTop + 250.dp
        val floorY = booksBottom + 190.dp
        if (header) Column(Modifier.statusBarsPadding().padding(start = 22.dp, top = 22.dp)) {
            Text("FANFIC HUB", style = TextStyle(brush = Grad, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
            Text("Choisis un manga", color = Txt, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
            Text("Chaque volume est un site. Touche-le pour l'ouvrir.", color = Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        // Lueur au sol
        val glowColor = siteUi(falling).color
        Box(Modifier.offset(y = floorY - 40.dp).fillMaxWidth().height(220.dp).graphicsLayer { alpha = if (landed) 0.3f + 0.7f * open.value else 0f }
            .background(Brush.radialGradient(listOf(glowColor.copy(alpha = 0.5f), Color.Transparent))))
        // Planche
        Plank(Modifier.offset(y = booksBottom - DEPTH.dp).padding(horizontal = 18.dp))
        // Volumes
        Row(Modifier.fillMaxWidth().offset(y = booksBottom - 252.dp).height(252.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom) {
            BOOKS.forEach { b ->
                val me = b.site == falling
                Box(Modifier.graphicsLayer {
                    if (me) {
                        if (landed) alpha = 0f
                        transformOrigin = TransformOrigin(1f, 1f)
                        rotationZ = 16f * tilt.value + 74f * fall.value
                        translationY = with(dens) { (floorY - booksBottom).toPx() } * fall.value
                        translationX = with(dens) { 36.dp.toPx() } * fall.value
                    }
                }.clickable(enabled = falling == null, indication = null, interactionSource = remember { MutableInteractionSource() }) { falling = b.site }) {
                    MangaSpine(b.site, b.w, b.h, counts[b.site] ?: 0)
                }
            }
        }
        if (landed) falling?.let { f ->
            OpenManga(f, counts[f] ?: 0, open.value, bounce.value, Modifier.align(Alignment.TopCenter).offset(x = (-66).dp, y = floorY - 120.dp))
            Text(siteUi(f).name, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = floorY - 190.dp - 26.dp * open.value).graphicsLayer { alpha = open.value })
        }
        if (onAll != null && falling == null) Text("Tout voir", color = A1, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).clip(CircleShape).clickable { onAll() }.padding(14.dp))
    }
}

/** Accueil : l'étagère de mangas. */
@Composable
fun Shelf(hub: Hub) {
    val counts = SITE_UI.associate { s -> s.name to hub.works.count { it.site == s.name } }
    ShelfScene(counts, auto = null, header = true, onAll = { hub.showShelf = false; hub.pickSite("ALL"); hub.tab = 0 }) { site ->
        hub.showShelf = false
        hub.pickSite(site)
        hub.tab = 0
        if (hub.account(site) == null) hub.push(Screen.Setup(site))
    }
}

/** Animation quand on change de site depuis les logos. */
@Composable
fun BookIntro(site: String, onDone: () -> Unit) {
    ShelfScene(emptyMap(), auto = site, header = false, onAll = null) { onDone() }
}

/** Étagère de l'accueil : toujours visible ; toucher un volume le fait tomber puis ouvre le site. */
@Composable
fun ShelfStrip(hub: Hub) {
    Box(Modifier.fillMaxWidth().height(300.dp).padding(top = 8.dp)) {
        Plank(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp))
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
            BOOKS.forEach { b ->
                Box(Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { hub.openSite(b.site) }) {
                    MangaSpine(b.site, b.w, b.h, hub.works.count { it.site == b.site })
                }
            }
        }
    }
}
