package app.fanfichub

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Extension communautaire : un fichier JSON hébergé sur GitHub qui décrit comment chercher sur un site.
 * Sélecteurs CSS ; « sélecteur@attribut » pour lire un attribut (ex. « a@href », « img@src »).
 */
data class ExtDef(
    val id: String, val name: String, val logo: String, val color: Long, val version: Int,
    val search: String, val item: String, val title: String, val link: String,
    val author: String, val cover: String, val summary: String, val raw: String,
    val search1: String = "", val alt: List<String> = emptyList(), // 1re page sans pagination ; adresses de repli
    val post: String = "", // corps envoyé en POST (formulaires qui ignorent les paramètres d'adresse)
) {
    companion object {
        fun parse(s: String): ExtDef = JSONObject(s).let { o ->
            ExtDef(o.getString("id"), o.getString("name"), o.optString("logo", o.getString("name").take(2)),
                runCatching { android.graphics.Color.parseColor(o.optString("color", "#8E7CFF")).toLong() and 0xFFFFFFFFL }.getOrDefault(0xFF8E7CFFL),
                o.optInt("version", 1), o.getString("search"), o.getString("item"), o.getString("title"), o.getString("link"),
                o.optString("author"), o.optString("cover"), o.optString("summary"), s,
                o.optString("search1"), o.optJSONArray("alt")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optString("post"))
        }
    }

    private suspend fun fetch(u: String, lane: String, q: String, page: Int): org.jsoup.nodes.Document =
        if (post.isNotBlank()) Web.htmlPost(u, post.replace("{q}", URLEncoder.encode(q.trim(), "UTF-8")).replace("{page}", "$page"), lane) else Web.html(u, lane)

    /** « Dragon's Edge » → « dragons-edge » (adresses de tags). */
    private fun slugOf(q: String) = java.text.Normalizer.normalize(q.trim().lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace("'", "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun pick(e: Element, spec: String): String {
        if (spec.isBlank()) return ""
        val sel = spec.substringBefore('@').trim(); val attr = spec.substringAfter('@', "")
        val el = if (sel.isEmpty()) e else e.selectFirst(sel) ?: return ""
        return if (attr.isEmpty()) el.text().trim() else el.absUrl(attr).ifBlank { el.attr(attr) }.trim()
    }

    /** Diagnostic : chaque adresse de recherche testée (téléphone / ordinateur), page reçue et histoires reconnues. */
    suspend fun diag(q: String = "dragon"): String {
        val r = StringBuilder("  $name v$version${if (post.isNotBlank()) " · POST $post" else ""}\n")
        fun fill(t: String, p: Int) = t.replace("{q}", URLEncoder.encode(q, "UTF-8")).replace("{slug}", slugOf(q)).replace("{page}", "$p")
        val urls = (listOf(search1.takeIf { it.isNotBlank() }?.let { fill(it, 1) }, fill(search, 1)) + alt.map { fill(it, 1) }).filterNotNull().distinct()
        for (u in urls) for (lane in listOf("ui", "pc")) {
            val t0 = System.currentTimeMillis()
            val res = runCatching { kotlinx.coroutines.withTimeout(25_000) { fetch(u, lane, q, 1) } }
            val d = res.getOrNull()
            if (d == null) { r.append("    ✕ [$lane] $u → ERREUR ${res.exceptionOrNull()?.message?.take(60)}\n"); continue }
            val n = d.select(item).size
            // Indices pour corriger les sélecteurs : classes des blocs qui contiennent des liens
            val hints = d.select("[class]").flatMap { it.classNames() }.filter { it.isNotBlank() && Regex("(?i)search|result|fic|story|series|item|title").containsMatchIn(it) }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key}×${it.value}" }
            val paths = d.select("a[href]").mapNotNull { a -> runCatching { java.net.URI(a.absUrl("href")).path.trim('/').split('/').firstOrNull()?.takeIf { it.isNotBlank() } }.getOrNull() }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "/${it.key}×${it.value}" }
            r.append("    ${if (n > 0) "✓" else "✕"} [$lane] $u (${System.currentTimeMillis() - t0} ms)\n      page « ${d.title().take(60)} » · ${d.location().take(90)} · « $item » : $n\n      classes : ${hints.ifEmpty { "—" }}\n      liens : $paths\n")
            // Chaque formulaire : adresse d'envoi + tous ses champs (texte, listes, cases) avec un exemple de valeur
            d.select("form").forEachIndexed { fi, f ->
                val fs = f.select("input[name], select[name], textarea[name]").filter { it.attr("type") != "submit" }
                    .map { e -> e.attr("name") + when { e.tagName() == "select" -> "[liste:" + e.select("option").take(2).joinToString("/") { it.attr("value") } + "]"; e.attr("type") in listOf("checkbox", "radio") -> "[case=" + e.attr("value") + "]"; e.attr("type") == "hidden" -> "[caché=" + e.attr("value").take(15) + "]"; else -> "[texte" + (e.attr("placeholder").takeIf { it.isNotBlank() }?.let { " « $it »" } ?: "") + "]" } }
                    .distinct()
                if (fs.isNotEmpty()) r.append("      formulaire ${fi + 1} → ${f.attr("method").ifBlank { "get" }} ${f.absUrl("action").ifBlank { "(même page)" }.take(70)} : ${fs.joinToString(", ").take(700)}\n")
            }
            if (n > 0) r.append("      titres : " + d.select(item).take(4).joinToString(" | ") { pick(it, title).take(35) } + "\n")
            if (n > 0) d.select(item).first()?.let { e -> r.append("      1er résultat : titre « ${pick(e, title).take(50)} » · lien ${pick(e, link).take(70)}\n") }
        }
        return r.toString()
    }

    /** Recherche sur le site via le navigateur intégré (passe les protections anti-robot). */
    suspend fun search(q: String, page: Int): List<Work> {
        fun fill(t: String) = t.replace("{q}", URLEncoder.encode(q.trim(), "UTF-8")).replace("{slug}", slugOf(q)).replace("{page}", "$page")
        // Adresse principale, puis les adresses de repli ; chacune en mode téléphone puis ordinateur
        val urls = (listOf(if (page == 1 && search1.isNotBlank()) search1 else search) + alt).map(::fill).distinct()
        var d = fetch(urls[0], "ui", q, page)
        outer@ for (u in urls) for (lane in listOf("ui", "pc")) {
            if (u == urls[0] && lane == "ui") { if (d.select(item).isNotEmpty()) break@outer; continue }
            val x = runCatching { fetch(u, lane, q, page) }.getOrNull() ?: continue
            if (x.select(item).isNotEmpty()) { d = x; break@outer }
        }
        if (d.select(item).isEmpty()) {
            val t = d.title().ifBlank { "page vide" }
            if (Regex("(?i)just a moment|attention required|un instant|cloudflare|verify").containsMatchIn(t + d.text().take(300)))
                error("$name demande une vérification anti-robot. Ouvre le site une fois dans l'app (Réglages › Extensions), puis réessaie.")
            if (Regex("(?i)no results|nothing found|aucun|0 results").containsMatchIn(d.text())) return emptyList()
            error("$name : page reçue « ${t.take(60)} » mais aucune histoire reconnue (extension à mettre à jour)")
        }
        return d.select(item).mapNotNull { e ->
            val link = pick(e, link).ifBlank { return@mapNotNull null }
            val t = pick(e, title).ifBlank { return@mapNotNull null }
            Work("x:$id:" + link.hashCode().toUInt().toString(36), t, pick(e, author), listOf(name), "", 0, "?/?", 0,
                pick(e, summary).take(700), emptyList(), "", false, setOf("Recherche"), site = name, url = link,
                cover = pick(e, cover), checked = System.currentTimeMillis())
        }.distinctBy { it.url }
    }
}

/** Extensions : les 4 sites intégrés (activables) + extensions communautaires installées depuis des dépôts GitHub. */
object Ext {
    val builtIn = listOf("AO3", "Wattpad", "FanFiction.net", "Spirit Fanfics")
    const val DEFAULT_REPO = "https://raw.githubusercontent.com/scalariapp-byte/Navigateur/claude/password-manager-app-8t7fic/extensions/index.json"
    val enabled = mutableStateListOf<String>()
    val installed = mutableStateListOf<ExtDef>()
    val catalog = mutableStateListOf<Pair<String, String>>() // nom → adresse du fichier
    val repos = mutableStateListOf<String>()
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ext", Context.MODE_PRIVATE)
        enabled.clear(); enabled.addAll(prefs.getStringSet("enabled", null) ?: builtIn.toSet())
        repos.clear(); repos.addAll(prefs.getString("repos", null)?.split('\n')?.filter { it.isNotBlank() } ?: listOf(DEFAULT_REPO))
        installed.clear(); prefs.getString("installed", null)?.let { s -> JSONArray(s).let { a -> (0 until a.length()).mapNotNull { runCatching { ExtDef.parse(a.getString(it)) }.getOrNull() } } }?.let { installed.addAll(it) }
    }
    private fun save() = prefs.edit().putStringSet("enabled", enabled.toSet()).putString("repos", repos.joinToString("\n"))
        .putString("installed", JSONArray(installed.map { it.raw }).toString()).apply()

    fun on(site: String) = site in enabled
    fun toggle(site: String) { if (site in enabled) enabled.remove(site) else enabled.add(site); save() }
    fun byName(name: String) = installed.firstOrNull { it.name == name }

    private suspend fun get(url: String) = withContext(Dispatchers.IO) {
        Jsoup.connect(url).ignoreContentType(true).userAgent(BROWSER_UA).timeout(20000).execute().body()
    }

    /** Lit l'index de chaque dépôt : { "extensions": [ { "name": "...", "url": "https://…/site.json" } ] } */
    suspend fun refresh(): String? = runCatching {
        val out = mutableListOf<Pair<String, String>>()
        for (r in repos) runCatching {
            val a = JSONObject(get(r)).getJSONArray("extensions")
            for (i in 0 until a.length()) a.getJSONObject(i).let { o -> out += o.getString("name") to o.getString("url") }
        }
        catalog.clear(); catalog.addAll(out.distinctBy { it.second }); null
    }.getOrElse { it.message }

    suspend fun install(url: String): String? = runCatching {
        val d = ExtDef.parse(get(url))
        installed.removeAll { it.id == d.id }; installed.add(d); save(); null
    }.getOrElse { "Extension invalide : ${it.message}" }

    /** Met à jour les extensions installées quand le dépôt publie une version plus récente (au lancement). */
    suspend fun updateAll() = runCatching {
        if (installed.isEmpty()) return@runCatching
        refresh()
        for (d in installed.toList()) {
            val url = catalog.firstOrNull { it.first == d.name }?.second ?: continue
            val n = runCatching { ExtDef.parse(get(url)) }.getOrNull() ?: continue
            if (n.version > d.version) withContext(Dispatchers.Main) { val i = installed.indexOfFirst { it.id == d.id }; if (i >= 0) installed[i] = n; save() }
        }
    }

    fun uninstall(id: String) { installed.removeAll { it.id == id }; save() }
    fun addRepo(url: String) { val u = url.trim(); if (u.startsWith("http") && u !in repos) { repos.add(u); save() } }
    fun removeRepo(url: String) { repos.remove(url); save() }
}
