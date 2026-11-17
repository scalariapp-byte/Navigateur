package app.fanfichub

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import java.io.File
import java.net.URL

/** Cache d'images : mémoire + disque (les couvertures ne sont téléchargées qu'une fois). */
object Img {
    lateinit var dir: File
    private val mem = LruCache<String, ImageBitmap>(120)
    fun cached(url: String): ImageBitmap? = mem.get(url)
    suspend fun load(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        mem.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = File(dir, Integer.toHexString(url.hashCode()))
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
fun NetFill(url: String, modifier: Modifier = Modifier) {
    val bmp by produceState(Img.cached(url), url) { if (value == null) value = Img.load(url) }
    bmp?.let { Image(it, null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/** Couverture : vraie image si le site en fournit une, sinon couverture générée aux couleurs du fandom. */
@Composable
fun BookCover(w: Work, width: Int, modifier: Modifier = Modifier) {
    val c = fandomColor(w.fandom)
    val h = (width * 1.5f).toInt()
    Box(modifier.width(width.dp).height(h.dp).shadow(8.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
        .background(Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.45f), Color(0xFF15151E))))) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Box(Modifier.size(width = 3.dp, height = 22.dp).background(Color.White.copy(alpha = 0.7f)))
            Text(w.title, color = Color.White, fontSize = (width / 8f).coerceIn(10f, 14f).sp, fontWeight = FontWeight.Bold, lineHeight = (width / 6.5f).coerceIn(12f, 17f).sp,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(w.author, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (w.cover.isNotBlank()) NetFill(w.cover)
        // Reliure + reflet
        Box(Modifier.fillMaxHeight().width(5.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
        if (w.newChapters > 0) Text("+${w.newChapters}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(CircleShape).background(Grad).padding(horizontal = 7.dp, vertical = 2.dp))
        if (w.progress > 0.01f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(w.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Grad))
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

/** Une étagère = une collection (liste de lecture, favoris…). */
@Composable
fun BookShelf(title: String, works: List<Work>, onAll: () -> Unit, onBook: (Work) -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Row(Modifier.padding(start = 22.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Txt, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${works.size} histoire" + if (works.size > 1) "s" else "", color = Mut, fontSize = 12.sp)
            }
            Text("Tout voir ›", color = A1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAll).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            items(works.take(30), key = { it.id }) { w -> Book3D(w, 92, Modifier.clickable { onBook(w) }) }
        }
        Box(Modifier.padding(horizontal = 12.dp)) { WoodPlank() }
    }
}

fun collectionName(src: String) = when (src) {
    "Favori" -> "Favoris"; "À lire" -> "À lire plus tard"; "Abonnement" -> "Abonnements"; "Ajouté" -> "Ajoutées par lien"
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
