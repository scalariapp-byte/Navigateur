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

    /** Œuvre : affiche (nom vide) + 25 personnages les plus populaires (nom, image). Une requête, mise en cache. */
    private val mem = HashMap<String, List<Pair<String, String>>>()
    private suspend fun media(name: String): List<Pair<String, String>> {
        val key = "M2:" + name.lowercase()
        mem[key]?.let { return it }
        fun parse(s: String) = s.split('\n').filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        prefs.getString(key, null)?.let { s -> return parse(s).also { mem[key] = it } }
        val m = gql("query(\$s:String){Media(search:\$s,type:ANIME){coverImage{extraLarge} characters(sort:FAVOURITES_DESC,perPage:25){nodes{name{full} image{large}}}}}", name)?.optJSONObject("Media")
        val out = mutableListOf<Pair<String, String>>()
        m?.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.isNotBlank() }?.let { out += "" to it }
        m?.optJSONObject("characters")?.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) { val n = a.getJSONObject(i)
            val img = n.optJSONObject("image")?.optString("large").orEmpty()
            if (img.isNotBlank() && "default" !in img) out += n.optJSONObject("name")?.optString("full").orEmpty() to img } }
        prefs.edit().putString(key, out.joinToString("\n") { it.first.replace('\t', ' ') + "\t" + it.second }).apply(); mem[key] = out; return out
    }

    private val STOP = setOf("the", "and", "von", "van", "der", "del", "les", "des")
    private fun tokens(n: String) = n.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 && it !in STOP }
    private val ALIAS = mapOf("asl" to listOf("ace", "sabo", "luffy"))

    /**
     * Personnages principaux de l'histoire, du plus important au moins important : on note chaque personnage connu de
     * l'œuvre selon sa présence dans le titre (fort), le résumé, les tags de personnages (ordre AO3 = importance) et
     * les tags libres (« Protective Luffy »…).
     */
    private fun rank(w: Work, cands: List<Triple<String, String, Int>>): List<Triple<String, String, Int>> {
        val title = w.title.lowercase(); val sum = w.summary.lowercase()
        val aliasHits = ALIAS.filterKeys { Regex("\\b$it\\b").containsMatchIn(title) }.values.flatten().toSet()
        fun count(text: String, t: String) = Regex("\\b${Regex.escape(t)}\\b").findAll(text).count()
        return cands.map { c ->
            val tk = tokens(c.first); var sc = 0.0
            if (tk.isEmpty()) return@map c to 0.0
            if (tk.any { count(title, it) > 0 }) sc += 8
            if (tk.any { it in aliasHits }) sc += 6
            sc += minOf(5, tk.maxOf { count(sum, it) }) * 2.0
            w.tags.forEachIndexed { i, t -> val tl = t.lowercase()
                if (tk.any { count(tl, it) > 0 }) sc += if (i < 6 && !Regex("[/&]").containsMatchIn(t) && tokens(t).all { x -> tk.any { it == x } || x.length < 4 }) 4.0 - i * 0.5 else 1.5 }
            c to sc
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Contrôle qualité d'une image : rejette les images trop petites, presque blanches, presque noires ou sans contraste
     * (fonds vides, images par défaut). Résultat mis en cache.
     */
    suspend fun good(url: String): Boolean {
        val key = "q2:$url"
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        val ok = runCatching {
            val b = Img.load(url)?.asAndroidBitmap() ?: return@runCatching false
            if (b.width < 100 || b.height < 120) return@runCatching false
            // Portrait obligatoire : les images couchées ou en bandeau coupent les têtes dans une demi-couverture
            if (b.height < b.width * 1.05f) return@runCatching false
            var sum = 0.0; var sq = 0.0; var n = 0; var skin = 0
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = b.getPixel(x * (b.width - 1) / 15, (y * (b.height - 1) / 15))
                val l = 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
                sum += l; sq += l * l; n++
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
                if (r > 95 && g > 40 && bl > 20 && r > g && r > bl && r - minOf(g, bl) > 15 && r - g > 15) skin++
            }
            val mean = sum / n; val sd = kotlin.math.sqrt((sq / n - mean * mean).coerceAtLeast(0.0))
            // Gros plan (bouche, main, visage zoomé) = trop de peau sur l'image → rejeté
            mean in 25.0..225.0 && sd > 30 && skin < n * 0.33
        }.getOrDefault(false)
        prefs.edit().putBoolean(key, ok).apply(); return ok
    }

    /** Couverture montée : les 2 personnages principaux (un par fandom en crossover), sinon des personnages de l'œuvre. */
    suspend fun montage(w: Work): String? {
        val h = w.id.hashCode() and 0x7fffffff
        val pics = mutableListOf<String>()
        val fs = fandomKey(w).removePrefix("Crossover : ").split(" × ").map { it.trim() }.filter { it.isNotBlank() && it != "Sans fandom" }
        val cands = fs.flatMapIndexed { k, f -> media(f).drop(1).map { Triple(it.first, it.second, k) } }.toMutableList()
        // Tags de personnages absents des 25 plus populaires : recherche directe
        for (t in w.tags.take(4)) {
            if (t.length > 40 || Regex("[/&:]").containsMatchIn(t)) continue
            val tk = tokens(t); if (tk.isEmpty() || cands.any { c -> tokens(c.first).containsAll(tk) }) continue
            character(t.substringBefore(" (").trim())?.let { cands += Triple(t.substringBefore(" (").trim(), it, -1) }
        }
        val ranked = rank(w, cands)
        suspend fun add(c: Triple<String, String, Int>) = if (c.second !in pics && good(c.second)) { pics += c.second; true } else false
        if (fs.size > 1) for (k in fs.indices) { if (pics.size >= 2) break; for (c in ranked.filter { it.third == k || it.third == -1 }) if (add(c)) break }
        for (c in ranked) { if (pics.size >= 2) break; add(c) }
        // Repli : personnages populaires de l'œuvre
        suspend fun pickFrom(pool: List<String>, seed: Int): String? {
            if (pool.isEmpty()) return null
            for (k in 0 until minOf(6, pool.size)) { val u = pool[(seed + k * 7) % pool.size]; if (u !in pics && good(u)) return u }
            return null
        }
        fs.forEachIndexed { k, f -> if (pics.size < 2) pickFrom(media(f).drop(1).map { it.second }, h / (k + 1))?.let { pics += it } }
        if (pics.size < 2) fs.firstOrNull()?.let { pickFrom(media(it).drop(1).map { p -> p.second }, h / 7 + 3) }?.let { pics += it }
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
        val pool = fs.flatMap { media(it).map { p -> p.second } }
        return if (pool.isEmpty()) null else pool[(w.id.hashCode() and 0x7fffffff) % pool.size]
    }
}
