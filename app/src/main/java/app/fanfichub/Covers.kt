package app.fanfichub

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

/** Cache d'images : mémoire + disque (les couvertures ne sont téléchargées qu'une fois). */
object Img {
    lateinit var dir: File
    private val mem = LruCache<String, ImageBitmap>(120)
    fun cached(url: String): ImageBitmap? = mem.get(url)
    private fun file(url: String) = File(dir, Integer.toHexString(url.hashCode()))

    /** Préchargement : télécharge les couvertures manquantes sur le disque (6 à la fois), puis met les premières en mémoire. */
    suspend fun prefetch(urls: List<String>, warm: Int = 40) = withContext(Dispatchers.IO) {
        val list = urls.filter { it.isNotBlank() }.distinct()
        val sem = kotlinx.coroutines.sync.Semaphore(6)
        kotlinx.coroutines.coroutineScope {
            list.filter { !file(it).exists() }.forEach { u ->
                launch { sem.acquire(); try { runCatching { file(u).writeBytes(URL(u).readBytes()) } } finally { sem.release() } }
            }
        }
        list.take(warm).forEach { load(it) }
    }
    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        mem.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = file(url)
                val bytes = if (f.exists()) f.readBytes() else URL(url).readBytes().also { f.writeBytes(it) }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var ss = 1; while (o.outWidth / ss > 400) ss *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })?.asImageBitmap()
            }.getOrNull()
        }?.also { mem.put(url, it) }
    }
}

@Composable
fun NetImage(url: String, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Surf2)) { NetFill(url) }
}

@Composable
fun NetFill(url: String, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    val bmp by produceState(Img.cached(url), url) { if (value == null) value = Img.load(url) }
    bmp?.let { Image(it, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = alignment) }
}

/** Écarte les images par défaut (bulle FanFiction.net, avatars) : seule une vraie couverture d'histoire est affichée. */
@Volatile var dupCovers: Set<String> = emptySet()
/** Une même image sur 3 histoires ou plus = image par défaut du site. */
fun refreshDupCovers(ws: List<Work>) { dupCovers = ws.groupingBy { it.cover }.eachCount().filter { it.key.isNotBlank() && it.value >= 3 }.keys }
fun goodCover(u: String) = u.isNotBlank() && "/img/common/" !in u && ("fanfiction.net" !in u && "fictionpress" !in u || Regex("/image/\\d+/").containsMatchIn(u))

/** Couverture : vraie image si le site en fournit une, sinon couverture générée aux couleurs du fandom. */
@Composable
fun BookCover(w: Work, width: Int, modifier: Modifier = Modifier) {
    val c = fandomColor(w.fandom)
    val h = (width * 1.5f).toInt()
    Box(modifier.width(width.dp).height(h.dp).clip(RoundedCornerShape(5.dp))
        .background(Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.45f), Color(0xFF15151E))))) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Box(Modifier.size(width = 3.dp, height = 22.dp).background(Color.White.copy(alpha = 0.7f)))
            // Le titre cède la place : l'auteur reste toujours visible en bas
            Text(w.title, color = Color.White, fontSize = (width / 8f).coerceIn(10f, 14f).sp, fontWeight = FontWeight.Bold, lineHeight = (width / 6.5f).coerceIn(12f, 17f).sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).weight(1f))
            Text(w.author, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (goodCover(w.cover) && w.site == "Spirit Fanfics") SpiritFace(w, width)
        else if (w.cover.startsWith("montage:")) Montage(w, width)
        else if (goodCover(w.cover)) NetFill(w.cover)
        // Reliure + reflet
        Box(Modifier.fillMaxHeight().width(5.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        if (w.newChapters > 0) Text("+${w.newChapters}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape).background(Grad).padding(horizontal = 7.dp, vertical = 2.dp))
        if (w.progress > 0.01f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(w.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Grad))
        }
    }
}

/**
 * Spirit fournit des images en paysage : au lieu de les rogner en portrait, couverture rigide sombre
 * avec l'illustration encadrée en haut, puis le titre et l'auteur (style édition prestige).
 */
@Composable
private fun SpiritFace(w: Work, width: Int) {
    val board = boardColor(fandomColor(w.fandom))
    val gold = Color(0xFFD9C08A)
    Column(Modifier.fillMaxSize().background(board).padding(start = 7.dp, end = 5.dp, top = 7.dp, bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.45f).border(1.dp, gold.copy(alpha = 0.6f)).padding(2.dp)) { NetFill(w.cover) }
        Box(Modifier.padding(top = 5.dp).align(Alignment.CenterHorizontally).size(width = (width * 0.3f).dp, height = 1.dp).background(gold.copy(alpha = 0.7f)))
        Text(w.title, color = Color(0xFFF2EEE8), fontSize = (width / 9f).coerceIn(9f, 13f).sp, lineHeight = (width / 7.5f).coerceIn(11f, 16f).sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Spacer(Modifier.weight(1f))
        Text(w.author.uppercase(), color = gold, fontSize = 7.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Couverture montée façon page de manga : personnage principal en plein cadre, cases inclinées liserées de blanc
 * pour les autres, lignes de vitesse, bandeau des fandoms et titre en bas. Unique par histoire.
 */
@Composable
private fun Montage(w: Work, width: Int) {
    val urls = w.cover.removePrefix("montage:").split('|').filter { it.isNotBlank() }
    val accent = fandomColor(w.fandom)
    val fandoms = fandomKey(w).removePrefix("Crossover : ").uppercase()
    val flip = (w.id.hashCode() and 1) == 1 // mise en page miroir une fois sur deux
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))) {
        // 1. Personnage principal en plein cadre
        urls.getOrNull(0)?.let { NetFill(it, alignment = Alignment.TopCenter) }
        // Lignes de vitesse (manga)
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = androidx.compose.ui.geometry.Offset(if (flip) size.width * 0.25f else size.width * 0.75f, size.height * 0.3f)
            for (k in 0 until 28) {
                val a = k * (Math.PI * 2 / 28).toFloat(); val r0 = size.maxDimension * 0.55f; val r1 = size.maxDimension * 1.2f
                drawLine(Color.White.copy(alpha = 0.07f), c + androidx.compose.ui.geometry.Offset(kotlin.math.cos(a) * r0, kotlin.math.sin(a) * r0),
                    c + androidx.compose.ui.geometry.Offset(kotlin.math.cos(a) * r1, kotlin.math.sin(a) * r1), 2f)
            }
        }
        // 2. Case inclinée en bas (2e personnage), liseré blanc
        urls.getOrNull(1)?.let { u ->
            val panel = androidx.compose.foundation.shape.GenericShape { sz, _ ->
                if (flip) { moveTo(0f, sz.height * 0.48f); lineTo(sz.width * 0.62f, sz.height * 0.6f); lineTo(sz.width * 0.62f, sz.height); lineTo(0f, sz.height) }
                else { moveTo(sz.width * 0.38f, sz.height * 0.6f); lineTo(sz.width, sz.height * 0.48f); lineTo(sz.width, sz.height); lineTo(sz.width * 0.38f, sz.height) }
                close()
            }
            Box(Modifier.fillMaxSize().clip(panel).background(Color.White).padding(start = if (flip) 0.dp else 2.dp, end = if (flip) 2.dp else 0.dp, top = 2.dp)
                .clip(panel)) { NetFill(u, alignment = Alignment.TopCenter) }
        }
        // 3. Petite case en médaillon (3e personnage)
        urls.getOrNull(2)?.let { u ->
            Box(Modifier.align(if (flip) Alignment.TopEnd else Alignment.TopStart).padding(top = 22.dp, start = 6.dp, end = 6.dp)
                .size((width * 0.36f).dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape)) { NetFill(u) }
        }
        // Lumière colorée + assombrissement bas pour le titre
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent, Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
        // Bandeau des fandoms
        Text(fandoms, color = Color.White, fontSize = 6.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 3.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(7.dp)) {
            Box(Modifier.size(width = 20.dp, height = 2.dp).background(accent))
            Text(w.title.uppercase(), color = Color.White, fontSize = (width / 10f).coerceIn(8f, 12f).sp, fontWeight = FontWeight.Black, maxLines = 3,
                overflow = TextOverflow.Ellipsis, lineHeight = (width / 8f).coerceIn(10f, 15f).sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 4.dp))
            if (w.author.isNotBlank()) Text(w.author, color = accent.copy(alpha = 0.95f), fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Planche sous une rangée de livres. */
@Composable
fun WoodPlank() {
    Column {
        Box(Modifier.fillMaxWidth().height(9.dp).background(Brush.verticalGradient(listOf(Color(0xFF9A6B45), Color(0xFF7A5134)))))
        Box(Modifier.fillMaxWidth().height(7.dp).background(Brush.verticalGradient(listOf(Color(0xFF5A3B26), Color(0xFF3A2618)))))
        Box(Modifier.fillMaxWidth().height(14.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
    }
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
/** Date de mise à jour comparable (aaaammjj) à partir des formats des sites ; 0 si inconnue. */
fun dateKey(w: Work): Int {
    val u = w.updated.lowercase()
    Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(u)?.let { val (y, m, d) = it.destructured; return y.toInt() * 10000 + m.toInt() * 100 + d.toInt() }
    Regex("(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(u)?.let { val (d, m, y) = it.destructured; return y.toInt() * 10000 + m.toInt() * 100 + d.toInt() }
    Regex("([a-z]{3})[a-z]* (\\d{1,2})(?:, (\\d{4}))?").find(u)?.let { val (mo, d, y) = it.destructured
        val m = MONTHS.indexOf(mo) + 1; if (m > 0) return (y.toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)) * 10000 + m * 100 + d.toInt() }
    return 0
}
/** Fandoms reconnus par leurs noms courants (titre, tags, résumé, nom de liste). */
private val FANDOMS = mapOf(
    "onepiece" to listOf("one piece", "onepiece", "luffy", "zoro", "mugiwara", "strawhat"),
    "mha" to listOf("my hero academia", "myheroacademia", "boku no hero", "bnha", "mha", "deku", "izuku", "bakugo"),
    "naruto" to listOf("naruto", "boruto", "sasuke", "konoha"), "dxd" to listOf("dxd", "highschooldxd", "high school dxd", "rias"),
    "bleach" to listOf("bleach", "ichigo"), "fairytail" to listOf("fairy tail", "fairytail", "natsu"),
    "jjk" to listOf("jujutsu", "jjk", "gojo", "sukuna"), "kny" to listOf("demon slayer", "kimetsu", "tanjiro"),
    "dbz" to listOf("dragon ball", "dragonball", "goku"), "hxh" to listOf("hunter x hunter", "hunterxhunter", "hxh", "gon"),
    "blackclover" to listOf("black clover", "blackclover", "asta"), "pokemon" to listOf("pokemon", "pokémon"),
    "marvel" to listOf("marvel", "avengers", "spider-man", "spiderman"), "dc" to listOf(" dc ", "justice league", "batman", "superman"),
    "hp" to listOf("harry potter", "hogwarts"), "genshin" to listOf("genshin"), "csm" to listOf("chainsaw man", "chainsawman"),
    "aot" to listOf("attack on titan", "shingeki", "aot"), "rwby" to listOf("rwby"), "opm" to listOf("one punch", "saitama"),
    "akame" to listOf("akame ga kill"), "kh" to listOf("kingdom hearts"), "ben10" to listOf("ben 10", "ben10"),
)
private fun fandomsIn(t: String): Set<String> { val s = " " + t.lowercase() + " "; return FANDOMS.filter { (_, a) -> a.any { s.contains(it) } }.keys }
/** Nom de liste ou titre qui annonce un crossover (« A x B », « crossover », 2 fandoms…), hors « x reader ». */
fun crossName(t: String) = Regex("(?i)crossover|×").containsMatchIn(t) ||
    (Regex("(?i)\\s[x×]\\s").containsMatchIn(t) && !Regex("(?i)\\sx\\s*(reader|you|oc|harem)").containsMatchIn(t)) || fandomsIn(t).size >= 2
/** Crossover : plusieurs fandoms (site, tags, titre, résumé) ou titre annonçant un crossover. */
fun isCrossover(w: Work) = fandomKey(w).startsWith("Crossover") || crossName(w.title) ||
    fandomsIn(w.title + " " + w.tags.joinToString(" ") + " " + w.fandoms.joinToString(" ") + " " + w.summary.take(300)).size >= 2
// Caches : chaque fiche n'est analysée qu'une fois (tri instantané même avec 1 700 fiches)
private val crossCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
private val dateCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
fun crossOf(w: Work) = crossCache.getOrPut(w.id + "|" + w.title) { isCrossover(w) }
fun dateOf(w: Work) = dateCache.getOrPut(w.updated) { dateKey(w) }
fun sortWorks(l: List<Work>, mode: Int): List<Work> = when (mode) {
    0 -> l.sortedBy { it.title.lowercase() }
    2 -> l.map { Triple(it, crossOf(it), it.title.lowercase()) }.sortedWith(compareByDescending<Triple<Work, Boolean, String>> { it.second }.thenBy { it.third }).map { it.first }
    else -> l.map { it to dateOf(it) }.sortedWith(compareByDescending<Pair<Work, Int>> { it.second }.thenByDescending { it.first.checked }).map { it.first }
}

/** Coche de sélection posée sur un livre ou une fiche. */
@Composable
fun SelMark(modifier: Modifier = Modifier) {
    Box(modifier.size(34.dp).clip(CircleShape).background(Color(0xFFE5484D)), contentAlignment = Alignment.Center) {
        Text("✓", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

/** Rangées de 4 livres en 3D posés sur des planches, à la largeur de l'écran. */
@Composable
fun BookRows(works: List<Work>, isSel: (Work) -> Boolean = { false }, onBook: (Work) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        val bw = ((maxWidth.value - 3 * 10 - 4 * 9 - 8) / 4).toInt().coerceIn(56, 120)
        Column {
            works.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    row.forEach { w -> Box { Book3D(w, bw, Modifier.clickable { onBook(w) }); if (isSel(w)) SelMark(Modifier.align(Alignment.Center)) } }
                }
                WoodPlank()
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** Une étagère = une collection (liste de lecture, fandom…) : une rangée de 4, le reste via « Tout voir ». */
@Composable
fun BookShelf(title: String, works: List<Work>, onAll: () -> Unit, isSel: (Work) -> Boolean = { false }, onBook: (Work) -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Row(Modifier.padding(start = 22.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${works.size} histoire" + if (works.size > 1) "s" else "", color = Mut, fontSize = 12.sp)
            }
            if (works.size > 4) Text("Tout voir ›", color = A1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAll).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        BookRows(works.take(4), isSel, onBook)
    }
}

/** Étagère AO3 : un fandom, ou un crossover (fandoms triés). */
fun fandomKey(w: Work): String {
    val f = w.fandoms.flatMap { it.replace(Regex("\\s*\\([^)]*\\)"), "").split(" & ", " + ") }.map { it.split(" | ").last().removeSuffix(" Xover").trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    return when { f.isEmpty() -> "Sans fandom"; f.size == 1 -> f[0]; else -> "Crossover : " + f.joinToString(" × ") }
}

fun collectionName(src: String) = when (src) {
    "Favori" -> "Favoris"; "À lire" -> "À lire plus tard"; "Abonnement" -> "Abonnements"; "Ajouté" -> "Ajoutées"
    else -> src.removePrefix("Liste : ")
}

/** Livre en 3D debout : couverture devant, tranche des pages dessus et sur le côté. */
@Composable
fun Book3D(w: Work, width: Int, modifier: Modifier = Modifier) {
    val d = 9
    val h = (width * 1.5f).toInt()
    Box(modifier.size((width + d).dp, (h + d).dp)) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val D = d.dp.toPx(); val W = width.dp.toPx(); val H = size.height
            val top = androidx.compose.ui.graphics.Path().apply { moveTo(0f, D); lineTo(D, 0f); lineTo(W + D, 0f); lineTo(W, D); close() }
            drawPath(top, Color(0xFFF3EBDD))
            val side = androidx.compose.ui.graphics.Path().apply { moveTo(W, D); lineTo(W + D, 0f); lineTo(W + D, H - D); lineTo(W, H); close() }
            drawPath(side, Brush.horizontalGradient(listOf(Color(0xFFE6DBC8), Color(0xFFB9AC96)), startX = W, endX = W + D))
            for (i in 1..5) {
                val t = i / 6f
                drawLine(Color(0x22000000), androidx.compose.ui.geometry.Offset(W + D * t, D - D * t), androidx.compose.ui.geometry.Offset(W + D * t, H - D * t), 1f)
                drawLine(Color(0x22000000), androidx.compose.ui.geometry.Offset(W * t, D), androidx.compose.ui.geometry.Offset(W * t + D, 0f), 1f)
            }
        }
        BookCover(w, width, Modifier.offset(y = d.dp))
    }
}
