package app.fanfichub

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Navigateur invisible : charge la page comme un vrai navigateur (passe les protections anti-robot
 * de type Cloudflare quand elles n'exigent pas de clic) puis renvoie le HTML.
 */
object Web {
    @SuppressLint("StaticFieldLeak") private var wv: WebView? = null
    lateinit var app: Context
    private val lock = Mutex()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun html(url: String): Document = Jsoup.parse(load(url, json = false), url)

    /** Texte brut de la page (réponses JSON des API), via le vrai navigateur et sa session. */
    suspend fun text(url: String): String = load(url, json = true)

    private suspend fun load(url: String, json: Boolean): String = lock.withLock {
        val raw = withContext(Dispatchers.Main) {
            val w = wv ?: WebView(app).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true }.also { wv = it }
            withTimeoutOrNull(60_000) {
                suspendCancellableCoroutine<String> { cont ->
                    var tries = 0
                    fun grab() {
                        w.evaluateJavascript(if (json) "document.title+'\\u0001'+document.body.innerText" else "document.title+'\\u0001'+document.documentElement.outerHTML") { r ->
                            val s = runCatching { JSONTokener(r).nextValue() as String }.getOrDefault("")
                            val title = s.substringBefore('\u0001')
                            val challenge = title.contains("Just a moment", true) || title.contains("Un instant", true) || title.contains("Attention Required", true)
                            if ((challenge || (!json && s.length < 1500)) && tries++ < 25) w.postDelayed({ if (cont.isActive) grab() }, 1500)
                            else if (cont.isActive) cont.resume(s)
                        }
                    }
                    w.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, u: String?) { if (cont.isActive && tries == 0) grab() }
                    }
                    cont.invokeOnCancellation { w.post { w.stopLoading() } }
                    w.loadUrl(url)
                }
            }
        } ?: error("Le site ne répond pas")
        val title = raw.substringBefore('\u0001')
        if (title.contains("Just a moment", true) || title.contains("Attention Required", true))
            error("Le site demande une vérification : ouvre-le une fois via « Ouvrir le site » dans l'onglet Compte")
        raw.substringAfter('\u0001')
    }
}

interface Site {
    val name: String
    val home: String
    val userHint: String
    fun owns(url: String): Boolean
    suspend fun info(url: String): Work?
    suspend fun download(w: Work): Pair<List<String>, String>
    suspend fun library(user: String, known: Map<String, Work>): List<Work>
    /** Erreur de format immédiate (null si le texte a la bonne forme). */
    fun format(user: String): String?
    /** Vérifie en ligne que le compte existe ; renvoie un résumé, ou lève une erreur lisible. */
    suspend fun verify(user: String): String
}

object Sites {
    val all = listOf(Spirit, Wattpad, Ffn)
    fun of(name: String) = all.firstOrNull { it.name == name }
    fun find(url: String) = all.firstOrNull { it.owns(url) }
}

fun sections(parts: List<Pair<String, String>>): Pair<List<String>, String> {
    val titles = parts.mapIndexed { i, (t, _) -> t.ifBlank { "Chapitre ${i + 1}" } }
    return titles to parts.mapIndexed { i, (_, h) ->
        "<section id='c$i'><h2>${Jsoup.clean(titles[i], Safelist.none())}</h2>${Jsoup.clean(h, Safelist.relaxed())}</section>"
    }.joinToString("")
}

/** Repère le bloc de texte principal d'une page (celui qui contient le plus de paragraphes). */
fun mainText(d: Document): String {
    d.select("script,style,nav,header,footer,aside,form,noscript").remove()
    return d.select("div,article,section").maxByOrNull { e ->
        e.children().filter { it.tagName() == "p" }.sumOf { it.text().length } + e.ownText().length
    }?.html() ?: ""
}

private fun digits(s: String?) = s?.filter { it.isDigit() }?.toIntOrNull() ?: 0

object Wattpad : Site {
    override val name = "Wattpad"
    override val home = "https://www.wattpad.com/login"
    override val userHint = "Ton pseudo Wattpad"
    private const val B = "https://www.wattpad.com"
    private const val F = "id,title,user(name),description,tags,completed,numParts,voteCount,mature,lastPublishedPart(createDate)"
    override fun owns(url: String) = "wattpad.com" in url

    private suspend fun json(u: String): String = Web.text(u).trim().also {
        if (!it.startsWith("{") && !it.startsWith("[")) error("Wattpad a renvoyé une page inattendue (connecte-toi via « Se connecter sur le site »)")
    }

    private fun work(o: JSONObject): Work {
        val n = o.optInt("numParts", 1); val done = o.optBoolean("completed")
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val id = o.get("id").toString()
        return Work("wp:$id", o.optString("title"), o.optJSONObject("user")?.optString("name") ?: "", listOf(tags.firstOrNull() ?: "Wattpad"),
            if (o.optBoolean("mature")) "Mature" else "", 0, "$n/${if (done) n else "?"}", o.optInt("voteCount"), o.optString("description").take(700),
            tags.take(14), o.optJSONObject("lastPublishedPart")?.optString("createDate")?.take(10) ?: "", done, setOf("Favori"),
            site = name, url = "$B/story/$id")
    }

    override suspend fun info(url: String): Work? {
        var id = Regex("/story/(\\d+)").find(url)?.groupValues?.get(1)
        if (id == null) Regex("wattpad\\.com/(\\d+)").find(url)?.groupValues?.get(1)?.let { pid ->
            id = JSONObject(json("$B/api/v3/story_parts/$pid?fields=groupId")).optString("groupId").ifEmpty { null }
        }
        return id?.let { work(JSONObject(json("$B/api/v3/stories/$it?fields=$F"))) }
    }

    override suspend fun download(w: Work): Pair<List<String>, String> {
        val parts = JSONObject(json("$B/api/v3/stories/${w.id.removePrefix("wp:")}?fields=parts(id,title)")).getJSONArray("parts")
        return sections((0 until parts.length()).map { i -> parts.getJSONObject(i).let { p -> p.optString("title") to text(p.get("id").toString()) } })
    }

    private suspend fun text(pid: String): String {
        val sb = StringBuilder()
        for (page in 1..30) {
            val h = Web.html("$B/apiv2/storytext?id=$pid&page=$page").body().html()
            if (h.isBlank() || sb.contains(h)) break
            sb.append(h)
        }
        return sb.toString()
    }

    private fun enc(user: String) = URLEncoder.encode(user.trim().removePrefix("@"), "UTF-8")

    private suspend fun lists(u: String): List<Pair<String, String>> {
        val a = JSONObject(json("$B/api/v3/users/$u/lists?limit=100&fields=lists(id,name)")).optJSONArray("lists") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).let { l -> l.get("id").toString() to l.optString("name").ifBlank { "Liste" } } }
    }

    private suspend fun listStories(id: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url: String? = "$B/api/v3/lists/$id/stories?limit=100&fields=stories($F),nextUrl"
        var n = 0
        while (url != null && n++ < 10) {
            val o = JSONObject(json(url))
            o.optJSONArray("stories")?.let { a -> for (i in 0 until a.length()) out += a.getJSONObject(i) }
            url = o.optString("nextUrl").ifEmpty { null }?.let { if (it.startsWith("http")) it else B + it }
        }
        return out
    }

    override suspend fun library(user: String, known: Map<String, Work>): List<Work> {
        val u = enc(user)
        val out = LinkedHashMap<String, Work>()
        fun put(w: Work) { out[w.id] = out[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        // Bibliothèque (si connecté), puis chaque liste de lecture.
        runCatching {
            val a = JSONObject(json("$B/api/v3/users/$u/library?limit=200&fields=stories($F)")).optJSONArray("stories") ?: JSONArray()
            for (i in 0 until a.length()) put(work(a.getJSONObject(i)).copy(sources = setOf("Bibliothèque")))
        }
        runCatching { lists(u) }.getOrDefault(emptyList()).forEach { (id, name) ->
            runCatching { listStories(id) }.getOrDefault(emptyList()).forEach { put(work(it).copy(sources = setOf("Liste : $name"))) }
        }
        if (out.isEmpty()) error("aucune histoire trouvée pour « $user » (bibliothèque et listes vides ou privées)")
        return out.values.toList()
    }

    data class Account(val username: String, val name: String, val avatar: String, val info: String)

    private fun acc(o: JSONObject) = Account(o.optString("username"), o.optString("name"), o.optString("avatar"),
        listOfNotNull(o.optInt("numLists", -1).takeIf { it >= 0 }?.let { "$it listes" }, o.optInt("numFollowers", -1).takeIf { it >= 0 }?.let { "$it abonnés" },
            o.optInt("numStoriesPublished", -1).takeIf { it > 0 }?.let { "$it histoires" }).joinToString(" · "))

    /** Cherche récursivement les objets « utilisateur » dans une réponse JSON de forme inconnue. */
    private fun findUsers(v: Any?): List<Account> = when (v) {
        is JSONObject -> if (v.has("username")) listOf(acc(v)) else v.keys().asSequence().toList().flatMap { findUsers(v.opt(it)) }
        is JSONArray -> (0 until v.length()).flatMap { findUsers(v.opt(it)) }
        else -> emptyList()
    }

    /** Recherche de comptes : pseudo exact d'abord, puis recherche du site. */
    suspend fun searchUsers(q: String): List<Account> {
        val e = URLEncoder.encode(q.trim().removePrefix("@"), "UTF-8")
        val out = LinkedHashMap<String, Account>()
        runCatching { findUsers(JSONTokener(json("$B/api/v3/users/$e?fields=username,name,avatar,numLists,numFollowers,numStoriesPublished")).nextValue()) }
            .getOrDefault(emptyList()).forEach { out[it.username.lowercase()] = it }
        for (u in listOf("$B/v4/search/users/?query=$e&limit=15&fields=username,name,avatar,numLists,numFollowers,numStoriesPublished", "$B/api/v3/users?query=$e&limit=15")) {
            val r = runCatching { findUsers(JSONTokener(json(u)).nextValue()) }.getOrDefault(emptyList())
            r.forEach { out.putIfAbsent(it.username.lowercase(), it) }
            if (r.isNotEmpty()) break
        }
        return out.values.filter { it.username.isNotBlank() }.take(10)
    }

    override fun format(user: String) = when {
        user.isBlank() -> "Entre ton pseudo"
        user.trim().contains(' ') -> "Un pseudo Wattpad ne contient pas d'espace"
        else -> null
    }

    override suspend fun verify(user: String): String {
        val o = JSONObject(json("$B/api/v3/users/${enc(user)}?fields=username,name"))
        val name = o.optString("username").ifEmpty { error("Pseudo « $user » introuvable sur Wattpad") }
        val l = runCatching { lists(enc(user)) }.getOrDefault(emptyList())
        return "Profil trouvé : @$name · ${l.size} liste" + (if (l.size > 1) "s" else "") + " de lecture" +
            (if (l.isNotEmpty()) " (" + l.take(3).joinToString { it.second } + (if (l.size > 3) "…" else "") + ")" else "")
    }
}

object Ffn : Site {
    override fun format(user: String) = if (Regex("\\d{3,}").containsMatchIn(user)) null else "Colle le lien de ton profil, ex. fanfiction.net/u/1234567"

    override suspend fun verify(user: String): String {
        val uid = Regex("(\\d{3,})").find(user)?.value ?: error("Numéro de profil introuvable dans le lien")
        val d = Web.html("$B/u/$uid/")
        if (d.title().contains("not exist", true) || d.selectFirst("#content_wrapper_inner") == null) error("Profil $uid introuvable sur FanFiction.net")
        val name = d.title().substringBefore('|').trim().ifEmpty { "n°$uid" }
        return "Profil trouvé : $name · ${d.select("div.favstories").size} histoires favorites"
    }

    override val name = "FanFiction.net"
    override val home = "https://www.fanfiction.net/"
    override val userHint = "Lien ou numéro de ton profil (fanfiction.net/u/…)"
    private const val B = "https://www.fanfiction.net"
    override fun owns(url: String) = "fanfiction.net" in url
    private fun stat(t: String, k: String) = Regex("$k:\\s*([\\d,]+)").find(t)?.groupValues?.get(1)
    private fun date(sec: String) = sec.toLongOrNull()?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000)) } ?: ""

    override suspend fun info(url: String): Work? {
        val id = Regex("/s/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val d = Web.html("$B/s/$id/1/")
        val top = d.selectFirst("#profile_top") ?: error("Page FanFiction.net illisible")
        val st = top.selectFirst("span.xgray")?.text() ?: ""
        val n = digits(stat(st, "Chapters")).coerceAtLeast(1)
        val done = "Status: Complete" in st
        return Work("ffn:$id", top.selectFirst("b.xcontrast_txt")?.text() ?: "Sans titre", top.selectFirst("a[href^=/u/]")?.text() ?: "",
            listOf(d.select("#pre_story_links a").lastOrNull()?.text() ?: name), Regex("Rated:\\s*(?:Fiction\\s*)?(\\S+)").find(st)?.groupValues?.get(1) ?: "",
            digits(stat(st, "Words")), "$n/${if (done) n else "?"}", digits(stat(st, "Favs")), top.selectFirst("div.xcontrast_txt")?.text() ?: "",
            emptyList(), Regex("Updated:\\s*([^-]+)").find(st)?.groupValues?.get(1)?.trim() ?: "", done, setOf("Favori"), site = name, url = "$B/s/$id")
    }

    override suspend fun download(w: Work): Pair<List<String>, String> {
        val id = w.id.removePrefix("ffn:")
        val first = Web.html("$B/s/$id/1/")
        val titles = first.selectFirst("select#chap_select")?.select("option")?.map { it.text() } ?: listOf("")
        return sections(titles.mapIndexed { i, t ->
            val d = if (i == 0) first else Web.html("$B/s/$id/${i + 1}/")
            t to (d.selectFirst("#storytext")?.html() ?: "")
        })
    }

    override suspend fun library(user: String, known: Map<String, Work>): List<Work> {
        val uid = Regex("(\\d+)").find(user)?.value ?: return emptyList()
        return Web.html("$B/u/$uid/").select("div.favstories").mapNotNull { e ->
            val id = e.attr("data-storyid").ifEmpty { return@mapNotNull null }
            val n = e.attr("data-chapters").toIntOrNull() ?: 1; val done = e.attr("data-statusid") == "2"
            Work("ffn:$id", e.attr("data-title"), e.select("a[href^=/u/]").firstOrNull()?.text() ?: "", listOf(e.attr("data-category").ifEmpty { name }), "",
                e.attr("data-wordcount").toIntOrNull() ?: 0, "$n/${if (done) n else "?"}", 0, e.selectFirst("div.z-indent")?.ownText() ?: "", emptyList(),
                date(e.attr("data-dateupdate")), done, setOf("Favori"), site = name, url = "$B/s/$id")
        }
    }
}

object Spirit : Site {
    override fun format(user: String) = when {
        user.isBlank() -> "Entre ton pseudo"
        user.trim().contains(' ') -> "Un pseudo Spirit ne contient pas d'espace"
        else -> null
    }

    override suspend fun verify(user: String): String {
        val d = Web.html("$B/perfil/${URLEncoder.encode(user.trim(), "UTF-8")}")
        val t = d.title()
        if (t.contains("404") || t.contains("encontrad", true) || d.selectFirst("h1") == null) error("Pseudo « $user » introuvable sur Spirit")
        return "Profil trouvé : ${d.selectFirst("h1")?.text() ?: user}"
    }

    override val name = "Spirit Fanfics"
    override val home = "https://www.spiritfanfics.com/login"
    override val userHint = "Ton pseudo Spirit"
    private const val B = "https://www.spiritfanfics.com"
    override fun owns(url: String) = "spiritfanfics.com" in url
    private fun slug(url: String) = Regex("/historia/([a-z0-9-]+-\\d+)").find(url)?.groupValues?.get(1)
    private fun chapters(d: Document, s: String) =
        d.select("a[href*=/historia/$s/]").filter { Regex("/historia/$s/[^/?#]+").containsMatchIn(it.attr("href")) && it.text().isNotBlank() }
            .map { it.absUrl("href").substringBefore('#') to it.text() }.distinctBy { it.first }

    override suspend fun info(url: String): Work? {
        val s = slug(url) ?: return null
        val d = Web.html("$B/historia/$s")
        val body = d.text()
        val n = chapters(d, s).size.coerceAtLeast(1)
        val done = Regex("Conclu[ií]da|Terminada|Completa", RegexOption.IGNORE_CASE).containsMatchIn(body)
        return Work("sp:$s", d.selectFirst("h1")?.text() ?: s, d.selectFirst("a[href*=/perfil/]")?.text() ?: "",
            listOf(d.selectFirst("a[href*=/categorias/]")?.text() ?: name), "", digits(Regex("([\\d.]+)\\s+palavras").find(body)?.groupValues?.get(1)),
            "$n/${if (done) n else "?"}", 0, d.selectFirst("meta[name=description]")?.attr("content") ?: "", emptyList(), "", done, setOf("Favori"),
            site = name, url = "$B/historia/$s")
    }

    override suspend fun download(w: Work): Pair<List<String>, String> {
        val s = w.id.removePrefix("sp:")
        val chaps = chapters(Web.html("$B/historia/$s"), s).ifEmpty { listOf("$B/historia/$s" to w.title) }
        return sections(chaps.map { (u, t) -> t to mainText(Web.html(u)) })
    }

    override suspend fun library(user: String, known: Map<String, Work>): List<Work> {
        val slugs = Web.html("$B/biblioteca").select("a[href*=/historia/]").mapNotNull { slug(it.attr("href")) }.distinct().take(80)
        return slugs.mapNotNull { s -> known["sp:$s"]?.takeIf { it.complete } ?: runCatching { info("$B/historia/$s") }.getOrNull() }
    }
}
