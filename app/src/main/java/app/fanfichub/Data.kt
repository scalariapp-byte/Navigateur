package app.fanfichub

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.File

data class Work(
    val id: String, val title: String, val author: String, val fandoms: List<String>, val rating: String,
    val words: Int, val chapters: String, val kudos: Int, val summary: String, val tags: List<String>,
    val updated: String, val complete: Boolean, val sources: Set<String>,
    val newChapters: Int = 0, val progress: Float = 0f, val lastRead: Long = 0L, val site: String = "AO3", val url: String = "",
    val cover: String = "", val checked: Long = 0L,
) {
    val link get() = url.ifEmpty { "${Ao3.BASE}/works/$id" }
    val published get() = chapters.substringBefore('/').trim().toIntOrNull() ?: 1
    val fandom get() = fandoms.firstOrNull() ?: "Original"

    fun json() = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("fandoms", JSONArray(fandoms)); put("rating", rating)
        put("words", words); put("chapters", chapters); put("kudos", kudos); put("summary", summary); put("tags", JSONArray(tags))
        put("updated", updated); put("complete", complete); put("sources", JSONArray(sources.toList()))
        put("newChapters", newChapters); put("progress", progress.toDouble()); put("lastRead", lastRead); put("site", site); put("url", url); put("cover", cover); put("checked", checked)
    }

    companion object {
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun from(o: JSONObject) = Work(
            o.getString("id"), o.getString("title"), o.getString("author"), o.getJSONArray("fandoms").strings(), o.optString("rating"),
            o.optInt("words"), o.optString("chapters", "1/1"), o.optInt("kudos"), o.optString("summary"), o.getJSONArray("tags").strings(),
            o.optString("updated"), o.optBoolean("complete"), o.getJSONArray("sources").strings().toSet(),
            o.optInt("newChapters"), o.optDouble("progress", 0.0).toFloat(), o.optLong("lastRead"), o.optString("site", "AO3"), o.optString("url"), o.optString("cover"), o.optLong("checked"),
        )
    }
}

class Store(private val dir: File) {
    private val f = File(dir, "works.json")
    fun load(): List<Work> = runCatching { JSONArray(f.readText()).let { a -> (0 until a.length()).map { Work.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun save(list: List<Work>) = f.writeText(JSONArray(list.map { it.json() }).toString())
    fun text(id: String) = File(dir, "w_$id.html")
    fun titles(id: String) = File(dir, "t_$id.txt")
    fun fr(id: String) = File(dir, "w_${id}_fr.html")
    fun clearFr() { dir.listFiles()?.filter { it.name.endsWith("_fr.html") }?.forEach { it.delete() } }
    fun frIds(): Set<String> = dir.list()?.filter { it.startsWith("w_") && it.endsWith("_fr.html") }?.map { it.removePrefix("w_").removeSuffix("_fr.html") }?.toSet() ?: emptySet()
    fun downloadedIds(): Set<String> = dir.list()?.filter { it.startsWith("w_") && it.endsWith(".html") && !it.endsWith("_fr.html") }
        ?.map { it.removePrefix("w_").removeSuffix(".html") }?.toSet() ?: emptySet()
}

const val UA = "Mozilla/5.0 (Linux; Android 14) FanficHub/1.0 (lecteur personnel)"

fun cookiesFor(base: String): Map<String, String> = CookieManager.getInstance().getCookie(base)?.split(";")
    ?.mapNotNull { c -> c.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap() ?: emptyMap()

/** AO3 n'a pas d'API publique : on lit les pages HTML avec la session du compte, lentement (une requête toutes les ~0,8 s). */
object Ao3 {
    const val BASE = "https://archiveofourown.org"
    private fun cookies() = cookiesFor(BASE)

    private fun get(path: String): Document {
        var wait = 350L
        repeat(4) { i ->
            Thread.sleep(wait)
            try {
                return Jsoup.connect(if (path.startsWith("http")) path else BASE + path).userAgent(UA).cookies(cookies()).timeout(30000).get()
            } catch (e: HttpStatusException) {
                if (e.statusCode != 429 && e.statusCode != 503) throw e
                wait = 6000L * (i + 1) // AO3 demande de ralentir
            }
        }
        error("AO3 limite les requêtes, réessaie dans quelques minutes")
    }

    /** Une page de blurbs : (fanfictions, lien de la page suivante). */
    fun page(url: String, src: String): Pair<List<Work>, String?> {
        val d = get(url)
        return d.select("li.blurb").mapNotNull { blurb(it, src) } to d.selectFirst("ol.pagination li.next a[href]")?.attr("href")
    }

    fun subsPage(url: String): Pair<List<String>, String?> {
        val d = get(url)
        return d.select("#main dt a[href^=/works/]").map { it.attr("href").removePrefix("/works/").substringBefore('/') } to
            d.selectFirst("ol.pagination li.next a[href]")?.attr("href")
    }

    private fun num(s: String?) = s?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    private fun complete(ch: String) = ch.split('/').let { it.size == 2 && it[0].trim() == it[1].trim() }

    /** Recherche AO3 avec filtres (paramètres officiels du moteur de recherche). */
    fun search(q: SearchQuery, page: Int): List<Work> {
        fun e(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val p = StringBuilder("/works/search?commit=Search&page=$page")
        p.append("&work_search%5Bquery%5D=").append(e(q.text))
        p.append("&work_search%5Bsort_column%5D=").append(when (q.sort) { 1 -> "kudos_count"; 2 -> "revised_at"; 3 -> "word_count"; else -> "_score" })
        p.append("&work_search%5Bsort_direction%5D=desc")
        when (q.status) { 1 -> p.append("&work_search%5Bcomplete%5D=T"); 2 -> p.append("&work_search%5Bcomplete%5D=F") }
        if (q.lang.isNotEmpty()) p.append("&work_search%5Blanguage_id%5D=").append(q.lang)
        if (q.fandom.isNotBlank()) p.append("&work_search%5Bfandom_names%5D=").append(e(q.fandom))
        if (q.character.isNotBlank()) p.append("&work_search%5Bcharacter_names%5D=").append(e(q.character))
        if (q.rating > 0) p.append("&work_search%5Brating_ids%5D=").append(q.rating)
        if (q.minWords > 0) p.append("&work_search%5Bwords_from%5D=").append(q.minWords)
        return this.page(p.toString(), "Recherche").first
    }

    /** Auto-complétion officielle d'AO3 (kind : fandom, character, relationship, freeform). */
    fun autocomplete(kind: String, term: String): List<String> = runCatching {
        val body = Jsoup.connect("$BASE/autocomplete/$kind?term=" + java.net.URLEncoder.encode(term.trim(), "UTF-8"))
            .ignoreContentType(true).userAgent(UA).cookies(cookies()).timeout(15000).execute().body()
        val a = JSONArray(body)
        (0 until a.length()).map { a.getJSONObject(it).optString("name").ifBlank { a.getJSONObject(it).optString("id") } }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun username(): String? = runCatching {
        get("/").selectFirst("#greeting a[href^=/users/], ul.user.navigation a[href^=/users/], a.dropdown-toggle[href^=/users/]")
            ?.attr("href")?.removePrefix("/users/")?.substringBefore('/')?.ifBlank { null }
    }.getOrNull()

    private fun blurb(b: Element, src: String): Work? {
        val a = b.selectFirst("h4.heading a[href^=/works/]") ?: return null
        val ch = b.selectFirst("dd.chapters")?.text() ?: "1/1"
        return Work(
            id = a.attr("href").removePrefix("/works/").substringBefore('/').substringBefore('?'),
            title = a.text(), author = b.select("a[rel=author]").joinToString { it.text() }.ifEmpty { "Anonyme" },
            fandoms = b.select("h5.fandoms a.tag").map { it.text() }, rating = b.selectFirst("span.rating")?.attr("title") ?: "",
            words = num(b.selectFirst("dd.words")?.text()), chapters = ch, kudos = num(b.selectFirst("dd.kudos")?.text()),
            summary = b.selectFirst("blockquote.summary")?.text() ?: "", tags = b.select("li.freeforms a.tag").map { it.text() }.take(14),
            updated = b.selectFirst("p.datetime")?.text() ?: "", complete = complete(ch), sources = setOf(src),
        )
    }

    /** Liste paginée de « blurbs » (favoris, à lire plus tard). */
    fun list(path: String, src: String, maxPages: Int = 20): List<Work> {
        val out = mutableListOf<Work>(); var url: String? = path; var n = 0
        while (url != null && n++ < maxPages) {
            val d = get(url)
            d.select("li.blurb").forEach { b -> blurb(b, src)?.let { out += it } }
            url = d.selectFirst("ol.pagination li.next a[href]")?.attr("href")
        }
        return out
    }

    fun subscriptionIds(user: String, maxPages: Int = 10): List<String> {
        val out = mutableListOf<String>(); var url: String? = "/users/$user/subscriptions?type=works"; var n = 0
        while (url != null && n++ < maxPages) {
            val d = get(url)
            d.select("#main dt a[href^=/works/]").forEach { out += it.attr("href").removePrefix("/works/").substringBefore('/') }
            url = d.selectFirst("ol.pagination li.next a[href]")?.attr("href")
        }
        return out.distinct()
    }

    /** Nombre de chapitres publiés, via la page sommaire (bien plus légère que la fanfiction). */
    fun chapterCount(id: String): Int? = runCatching { get("/works/$id/navigate?view_adult=true").select("ol.chapter.index li").size.takeIf { it > 0 } }.getOrNull()

    fun work(id: String, src: String): Work? = runCatching {
        val d = get("/works/$id?view_adult=true")
        val ch = d.selectFirst("dl.stats dd.chapters")?.text() ?: "1/1"
        Work(
            id, d.selectFirst("h2.title")?.text() ?: "Sans titre", d.select("h3.byline a[rel=author]").joinToString { it.text() }.ifEmpty { "Anonyme" },
            d.select("dd.fandom a.tag").map { it.text() }, d.selectFirst("dd.rating a.tag")?.text() ?: "",
            num(d.selectFirst("dl.stats dd.words")?.text()), ch, num(d.selectFirst("dl.stats dd.kudos")?.text()),
            d.selectFirst("div.summary blockquote")?.text() ?: "", d.select("dd.freeform a.tag").map { it.text() }.take(14),
            d.selectFirst("dl.stats dd.status")?.text() ?: d.selectFirst("dl.stats dd.published")?.text() ?: "", complete(ch), setOf(src),
        )
    }.getOrNull()

    /** Texte complet, nettoyé, découpé en sections par chapitre. Renvoie (titres des chapitres, html). */
    fun download(id: String): Pair<List<String>, String> {
        val d = get("/works/$id?view_full_work=true&view_adult=true")
        val root = d.selectFirst("#chapters") ?: error("Texte introuvable (fanfiction réservée aux membres ? connecte-toi)")
        val parts: List<Element> = root.select("> div.chapter").ifEmpty { listOf(root) }
        val titles = mutableListOf<String>()
        val html = parts.mapIndexed { i, c ->
            val t = c.selectFirst("h3.title")?.text()?.ifBlank { null } ?: if (parts.size > 1) "Chapitre ${i + 1}" else ""
            titles += t.ifEmpty { "Début" }
            val body = c.selectFirst("div.userstuff[role=article]") ?: c.selectFirst("div.userstuff") ?: c
            body.select("h3.landmark").remove()
            "<section id='c$i'>${if (t.isNotEmpty()) "<h2>${Jsoup.clean(t, Safelist.none())}</h2>" else ""}${Jsoup.clean(body.html(), Safelist.relaxed())}</section>"
        }.joinToString("")
        return titles to html
    }
}


/** Critères de recherche. sort : 0 pertinence, 1 populaires, 2 récentes, 3 longues ; status : 0 toutes, 1 terminées, 2 en cours. */
data class SearchQuery(
    val text: String = "", val site: String = "AO3", val sort: Int = 0, val status: Int = 0,
    val lang: String = "", val rating: Int = 0, val minWords: Int = 0, val fandom: String = "",
    val character: String = "", val wpLists: Boolean = false, val spirit: Map<String, Pair<String, String>> = emptyMap(),
)

/** Liste de lecture trouvée (Wattpad). */
data class ListHit(val id: String, val name: String, val count: Int, val user: String, val cover: String)

/** Champ du formulaire de recherche d'un site (nom, libellé, options valeur → texte). */
data class FormField(val name: String, val label: String, val options: List<Pair<String, String>>)
