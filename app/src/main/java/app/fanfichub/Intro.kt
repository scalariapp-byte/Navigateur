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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.sin
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.delay

private val Paper = Color(0xFFF3EBDD)
private const val DEPTH = 12 // profondeur 3D (dp)

private data class BookSpec(val site: String, val w: Int, val h: Int)
private val BOOKS = listOf(BookSpec("AO3", 60, 232), BookSpec("Wattpad", 56, 214), BookSpec("FanFiction.net", 62, 240), BookSpec("Spirit Fanfics", 54, 206))

private fun Color.shade(f: Float) = lerp(this, Color.Black, f)
private fun Color.light(f: Float) = lerp(this, Color.White, f)

/** Couleur de la couverture rigide : presque noire, teintée par le site. */
fun boardColor(c: Color) = lerp(Color(0xFF141116), c, 0.13f)
fun spineTitle(site: String) = when (site) { "FanFiction.net" -> "FANFICTION"; "Spirit Fanfics" -> "SPIRIT"; else -> site.uppercase() }

/** Volume « édition prestige » vu de 3/4 : couverture rigide sombre, tranches teintées, titre espacé. */
@Composable
private fun MangaSpine(site: String, w: Int, h: Int, vol: Int) {
    val s = siteUi(site)
    val board = boardColor(s.color)
    val edge = lerp(s.color, Color.Black, 0.35f)
    val accent = lerp(s.color, Color.White, 0.25f)
    Box(Modifier.size((w + DEPTH).dp, (h + DEPTH).dp)) {
        Canvas(Modifier.matchParentSize()) {
            val d = DEPTH.dp.toPx(); val W = w.dp.toPx(); val H = size.height
            val lip = 2.5f * density // la couverture dépasse des pages
            // Dessus : couverture + tranche des pages teintée
            drawPath(Path().apply { moveTo(0f, d); lineTo(d, 0f); lineTo(W + d, 0f); lineTo(W, d); close() }, board.shade(0.2f))
            drawPath(Path().apply { moveTo(lip * 1.5f, d - lip * 0.3f); lineTo(d, lip); lineTo(W + d - lip, lip); lineTo(W - lip * 0.5f, d - lip * 0.3f); close() },
                Brush.horizontalGradient(listOf(edge.shade(0.2f), edge, edge.shade(0.3f))))
            for (i in 1..9) { val x = W * i / 10f; drawLine(Color(0x22000000), Offset(x, d - lip * 0.3f), Offset(x + d - lip, lip), 1f) }
            // Côté : plat de la couverture, dans l'ombre
            drawPath(Path().apply { moveTo(W, d); lineTo(W + d, 0f); lineTo(W + d, H - d); lineTo(W, H); close() }, board.shade(0.45f))
            // Dos arrondi (reflet mat)
            drawRect(Brush.horizontalGradient(listOf(board.shade(0.5f), board.light(0.1f), board, board.shade(0.55f)), startX = 0f, endX = W), Offset(0f, d), Size(W, H - d))
        }
        Column(Modifier.offset(y = DEPTH.dp).size(w.dp, h.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$vol", color = accent, fontSize = if (vol > 999) 10.sp else 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            Box(Modifier.padding(top = 4.dp).size(width = 14.dp, height = 1.dp).background(accent.copy(alpha = 0.7f)))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(spineTitle(site), color = Color(0xFFF2EEE8), fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    softWrap = false, maxLines = 1, style = TextStyle(letterSpacing = if (site == "FanFiction.net") 3.sp else 6.sp),
                    modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = 90f })
            }
            Text("FH", color = Color(0xFFF2EEE8), fontSize = 10.sp, fontWeight = FontWeight.Black)
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
    val bounce = remember { Animatable(0f) } // rebond à l'impact (oscille autour de 0)
    val fade = remember { Animatable(1f) }
    val dens = LocalDensity.current
    var start by remember { mutableStateOf(Offset.Zero) }
    val hover = androidx.compose.animation.core.rememberInfiniteTransition(label = "hover").animateFloat(0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(tween(4200, easing = androidx.compose.animation.core.LinearEasing)), label = "t")
    LaunchedEffect(falling) {
        val f = falling ?: return@LaunchedEffect
        tilt.animateTo(1f, tween(420, easing = FastOutSlowInEasing))                      // il penche
        fall.animateTo(1f, tween(600, easing = CubicBezierEasing(0.55f, 0f, 1f, 0.5f)))  // gravité
        landed = true
        bounce.animateTo(0f, spring(dampingRatio = 0.32f, stiffness = 420f), initialVelocity = 6f)
        open.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
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
                        if (fall.value > 0f || landed) alpha = 0f
                        transformOrigin = TransformOrigin(1f, 1f)
                        rotationZ = 16f * tilt.value
                    }
                }.onGloballyPositioned { c -> if (b.site == (falling ?: auto)) start = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f) }.clickable(enabled = falling == null, indication = null, interactionSource = remember { MutableInteractionSource() }) { falling = b.site }) {
                    MangaSpine(b.site, b.w, b.h, counts[b.site] ?: 0)
                }
            }
        }
        val fs = falling
        if (fs != null && (fall.value > 0f || landed)) {
            val spec = BOOKS.first { it.site == fs }
            val s = siteUi(fs)
            val floorPx = with(dens) { floorY.toPx() }
            val t: Float = hover.value
            Canvas(Modifier.fillMaxSize()) {
                val d = density
                val f = fall.value
                val g = f * f // chute accélérée
                val Hh = spec.h * d; val Ww = Hh * 0.7f; val Tt = spec.w * d
                val endScale = 0.72f
                val sc = 1f + (endScale - 1f) * f
                val shift = Ww * endScale * 0.5f * open.value // la reliure se recentre à l'ouverture
                val hoverOn = if (landed) 1f else 0f
                drawBook3D(s.color, s.logo, s.name, BookPose(
                    cx = start.x + (size.width / 2f + shift - start.x) * f,
                    cy = start.y + (floorPx - start.y) * g - bounce.value * 14f * d - hoverOn * sin(t * 1.3f) * 5f * d,
                    rx = 54f * f + hoverOn * sin(t) * 3f,
                    ry = -90f + 82f * f + hoverOn * sin(t * 0.8f) * 7f,
                    rz = -28f * sin(f * Math.PI.toFloat()) * (1f - open.value),
                    scale = sc, open = open.value,
                ), Ww, Hh, Tt)
            }
            if (landed) Text(s.name, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = floorY + 150.dp - 16.dp * open.value).graphicsLayer { alpha = open.value })
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
fun BookIntro(site: String, counts: Map<String, Int>, onDone: () -> Unit) {
    ShelfScene(counts, auto = site, header = false, onAll = null) { onDone() }
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
