package app.fanfichub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Couvertures illustrées pour les fiches sans image (AO3) : image officielle du personnage principal,
 * sinon de l'œuvre, via l'API publique d'AniList. Résultats mis en cache (par nom).
 */
object Art {
    private lateinit var prefs: android.content.SharedPreferences
    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("art", Context.MODE_PRIVATE) }

    private suspend fun gql(q: String, s: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("query", q).put("variables", JSONObject().put("s", s)).toString()
            JSONObject(Jsoup.connect("https://graphql.anilist.co").ignoreContentType(true).requestBody(body)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(org.jsoup.Connection.Method.POST).timeout(15000).execute().body()).optJSONObject("data")
        }.getOrNull()
    }

    private suspend fun character(name: String): String? {
        val key = "c:" + name.lowercase()
        prefs.getString(key, null)?.let { return it.ifBlank { null } }
        val o = gql("query(\$s:String){Character(search:\$s){name{full} image{large}}}", name)?.optJSONObject("Character")
        val full = o?.optJSONObject("name")?.optString("full").orEmpty().lowercase()
        // On n'accepte que si le nom trouvé partage un mot du tag (évite les faux positifs)
        val ok = full.isNotBlank() && name.lowercase().split(Regex("[^\\p{L}]+")).any { it.length > 2 && full.contains(it) }
        val url = if (ok) o?.optJSONObject("image")?.optString("large").orEmpty() else ""
        prefs.edit().putString(key, url).apply(); return url.ifBlank { null }
    }

    /** Images d'une œuvre : affiche + 25 personnages les plus populaires (une seule requête, mise en cache). */
    private val mem = HashMap<String, List<String>>()
    private suspend fun media(name: String): List<String> {
        val key = "M:" + name.lowercase()
        mem[key]?.let { return it }
        prefs.getString(key, null)?.let { s -> return s.split('\n').filter { it.isNotBlank() }.also { mem[key] = it } }
        val m = gql("query(\$s:String){Media(search:\$s,type:ANIME){coverImage{extraLarge} characters(sort:FAVOURITES_DESC,perPage:25){nodes{image{large}}}}}", name)?.optJSONObject("Media")
        val out = mutableListOf<String>()
        m?.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.isNotBlank() }?.let { out += it }
        m?.optJSONObject("characters")?.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).optJSONObject("image")?.optString("large")?.takeIf { it.isNotBlank() && "default" !in it }?.let { out += it } }
        prefs.edit().putString(key, out.joinToString("\n")).apply(); mem[key] = out; return out
    }

    /**
     * Contrôle qualité d'une image : rejette les images trop petites, presque blanches, presque noires ou sans contraste
     * (fonds vides, images par défaut). Résultat mis en cache.
     */
    suspend fun good(url: String): Boolean {
        val key = "q:$url"
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        val ok = runCatching {
            val b = Img.load(url)?.asAndroidBitmap() ?: return@runCatching false
            if (b.width < 100 || b.height < 120) return@runCatching false
            // Portrait obligatoire : les images couchées ou en bandeau coupent les têtes dans une demi-couverture
            if (b.height < b.width * 1.05f) return@runCatching false
            var sum = 0.0; var sq = 0.0; var n = 0
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = b.getPixel(x * (b.width - 1) / 15, (y * (b.height - 1) / 15))
                val l = 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
                sum += l; sq += l * l; n++
            }
            val mean = sum / n; val sd = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
            mean in 25.0..225.0 && sd > 30
        }.getOrDefault(false)
        prefs.edit().putBoolean(key, ok).apply(); return ok
    }

    /** Couverture montée : jusqu'à 3 images (personnages des tags, puis un par fandom), propres à l'histoire. */
    suspend fun montage(w: Work): String? {
        val h = w.id.hashCode() and 0x7fffffff
        val pics = mutableListOf<String>()
        for (t in w.tags.take(4)) {
            if (pics.size >= 2) break
            if (t.length > 40 || Regex("[/&:]").containsMatchIn(t)) continue
            character(t.substringBefore(" (").trim())?.let { if (it !in pics && good(it)) pics += it }
        }
        val fs = fandomKey(w).removePrefix("Crossover : ").split(" × ").map { it.trim() }.filter { it.isNotBlank() && it != "Sans fandom" }
        // Une bonne image par fandom (on essaie plusieurs candidats si la première est mauvaise)
        suspend fun pickFrom(pool: List<String>, seed: Int): String? {
            if (pool.isEmpty()) return null
            for (k in 0 until minOf(6, pool.size)) { val u = pool[(seed + k * 7) % pool.size]; if (u !in pics && good(u)) return u }
            return null
        }
        fs.forEachIndexed { k, f -> if (pics.size < 2) pickFrom(media(f).drop(1), h / (k + 1))?.let { pics += it } }
        if (pics.size < 2) fs.firstOrNull()?.let { pickFrom(media(it).drop(1), h / 7 + 3) }?.let { pics += it }
        return when { pics.size >= 2 -> "montage:" + pics.take(2).joinToString("|"); pics.size == 1 -> pics[0]; else -> null }
    }

    /** Image pour une fiche : personnage (tags en tête = personnages sur AO3), sinon l'œuvre du fandom. */
    suspend fun forWork(w: Work): String? {
        for (t in w.tags.take(3)) {
            if (t.length > 40 || Regex("[/&:]").containsMatchIn(t)) continue
            character(t.substringBefore(" (").trim())?.let { return it }
        }
        // Sinon une image différente pour chaque histoire parmi l'affiche et les personnages de l'œuvre
        val fs = fandomKey(w).removePrefix("Crossover : ").split(" × ").map { it.trim() }.filter { it.isNotBlank() && it != "Sans fandom" }
        val pool = fs.flatMap { media(it) }
        return if (pool.isEmpty()) null else pool[(w.id.hashCode() and 0x7fffffff) % pool.size]
    }
}
