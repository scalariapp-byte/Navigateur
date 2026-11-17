package app.fanfichub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.RowScope

private val Wood = Color(0xFF4A3426)
private val Paper = Color(0xFFF3EBDD)

/** Le livre du site tombe de l'étagère, rebondit, puis s'ouvre au sol. */
@Composable
fun BookIntro(site: String, onDone: () -> Unit) {
    val s = siteUi(site)
    val tilt = remember { Animatable(0f) }
    val fall = remember { Animatable(0f) }
    val open = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(site) {
        tilt.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        fall.animateTo(1f, tween(560, easing = CubicBezierEasing(0.55f, 0f, 1f, 0.45f))) // gravité
        landed = true
        coroutineScope {
            launch { open.animateTo(1f, tween(760, delayMillis = 160, easing = FastOutSlowInEasing)) }
        }
        delay(380)
        fade.animateTo(0f, tween(240))
        onDone()
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Bg)
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDone() }) {
        val w = maxWidth
        val shelfY = maxHeight * 0.30f
        val floorY = maxHeight * 0.66f

        // Lueur au sol, qui s'allume à l'impact
        Box(Modifier.offset(y = floorY + 70.dp).fillMaxWidth().height(160.dp).graphicsLayer { alpha = if (landed) 0.35f + 0.65f * open.value else 0f }
            .background(Brush.radialGradient(listOf(s.color.copy(alpha = 0.45f), Color.Transparent))))
        Box(Modifier.offset(y = floorY + 150.dp).fillMaxWidth().height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, s.color.copy(alpha = 0.6f), Color.Transparent))))

        // Étagère et livres voisins
        val neighbours = listOf(Triple(0.16f, 96, Color(0xFF6B4E9B)), Triple(0.24f, 118, Color(0xFF2F6F73)), Triple(0.32f, 104, Color(0xFF8A3B3B)),
            Triple(0.40f, 124, Color(0xFF3B4F8A)), Triple(0.48f, 100, Color(0xFF7A6A3A)), Triple(0.70f, 112, Color(0xFF5A3B6B)), Triple(0.78f, 92, Color(0xFF2F5A3B)))
        neighbours.forEach { (x, h, c) ->
            Box(Modifier.offset(x = w * x, y = shelfY - h.dp).width(24.dp).height(h.dp).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.7f), c))))
        }
        Box(Modifier.offset(x = w * 0.1f, y = shelfY).width(w * 0.8f).height(12.dp).clip(RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(listOf(Wood.copy(alpha = 1f), Color(0xFF2E2018)))))

        if (!landed) {
            // Le livre du site, vu par la tranche : il penche, glisse puis tombe en tournoyant
            val bookH = 126.dp
            Box(Modifier.offset(x = w * 0.58f + 26.dp * tilt.value + 40.dp * fall.value, y = shelfY - bookH + (floorY - shelfY + 80.dp) * fall.value)
                .width(30.dp).height(bookH)
                .graphicsLayer { transformOrigin = TransformOrigin(1f, 1f); rotationZ = 22f * tilt.value + 210f * fall.value }
                .clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(s.color, s.color.copy(alpha = 0.75f), s.color))),
                contentAlignment = Alignment.Center) {
                Text(s.logo, color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.graphicsLayer { rotationZ = 90f })
            }
        } else {
            // Au sol, vu en perspective : rebond puis ouverture de la couverture
            val bounce = remember { Animatable(0f) }
            LaunchedEffect(Unit) { bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
            Box(Modifier.align(Alignment.TopCenter).offset(y = floorY).graphicsLayer {
                rotationX = 48f; cameraDistance = 14f * density
                scaleX = 0.9f + 0.1f * bounce.value; scaleY = 0.9f + 0.1f * bounce.value
                translationX = size.width * 0.25f * open.value // recentre le livre ouvert
            }) {
                // Pages de droite (sous la couverture)
                Column(Modifier.width(130.dp).height(170.dp).clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)).background(Paper).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(10) { i -> Box(Modifier.fillMaxWidth(if (i % 4 == 3) 0.6f else 1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33000000))) }
                }
                // Couverture : pivote autour de la reliure ; on voit son revers (papier) après 90°
                val back = open.value > 0.5f
                Box(Modifier.width(130.dp).height(170.dp).graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -178f * open.value; cameraDistance = 14f * density
                }.clip(RoundedCornerShape(6.dp)).background(if (back) Brush.linearGradient(listOf(Paper, Color(0xFFE4D8C3))) else Brush.linearGradient(listOf(s.color, s.color.copy(alpha = 0.7f))))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    if (!back) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SiteLogo(site, 54)
                        Text(s.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    } else Column(Modifier.fillMaxSize().padding(14.dp).graphicsLayer { rotationY = 180f }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        repeat(10) { i -> Box(Modifier.fillMaxWidth(if (i % 3 == 2) 0.7f else 1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33000000))) }
                    }
                }
            }
            // Le nom du site s'élève de la page
            Text(s.name, color = Txt, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = floorY - 70.dp - 30.dp * open.value).graphicsLayer { alpha = open.value })
        }
    }
}

/** Accueil : une bibliothèque en bois avec un livre par site. Toucher un livre le fait tomber et s'ouvrir. */
@Composable
fun Shelf(hub: Hub) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val w = maxWidth
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("FANFIC HUB", style = androidx.compose.ui.text.TextStyle(brush = Grad, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp),
                modifier = Modifier.padding(top = 26.dp))
            Text("Choisis un livre", color = Txt, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
            Text("Chaque livre est un site. Touche-le pour l'ouvrir.", color = Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 26.dp))

            // Meuble : deux planches
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF2A1C14), Color(0xFF1C130D))))
                .padding(horizontal = 14.dp, vertical = 16.dp)) {
                ShelfRow {
                    Deco(18, 150, Color(0xFF5A3B6B)); Deco(14, 170, Color(0xFF2F5A3B))
                    SITE_UI.take(2).forEach { SiteBook(hub, it.name, 210) }
                    Deco(16, 160, Color(0xFF8A3B3B))
                }
                Plank()
                Spacer(Modifier.height(22.dp))
                ShelfRow {
                    Deco(16, 165, Color(0xFF3B4F8A))
                    SITE_UI.drop(2).forEach { SiteBook(hub, it.name, 200) }
                    AllBook(hub)
                }
                Plank()
            }
            Spacer(Modifier.weight(1f))
            Text("Comptes, réglages et synchro : dans l'onglet Comptes une fois un livre ouvert.", color = Mut, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ShelfRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(220.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), content = content)
}

@Composable
private fun Plank() {
    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(3.dp)).background(Brush.verticalGradient(listOf(Color(0xFF6B4A33), Wood, Color(0xFF2E2018)))))
}

@Composable
private fun Deco(width: Int, height: Int, c: Color) {
    Box(Modifier.width(width.dp).height(height.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), c.copy(alpha = 0.35f), c.copy(alpha = 0.55f)))))
}

@Composable
private fun Spine(name: String, color: Brush, height: Int, sub: String, logo: (@Composable () -> Unit)?, onClick: () -> Unit) {
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(Modifier.width(58.dp).height(height.dp).graphicsLayer { translationY = -14f * press.value * density }
        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(color)
        .clickable { scope.launch { press.animateTo(1f, tween(140)); onClick(); press.snapTo(0f) } },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).background(Color(0xFFE8C26A).copy(alpha = 0.8f)))
        Box(Modifier.padding(top = 8.dp)) { logo?.invoke() }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, softWrap = false, maxLines = 1,
                modifier = Modifier.wrapContentSize(unbounded = true).graphicsLayer { rotationZ = -90f })
        }
        Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
        Box(Modifier.padding(bottom = 10.dp).fillMaxWidth().height(3.dp).background(Color(0xFFE8C26A).copy(alpha = 0.8f)))
    }
}

@Composable
private fun SiteBook(hub: Hub, site: String, height: Int) {
    val s = siteUi(site)
    Spine(if (site == "FanFiction.net") "FF.net" else site.substringBefore(' '), Brush.horizontalGradient(listOf(s.color.copy(alpha = 0.75f), s.color, s.color.copy(alpha = 0.7f))),
        height, "${hub.works.count { it.site == site }}", { SiteLogo(site, 34) }) {
        hub.showShelf = false
        hub.pickSite(site, animate = true)
        hub.tab = 0
        if (hub.account(site) == null) hub.push(Screen.Setup(site))
    }
}

@Composable
private fun AllBook(hub: Hub) {
    Spine("Tout", Grad, 180, "${hub.works.size}", null) { hub.showShelf = false; hub.pickSite("ALL"); hub.tab = 0 }
}
