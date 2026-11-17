package app.fanfichub

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Petit moteur 3D : vecteurs, rotations, projection en perspective. */
private data class V(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V(x * k, y * k, z * k)
    fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: V) = x * o.x + y * o.y + z * o.z
    fun norm(): V { val l = sqrt(dot(this)).coerceAtLeast(1e-4f); return V(x / l, y / l, z / l) }
}
private fun rotY(p: V, a: Float) = V(p.x * cos(a) + p.z * sin(a), p.y, -p.x * sin(a) + p.z * cos(a))
private fun rotX(p: V, a: Float) = V(p.x, p.y * cos(a) - p.z * sin(a), p.y * sin(a) + p.z * cos(a))
private fun rotZ(p: V, a: Float) = V(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a), p.z)

/** Position et orientation du livre (angles en degrés, open de 0 à 1). */
class BookPose(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rz: Float, val scale: Float, val open: Float)

private class Face(val pts: List<V>, val color: Color, val lines: List<Pair<V, V>> = emptyList(), val back: Color? = null, val cover: Boolean = false)

private val PaperC = Color(0xFFF3EBDD)
private val PaperBack = Color(0xFFE6DCC8)

/**
 * Dessine un volume en 3D : 4e de couverture, dos, bloc de pages (tranches lignées),
 * pages qui se tournent et couverture articulée sur la reliure. Faces triées par profondeur et éclairées.
 */
fun DrawScope.drawBook3D(color: Color, logo: String, title: String, pose: BookPose, W: Float, H: Float, T: Float) {
    val deg = (PI / 180).toFloat()
    val cam = 1600f
    val hw = W / 2; val hh = H / 2; val ht = T / 2
    fun world(p: V): V { var q = p * pose.scale; q = rotY(q, pose.ry * deg); q = rotX(q, pose.rx * deg); q = rotZ(q, pose.rz * deg); return q }
    fun proj(q: V): Offset { val s = cam / (cam + q.z); return Offset(pose.cx + q.x * s, pose.cy - q.y * s) }
    fun hinge(p: V, hx: Float, hz: Float, a: Float): V { val r = rotY(V(p.x - hx, p.y, p.z - hz), a); return V(r.x + hx, r.y, r.z + hz) }

    val faces = mutableListOf<Face>()
    fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, base: Color, pages: Boolean = false) {
        val c = listOf(V(x0, y0, z0), V(x1, y0, z0), V(x1, y1, z0), V(x0, y1, z0), V(x0, y0, z1), V(x1, y0, z1), V(x1, y1, z1), V(x0, y1, z1))
        listOf(listOf(0, 1, 2, 3), listOf(5, 4, 7, 6), listOf(4, 5, 1, 0), listOf(3, 2, 6, 7), listOf(4, 0, 3, 7), listOf(1, 5, 6, 2)).forEachIndexed { i, q ->
            val pts = q.map { c[it] }
            val lines = if (pages && i >= 2) (1..7).map { k ->
                val z = z0 + (z1 - z0) * k / 8f
                if (i <= 3) V(x0 + 2, pts[0].y, z) to V(x1 - 2, pts[0].y, z) else V(pts[0].x, y0 + 2, z) to V(pts[0].x, y1 - 2, z)
            } else emptyList()
            faces += Face(pts, base, lines)
        }
    }
    // Repère : couverture côté caméra (z = -ht), dos à gauche (x = -hw).
    box(-hw, hw, -hh, hh, ht - 4, ht, lerp(color, Color.Black, 0.45f))
    box(-hw, -hw + 4, -hh, hh, -ht, ht, lerp(color, Color.Black, 0.3f))
    box(-hw + 3, hw - 4, -hh + 4, hh - 4, -ht + 3, ht - 4, PaperC, pages = true)
    val zTop = -ht + 2.9f
    val text = (0 until 13).map { i ->
        val y = hh - 22 - i * (H - 44) / 13f
        V(-hw + 16, y, zTop) to V(hw - 18 - if (i % 4 == 3) W * 0.3f else 0f, y, zTop)
    }
    faces += Face(listOf(V(-hw + 3, -hh + 4, zTop), V(hw - 4, -hh + 4, zTop), V(hw - 4, hh - 4, zTop), V(-hw + 3, hh - 4, zTop)), PaperC, text)
    // Pages qui se tournent (décalées dans le temps)
    for (k in 3 downTo 0) {
        val t = ((pose.open - 0.22f - k * 0.13f) / 0.45f).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val a = 176f * deg * t
        val z = zTop - 0.3f * (k + 1)
        val hx = -hw + 3
        val pts = listOf(V(hx, -hh + 5, z), V(hw - 5, -hh + 5, z), V(hw - 5, hh - 5, z), V(hx, hh - 5, z)).map { hinge(it, hx, z, a) }
        faces += Face(pts, PaperC, text.map { (p, q) -> hinge(V(p.x, p.y, z), hx, z, a) to hinge(V(q.x, q.y, z), hx, z, a) }, back = PaperBack)
    }
    // Couverture articulée
    val ca = 178f * deg * (pose.open / 0.42f).coerceIn(0f, 1f)
    val cz = -ht - 0.5f
    faces += Face(listOf(V(-hw, -hh, cz), V(hw, -hh, cz), V(hw, hh, cz), V(-hw, hh, cz)).map { hinge(it, -hw, cz, ca) }, color, back = Color(0xFFEDE3D1), cover = true)

    val light = V(-0.45f, 0.65f, -1f).norm()
    val drawn = faces.map { f -> val w = f.pts.map { world(it) }; Triple(f, w, w.map { it.z }.average()) }.sortedByDescending { it.third }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    for ((f, w, _) in drawn) {
        val n = (w[1] - w[0]).cross(w[2] - w[0]).norm()
        val front = n.z > 0 // ordre des sommets : le recto fait face à la caméra
        val base = if (f.back != null && !front) f.back else f.color
        val k = 0.58f + 0.42f * abs(n.dot(light))
        val shaded = Color(base.red * k, base.green * k, base.blue * k, 1f)
        val path = Path().apply { w.forEachIndexed { i, p -> val o = proj(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
        drawPath(path, shaded)
        drawPath(path, Color.Black.copy(alpha = 0.18f), style = Stroke(1.2f))
        if (f.lines.isNotEmpty() && (f.back == null || true)) f.lines.forEach { (a, b) ->
            drawLine(Color(0x33000000), proj(world(a)), proj(world(b)), 1.6f)
        }
        if (f.cover && front) {
            // Décor de couverture : bandeau, carré du logo, titre
            fun q(x0: Float, x1: Float, y0: Float, y1: Float) = listOf(V(x0, y0, cz - 0.1f), V(x1, y0, cz - 0.1f), V(x1, y1, cz - 0.1f), V(x0, y1, cz - 0.1f))
                .map { proj(world(hinge(it, -hw, cz, ca))) }
            fun fill(o: List<Offset>, c: Color) = drawPath(Path().apply { o.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }, c)
            fill(q(-hw, hw, hh - H * 0.16f, hh), Color(0xFFF7F3EA))
            fill(q(-W * 0.22f, W * 0.22f, -H * 0.12f, H * 0.18f), Color.White.copy(alpha = 0.92f))
            val corners = q(-hw, hw, -hh, hh)
            val center = Offset(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())
            val pw = (corners[1] - corners[0]).getDistance()
            if (pw > 20f) {
                paint.color = lerp(color, Color.Black, 0.25f).toArgb(); paint.textSize = pw * 0.2f
                drawContext.canvas.nativeCanvas.drawText(logo, center.x, center.y + pw * 0.05f, paint)
                paint.color = Color(0xFF141414).toArgb(); paint.textSize = pw * 0.09f
                val band = q(-hw, hw, hh - H * 0.16f, hh)
                drawContext.canvas.nativeCanvas.drawText("FANFIC HUB", band.map { it.x }.average().toFloat(), band.map { it.y }.average().toFloat() + pw * 0.03f, paint)
                paint.color = Color.White.toArgb(); paint.textSize = pw * 0.1f
                drawContext.canvas.nativeCanvas.drawText(title, center.x, center.y + pw * 0.48f, paint)
            }
        }
    }
}
