package app.fanfichub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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

    // AniList limite le débit (≈30-90 requêtes/min) : requêtes espacées, nouvel essai en cas d'échec, échecs jamais mis en cache
    private val gqlGate = kotlinx.coroutines.sync.Mutex()
    private suspend fun gql(q: String, s: String): JSONObject? {
        for (attempt in 0 until 3) {
            gqlRaw(q, s)?.let { return it }
            kotlinx.coroutines.delay(if (attempt == 0) 3000L else 15000L)
        }
        return null
    }
    private suspend fun gqlRaw(q: String, s: String): JSONObject? = gqlGate.withLock { kotlinx.coroutines.delay(700); withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("query", q).put("variables", JSONObject().put("s", s)).toString()
            val r = Jsoup.connect("https://graphql.anilist.co").ignoreContentType(true).ignoreHttpErrors(true).requestBody(body)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(org.jsoup.Connection.Method.POST).timeout(15000).execute()
            // 429 (trop de requêtes) ou panne = échec à réessayer ; 404 = « introuvable », réponse valable
            lastAni = "HTTP ${r.statusCode()}"
            if (r.statusCode() == 429 || r.statusCode() >= 500) null else JSONObject(r.body()).optJSONObject("data") ?: JSONObject()
        }.onFailure { lastAni = "ERREUR ${it.javaClass.simpleName} ${it.message?.take(80)}" }.getOrNull()
    } }
    @Volatile var lastAni = "aucune requête"; @Volatile var lastJikan = "aucune requête"

    private suspend fun character(name: String): String? {
        val key = "c2:" + name.lowercase()
        prefs.getString(key, null)?.let { return it.ifBlank { null } }
        val o = (gql("query(\$s:String){Character(search:\$s){name{full} image{large}}}", name) ?: return null).optJSONObject("Character")
        val full = o?.optJSONObject("name")?.optString("full").orEmpty().lowercase()
        // On n'accepte que si le nom trouvé partage un mot du tag (évite les faux positifs)
        val ok = full.isNotBlank() && name.lowercase().split(Regex("[^\\p{L}]+")).any { it.length > 2 && full.contains(it) }
        val url = if (ok) o?.optJSONObject("image")?.optString("large").orEmpty() else ""
        prefs.edit().putString(key, url).apply(); return url.ifBlank { null }
    }

    /** Œuvre : affiche (nom vide) + 25 personnages les plus populaires (nom, image). Une requête, mise en cache. */
    private val mem = HashMap<String, List<Pair<String, String>>>()
    private suspend fun media(name: String): List<Pair<String, String>> {
        val key = "M3:" + name.lowercase()
        mem[key]?.let { return it }
        fun parse(s: String) = s.split('\n').filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        prefs.getString(key, null)?.let { s -> return parse(s).also { mem[key] = it } }
        val m = gql("query(\$s:String){Media(search:\$s,type:ANIME){coverImage{extraLarge} characters(sort:FAVOURITES_DESC,perPage:25){nodes{name{full} image{large}}}}}", name)?.optJSONObject("Media") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        m?.optJSONObject("coverImage")?.optString("extraLarge")?.takeIf { it.isNotBlank() }?.let { out += "" to it }
        m?.optJSONObject("characters")?.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) { val n = a.getJSONObject(i)
            val img = n.optJSONObject("image")?.optString("large").orEmpty()
            if (img.isNotBlank() && "default" !in img) out += n.optJSONObject("name")?.optString("full").orEmpty() to img } }
        prefs.edit().putString(key, out.joinToString("\n") { it.first.replace('\t', ' ') + "\t" + it.second }).apply(); mem[key] = out; return out
    }

    /** Images déjà posées sur une autre couverture (pour varier d'une histoire à l'autre). */
    val used: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Autres images d'un personnage (galerie MyAnimeList via Jikan) : utile quand l'image AniList est mauvaise ou déjà prise. */
    private val jikanGate = kotlinx.coroutines.sync.Mutex()
    @Volatile private var jikanDownUntil = 0L
    private suspend fun pictures(name: String): List<String> {
        val key = "J:" + name.lowercase()
        prefs.getString(key, null)?.let { s -> return s.split('\n').filter { it.isNotBlank() } }
        if (System.currentTimeMillis() < jikanDownUntil) return emptyList() // Jikan injoignable : on n'attend pas
        val out = jikanGate.withLock { if (System.currentTimeMillis() < jikanDownUntil) null else withContext(Dispatchers.IO) { runCatching {
            fun get(u: String) = JSONObject(Jsoup.connect(u).ignoreContentType(true).timeout(6000).execute().also { lastJikan = "HTTP ${it.statusCode()}" }.body())
            kotlinx.coroutines.delay(400) // limite de Jikan : 3 requêtes/s
            val c = get("https://api.jikan.moe/v4/characters?limit=1&order_by=favorites&sort=desc&q=" + java.net.URLEncoder.encode(name, "UTF-8"))
                .optJSONArray("data")?.optJSONObject(0) ?: return@runCatching emptyList<String>()
            val found = c.optString("name").lowercase()
            if (tokens(name).none { found.contains(it) }) return@runCatching emptyList<String>()
            kotlinx.coroutines.delay(400)
            val a = get("https://api.jikan.moe/v4/characters/${c.optInt("mal_id")}/pictures").optJSONArray("data") ?: return@runCatching emptyList<String>()
            (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("jpg")?.optString("image_url")?.takeIf { u -> u.isNotBlank() } }.take(10)
        }.onFailure { jikanDownUntil = System.currentTimeMillis() + 30 * 60_000; lastJikan = "ERREUR ${it.javaClass.simpleName} ${it.message?.take(80)}" }.getOrNull() } }
        if (out != null) prefs.edit().putString(key, out.joinToString("\n")).apply()
        return out.orEmpty()
    }

    private val STOP = setOf("the", "and", "von", "van", "der", "del", "les", "des")
    private fun tokens(n: String) = n.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length > 2 && it !in STOP }
    private val ALIAS = mapOf("asl" to listOf("ace", "sabo", "luffy"))

    /**
     * Personnages principaux de l'histoire, du plus important au moins important : on note chaque personnage connu de
     * l'œuvre selon sa présence dans le titre (fort), le résumé, les tags de personnages (ordre AO3 = importance) et
     * les tags libres (« Protective Luffy »…).
     */
    private fun rank(w: Work, cands: List<Triple<String, String, Int>>) = scored(w, cands).map { it.first }
    private fun scored(w: Work, cands: List<Triple<String, String, Int>>): List<Pair<Triple<String, String, Int>, Double>> {
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
        }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /**
     * Contrôle qualité d'une image : rejette les images trop petites, presque blanches, presque noires ou sans contraste
     * (fonds vides, images par défaut). Résultat mis en cache.
     */
    /** strict = aussi rejeter les gros plans (beaucoup de peau). Les personnages principaux passent en mode souple. */
    suspend fun good(url: String, strict: Boolean = true): Boolean { val v = quality(url); return v >= 0 && (!strict || v < 33) }
    /** -1 = image inutilisable (absente, petite, couchée, vide), sinon % de peau sur l'image. Mis en cache. */
    fun cachedQuality(url: String): Int? = if (prefs.contains("q3:$url")) prefs.getInt("q3:$url", -1) else null
    private suspend fun quality(url: String): Int {
        val key = "q3:$url"
        if (prefs.contains(key)) return prefs.getInt(key, -1)
        val ok = runCatching {
            val b = Img.load(url)?.asAndroidBitmap() ?: return@runCatching -1
            if (b.width < 100 || b.height < 120) return@runCatching -1
            // Portrait obligatoire : les images couchées ou en bandeau coupent les têtes dans une demi-couverture
            if (b.height < b.width * 1.05f) return@runCatching -1
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
            if (mean in 25.0..225.0 && sd > 30) skin * 100 / n else -1
        }.getOrDefault(-2)
        if (ok != -2) prefs.edit().putInt(key, ok).apply() // échec de chargement : pas mis en cache
        return ok.coerceAtLeast(-1)
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
        val sc = scored(w, cands).take(5); val ranked = sc.map { it.first }; val scoreOf = sc.toMap()
        // Personnage principal : son image AniList, sinon (mauvaise ou déjà utilisée ailleurs) une autre de sa galerie
        suspend fun add(c: Triple<String, String, Int>): Boolean {
            val strict = (scoreOf[c] ?: 0.0) < 6 // personnage vraiment principal : on accepte un gros plan plutôt que de l'omettre
            if (c.second !in pics && c.second !in used && good(c.second, strict)) { pics += c.second; return true }
            val alt = pictures(c.first).let { l -> if (l.isEmpty()) l else l.drop(h % l.size) + l.take(h % l.size) }
            for (u in alt) if (u !in pics && u !in used && good(u)) { pics += u; return true }
            if (c.second !in pics && good(c.second, strict)) { pics += c.second; return true }
            return false
        }
        if (fs.size > 1) for (k in fs.indices) { if (pics.size >= 2) break; for (c in ranked.filter { it.third == k || it.third == -1 }) if (add(c)) break }
        for (c in ranked) { if (pics.size >= 2) break; add(c) }
        // Repli : personnages populaires de l'œuvre
        suspend fun pickFrom(pool: List<String>, seed: Int): String? {
            if (pool.isEmpty()) return null
            for (k in 0 until minOf(8, pool.size)) { val u = pool[(seed + k * 7) % pool.size]; if (u !in pics && u !in used && good(u)) return u }
            for (k in 0 until minOf(6, pool.size)) { val u = pool[(seed + k * 7) % pool.size]; if (u !in pics && good(u)) return u }
            return null
        }
        fs.forEachIndexed { k, f -> if (pics.size < 2) pickFrom(media(f).drop(1).map { it.second }, h / (k + 1))?.let { pics += it } }
        if (pics.size < 2) fs.firstOrNull()?.let { pickFrom(media(it).drop(1).map { p -> p.second }, h / 7 + 3) }?.let { pics += it }
        used += pics
        return when { pics.size >= 2 -> "montage:" + pics.take(2).joinToString("|"); pics.size == 1 -> pics[0]; else -> null }
    }

    /** Test réseau rapide (hors file d'attente, sans nouvel essai) : statut et temps de réponse d'AniList et de Jikan. */
    suspend fun probe(): String = withContext(Dispatchers.IO) {
        fun t(name: String, f: () -> Int) = System.currentTimeMillis().let { t0 -> runCatching { "$name HTTP ${f()} (${System.currentTimeMillis() - t0} ms)" }.getOrElse { "$name ERREUR ${it.javaClass.simpleName} ${it.message?.take(60)}" } }
        val body = JSONObject().put("query", "{Media(search:\"One Piece\",type:ANIME){id}}").toString()
        t("AniList") { Jsoup.connect("https://graphql.anilist.co").ignoreContentType(true).ignoreHttpErrors(true).requestBody(body)
            .header("Content-Type", "application/json").method(org.jsoup.Connection.Method.POST).timeout(8000).execute().statusCode() } + " · " +
        t("Jikan") { Jsoup.connect("https://api.jikan.moe/v4/characters?limit=1&q=Luffy").ignoreContentType(true).ignoreHttpErrors(true).timeout(8000).execute().statusCode() } +
        " · dernier appel AniList de l'app : $lastAni · Jikan : $lastJikan"
    }

    /** Diagnostic instantané d'une histoire, uniquement à partir du cache (aucune requête) : ce que l'app sait et a décidé. */
    fun explain(w: Work): String {
        val r = StringBuilder("  « ${w.title.take(50)} »\n    couverture : ${w.cover.ifBlank { "aucune" }.take(110)}\n")
        val fs = fandomKey(w).removePrefix("Crossover : ").split(" × ").map { it.trim() }.filter { it.isNotBlank() && it != "Sans fandom" }
        r.append("    fandoms : ${fs.joinToString(" | ").ifEmpty { "aucun" }} · tags : ${w.tags.take(6).joinToString(", ").take(140)}\n")
        val cands = fs.flatMapIndexed { k, f ->
            val m = mem["M3:" + f.lowercase()] ?: prefs.getString("M3:" + f.lowercase(), null)?.split('\n')?.filter { '\t' in it }?.map { it.substringBefore('\t') to it.substringAfter('\t') }
            r.append("    AniList « $f » : ${m?.let { "${it.size} images en cache" } ?: "PAS ENCORE CHARGÉ"}\n")
            m.orEmpty().drop(1).map { Triple(it.first, it.second, k) }
        }
        val sc = scored(w, cands).take(5)
        if (cands.isNotEmpty() && sc.isEmpty()) r.append("    aucun personnage reconnu dans titre/résumé/tags → personnages populaires\n")
        fun q(u: String) = when (val v = cachedQuality(u)) { null -> "non testée"; -1 -> "inutilisable"; else -> if (v < 33) "OK" else "gros plan ($v % peau)" }
        for ((c, v) in sc) {
            val gal = prefs.getString("J:" + c.first.lowercase(), null)?.split('\n')?.filter { it.isNotBlank() }
            val onCover = w.cover.contains(c.second) || gal.orEmpty().any { w.cover.contains(it) }
            r.append("    • ${c.first} · score ${"%.1f".format(v)} · image ${q(c.second)} · galerie ${gal?.let { "${it.size} (OK ${it.count { u -> q(u) == "OK" }})" } ?: "non chargée"}${if (onCover) " · SUR LA COUVERTURE" else ""}\n")
        }
        return r.toString()
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
