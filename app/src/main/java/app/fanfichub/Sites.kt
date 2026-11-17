package app.fanfichub

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    lateinit var app: Context
    /** Un navigateur par usage : la synchro et les recherches de l'écran ne s'attendent pas. */
    private class Lane { @SuppressLint("StaticFieldLeak") var wv: WebView? = null; val lock = Mutex() }
    private val lanes = HashMap<String, Lane>()
    private fun lane(name: String) = synchronized(lanes) { lanes.getOrPut(name) { Lane() } }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun html(url: String, lane: String = "sync"): Document = Jsoup.parse(load(url, json = false, lane = lane), url)

    /** Texte brut de la page (réponses JSON des API), via le vrai navigateur et sa session. */
    suspend fun text(url: String, lane: String = "sync"): String = load(url, json = true, lane = lane)

    private suspend fun load(url: String, json: Boolean, lane: String): String = lane(lane).let { l -> l.lock.withLock {
        val raw = withContext(Dispatchers.Main) {
            val w = l.wv ?: WebView(app).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true }.also { l.wv = it }
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
    } }
}

/** Compte trouvé par une recherche : [id] est la valeur enregistrée, [handle] ce qu'on affiche. */
data class Account(val id: String, val name: String, val handle: String, val avatar: String, val info: String)

interface Site {
    val searchable: Boolean get() = false
    suspend fun searchUsers(q: String): List<Account> = emptyList()
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

const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

private fun digits(s: String?) = s?.filter { it.isDigit() }?.toIntOrNull() ?: 0

object Wattpad : Site {
    override val name = "Wattpad"
    override val home = "https://www.wattpad.com/login"
    override val userHint = "Ton pseudo Wattpad"
    private const val B = "https://www.wattpad.com"
    private const val F = "id,title,user(name),description,tags,completed,numParts,voteCount,mature,cover,lastPublishedPart(createDate)"
    override fun owns(url: String) = "wattpad.com" in url

    /** Mis à vrai pour forcer le passage par le navigateur intégré (si les requêtes directes ne donnent rien). */
    private var slow = false

    private suspend fun json(u: String, lane: String = "sync"): String {
        if (!slow) {
            val direct = withContext(Dispatchers.IO) {
                runCatching {
                    val r = Jsoup.connect(u).ignoreContentType(true).ignoreHttpErrors(true).userAgent(BROWSER_UA).header("Accept", "application/json")
                        .cookies(cookiesFor(B)).timeout(20000).execute()
                    r.body().trim().takeIf { r.statusCode() == 200 }
                }.getOrNull()
            }
            val isError = direct != null && Regex("^\\{\\s*\"(error|error_type|code|message)\"").containsMatchIn(direct)
            if (direct != null && !isError && (direct.startsWith("{") || direct.startsWith("["))) return direct
        }
        return slowJson(u, lane)
    }

    private suspend fun slowJson(u: String, lane: String = "sync"): String = Web.text(u, lane).trim().also {
        if (!it.startsWith("{") && !it.startsWith("[")) error("Wattpad a renvoyé une page inattendue (connecte-toi via « Se connecter sur le site »)")
    }

    private fun work(o: JSONObject): Work {
        val n = o.optInt("numParts", 1); val done = o.optBoolean("completed")
        val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val id = o.get("id").toString()
        return Work("wp:$id", o.optString("title"), o.optJSONObject("user")?.optString("name") ?: "", listOf(tags.firstOrNull() ?: "Wattpad"),
            if (o.optBoolean("mature")) "Mature" else "", 0, "$n/${if (done) n else "?"}", o.optInt("voteCount"), o.optString("description").take(700),
            tags.take(14), o.optJSONObject("lastPublishedPart")?.optString("createDate")?.take(10) ?: "", done, setOf("Favori"),
            site = name, url = "$B/story/$id", cover = o.optString("cover"), checked = System.currentTimeMillis())
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
            val h = withContext(Dispatchers.IO) {
                runCatching { Jsoup.connect("$B/apiv2/storytext?id=$pid&page=$page").ignoreContentType(true).userAgent(BROWSER_UA).cookies(cookiesFor(B)).timeout(20000).execute().body() }.getOrNull()
            } ?: Web.html("$B/apiv2/storytext?id=$pid&page=$page").body().html()
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
        slow = false
        val r = runCatching { fetchLibrary(user) }.getOrDefault(emptyList())
        if (r.isNotEmpty()) return r
        slow = true // nouvel essai via le navigateur intégré
        try { return fetchLibrary(user).ifEmpty { error("aucune histoire trouvée pour « $user » (listes vides ou privées)") } } finally { slow = false }
    }

    private suspend fun fetchLibrary(user: String): List<Work> {
        val u = enc(user)
        val out = LinkedHashMap<String, Work>()
        fun put(w: Work) { out[w.id] = out[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        // Bibliothèque (si connecté), puis chaque liste de lecture.
        runCatching {
            val a = JSONObject(json("$B/api/v3/users/$u/library?limit=200&fields=stories($F)")).optJSONArray("stories") ?: JSONArray()
            for (i in 0 until a.length()) put(work(a.getJSONObject(i)).copy(sources = setOf("Bibliothèque")))
        }
        coroutineScope {
            runCatching { lists(u) }.getOrDefault(emptyList()).map { (id, name) -> async { name to runCatching { listStories(id) }.getOrDefault(emptyList()) } }
                .awaitAll().forEach { (name, st) -> st.forEach { put(work(it).copy(sources = setOf("Liste : $name"))) } }
        }
        return out.values.toList()
    }

    override val searchable = true

    /** Recherche d'histoires (API de recherche, puis API v3 en secours). */
    suspend fun searchStories(q: String, offset: Int): List<Work> {
        val e = URLEncoder.encode(q.trim(), "UTF-8")
        for (u in listOf("$B/v4/search/stories/?query=$e&limit=20&offset=$offset&mature=true&fields=stories($F)",
                "$B/api/v3/stories?query=$e&limit=20&offset=$offset&fields=stories($F)")) {
            val a = runCatching { JSONObject(json(u, "ui")).optJSONArray("stories") }.getOrNull() ?: continue
            if (a.length() > 0) return (0 until a.length()).map { work(a.getJSONObject(it)).copy(sources = setOf("Recherche")) }
        }
        return emptyList()
    }

    private val listCache = HashMap<String, List<ListHit>>()
    private fun hit(o: JSONObject) = ListHit(o.get("id").toString(), o.optString("name"), o.optInt("numStories"),
        o.optJSONObject("user")?.optString("name") ?: "", o.optString("cover"), o.optInt("numStories"))

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")
    /** L'histoire porte-t-elle le(s) tag(s) cherché(s) ? (tous les mots, accents/majuscules/# ignorés) */
    fun tagged(tags: List<String>, title: String, q: String): Boolean {
        val words = q.split(Regex("[\\s,]+")).map(::norm).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { t -> tags.any { norm(it).contains(t) } || norm(title).contains(t) }
    }

    fun splitTags(q: String) = q.split(Regex("[\\s,+&]+|\\bet\\b")).map(::norm).filter { it.isNotEmpty() }.distinct()

    /**
     * Listes de tout Wattpad contenant des histoires avec CHACUN des tags (ex. « luffy dxd »). 25 robots en parallèle :
     * 1) histoires de chaque tag + des tags combinés, 2) leurs auteurs, puis les LECTEURS (abonnés de ces auteurs,
     * y compris ceux qui n'ont rien publié), 3) chaque liste de ces comptes est lue et analysée dès qu'elle est trouvée.
     */
    suspend fun scanLists(query: String, onProgress: (String) -> Unit, onHit: (ListHit) -> Unit, onStory: (Work, Int) -> Unit = { _, _ -> }) = coroutineScope {
        val tags = splitTags(query)
        if (tags.isEmpty()) error("tag vide")
        val bots = Semaphore(32)
        fun matched(w: Work) = tags.count { t -> tagged(w.tags, w.title, t) }
        val queries = (tags + if (tags.size > 1) listOf(tags.joinToString(" ")) else emptyList()).distinct()
        val stories = queries.flatMap { t -> (0 until 8).map { p -> async { bots.withPermit { runCatching { searchStories(t, p * 20) }.getOrDefault(emptyList()) } } } }
            .awaitAll().flatten().distinctBy { it.id }
        if (stories.isEmpty()) error("aucune histoire trouvée")
        stories.forEach { w -> matched(w).takeIf { it > 0 }?.let { onStory(w, it) } }
        val byTag = tags.map { t -> stories.filter { w -> w.tags.any { norm(it).contains(t) } || norm(w.title).contains(t) }.map { it.author } }
        val authors = (byTag.flatMap { it.withIndex() }.sortedBy { it.index }.map { it.value } + stories.map { it.author })
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(120)

        val seenUsers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val seenLists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val users = java.util.concurrent.atomic.AtomicInteger(); val lists = java.util.concurrent.atomic.AtomicInteger(); val found = java.util.concurrent.atomic.AtomicInteger()
        fun progress() = onProgress("${users.get()} comptes · ${lists.get()} listes analysées · ${found.get()} trouvées")

        suspend fun analyse(h: ListHit) = bots.withPermit {
            runCatching {
                val a = JSONObject(json("$B/api/v3/lists/${h.id}/stories?limit=100&fields=stories($F)", "ui")).optJSONArray("stories") ?: JSONArray()
                val st = (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    val t = o.optJSONArray("tags")
                    val all = (if (t == null) emptyList() else (0 until t.length()).map { norm(t.optString(it)) }) + norm(o.optString("title"))
                    tags.filter { tg -> all.any { it.contains(tg) } }.toSet().also { m ->
                        if (m.isNotEmpty()) runCatching { onStory(work(o).copy(sources = setOf("Recherche")), m.size) }
                    }
                } }
                val per = tags.map { tg -> st.count { tg in it } }
                if (per.all { it > 0 }) {
                    val both = st.count { it.size == tags.size }
                    val detail = (if (tags.size > 1 && both > 0) "$both avec tous les tags · " else "") + tags.indices.joinToString(" · ") { "#${tags[it]} ${per[it]}" }
                    found.incrementAndGet(); onHit(h.copy(count = per.sum(), score = both * 1000 + per.min(), detail = detail))
                }
            }
            lists.incrementAndGet(); progress()
        }

        /** Un compte : ses listes, analysées tout de suite. */
        suspend fun visit(u: String): Unit = coroutineScope {
            if (!seenUsers.add(u.lowercase())) return@coroutineScope
            val ls = bots.withPermit { runCatching {
                val a = JSONObject(json("$B/api/v3/users/${enc(u)}/lists?limit=50&fields=lists(id,name,numStories,cover,user(name))", "ui")).optJSONArray("lists") ?: JSONArray()
                (0 until a.length()).map { hit(a.getJSONObject(it)) }
            }.getOrDefault(emptyList()) }
            users.incrementAndGet(); progress()
            ls.filter { it.total in 1..1000 && seenLists.add(it.id) }.map { async { analyse(it) } }.awaitAll()
            Unit
        }

        /** Lecteurs d'un auteur : ses abonnés (souvent des comptes sans histoire publiée). */
        suspend fun readers(u: String): List<String> = bots.withPermit { runCatching {
            val v = JSONTokener(json("$B/api/v3/users/${enc(u)}/followers?limit=100&fields=users(username)", "ui")).nextValue()
            fun names(x: Any?): List<String> = when (x) {
                is JSONObject -> if (x.has("username")) listOf(x.optString("username")) else x.keys().asSequence().toList().flatMap { names(x.opt(it)) }
                is JSONArray -> (0 until x.length()).flatMap { names(x.opt(it)) }
                else -> emptyList()
            }
            names(v).filter { it.isNotBlank() }
        }.getOrDefault(emptyList()) }

        onProgress("${stories.size} histoires · ${authors.size} auteurs · recherche des lecteurs…")
        val jobs = authors.map { a -> async { visit(a) } }.toMutableList()
        // Lecteurs : abonnés des 40 auteurs les plus liés aux tags, jusqu'à 600 comptes
        val pool = authors.take(40).map { a -> async { readers(a) } }.awaitAll()
        val readerNames = pool.flatMap { it.withIndex() }.sortedBy { it.index }.map { it.value }.distinctBy { it.lowercase() }.take(600)
        jobs += readerNames.map { r -> async { visit(r) } }
        jobs.awaitAll()
    }

    /**
     * Listes de lecture correspondant à une recherche. Wattpad n'a pas de recherche de listes publique fiable :
     * on prend les auteurs des histoires trouvées + les comptes correspondants, et on garde leurs listes dont le nom correspond.
     */
    suspend fun searchLists(q: String, offset: Int): List<ListHit> {
        val first = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).firstOrNull { it.length >= 3 } ?: q.lowercase().trim()
        api(q, offset).filter { first in it.name.lowercase() }.takeIf { it.isNotEmpty() }?.let { return it }
        val all = listCache.getOrPut(q.trim().lowercase()) { viaAuthors(q) }
        return all.drop(offset).take(20)
    }

    private suspend fun viaAuthors(q: String): List<ListHit> = coroutineScope {
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.ifEmpty { listOf(q.lowercase().trim()) }
        val stories = listOf(async { runCatching { searchStories(q, 0) }.getOrDefault(emptyList()) }, async { runCatching { searchStories(q, 20) }.getOrDefault(emptyList()) }).awaitAll().flatten()
        val users = runCatching { searchUsers(q) }.getOrDefault(emptyList()).map { it.id }
        val authors = (users + stories.map { it.author }).filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(30)
        val gate = Semaphore(6)
        authors.map { u -> async { gate.withPermit { runCatching {
            val a = JSONObject(json("$B/api/v3/users/${enc(u)}/lists?limit=100&fields=lists(id,name,numStories,cover,user(name))", "ui")).optJSONArray("lists") ?: JSONArray()
            (0 until a.length()).map { hit(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()) } } }.awaitAll().flatten()
            .map { h -> h to words.count { h.name.lowercase().contains(it) } }
            .filter { it.second > 0 && it.first.count > 0 }
            .sortedWith(compareByDescending<Pair<ListHit, Int>> { it.second }.thenByDescending { it.first.count })
            .map { it.first }.distinctBy { it.id }
    }

    private suspend fun api(q: String, offset: Int): List<ListHit> {
        val e = URLEncoder.encode(q.trim(), "UTF-8")
        for (u in listOf("$B/v4/search/lists/?query=$e&limit=20&offset=$offset&fields=lists(id,name,numStories,user(name),cover)",
                "$B/api/v3/lists?query=$e&limit=20&offset=$offset&fields=lists(id,name,numStories,user(name),cover)")) {
            val a = runCatching { JSONObject(json(u, "ui")).optJSONArray("lists") }.getOrNull() ?: continue
            if (a.length() > 0) return (0 until a.length()).map { hit(a.getJSONObject(it)) }
        }
        return emptyList()
    }

    suspend fun listWorks(id: String): List<Work> = listStories(id).map { work(it).copy(sources = setOf("Recherche")) }

    // ---- Compte Wattpad : listes de lecture (session du navigateur intégré)
    private suspend fun post(url: String, data: Map<String, String>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = Jsoup.connect(url).method(org.jsoup.Connection.Method.POST).data(data).ignoreContentType(true).ignoreHttpErrors(true)
                .userAgent(BROWSER_UA).header("Accept", "application/json").header("X-Requested-With", "XMLHttpRequest")
                .cookies(cookiesFor(B)).timeout(20000).execute()
            r.body().takeIf { r.statusCode() in 200..299 }
        }.getOrNull()
    }
    suspend fun userLists(user: String) = lists(enc(user))
    suspend fun createList(name: String): String? = post("$B/api/v3/lists", mapOf("name" to name))?.let { runCatching { JSONObject(it).get("id").toString() }.getOrNull() }
    suspend fun addToList(listId: String, storyId: String): Boolean = post("$B/api/v3/lists/$listId/stories", mapOf("stories" to storyId)) != null

    private fun acc(o: JSONObject) = Account(o.optString("username"), o.optString("name"), "@" + o.optString("username"), o.optString("avatar"),
        listOfNotNull(o.optInt("numLists", -1).takeIf { it >= 0 }?.let { "$it listes" }, o.optInt("numFollowers", -1).takeIf { it >= 0 }?.let { "$it abonnés" },
            o.optInt("numStoriesPublished", -1).takeIf { it > 0 }?.let { "$it histoires" }).joinToString(" · "))

    /** Cherche récursivement les objets « utilisateur » dans une réponse JSON de forme inconnue. */
    private fun findUsers(v: Any?): List<Account> = when (v) {
        is JSONObject -> if (v.has("username")) listOf(acc(v)) else v.keys().asSequence().toList().flatMap { findUsers(v.opt(it)) }
        is JSONArray -> (0 until v.length()).flatMap { findUsers(v.opt(it)) }
        else -> emptyList()
    }

    /** Recherche de comptes : pseudo exact d'abord, puis recherche du site. */
    override suspend fun searchUsers(q: String): List<Account> {
        val e = URLEncoder.encode(q.trim().removePrefix("@"), "UTF-8")
        val out = LinkedHashMap<String, Account>()
        runCatching { findUsers(JSONTokener(json("$B/api/v3/users/$e?fields=username,name,avatar,numLists,numFollowers,numStoriesPublished", "ui")).nextValue()) }
            .getOrDefault(emptyList()).forEach { out[it.id.lowercase()] = it }
        for (u in listOf("$B/v4/search/users/?query=$e&limit=15&fields=username,name,avatar,numLists,numFollowers,numStoriesPublished", "$B/api/v3/users?query=$e&limit=15")) {
            val r = runCatching { findUsers(JSONTokener(json(u, "ui")).nextValue()) }.getOrDefault(emptyList())
            r.forEach { out.putIfAbsent(it.id.lowercase(), it) }
            if (r.isNotEmpty()) break
        }
        return out.values.filter { it.id.isNotBlank() }.take(10)
    }

    override fun format(user: String) = when {
        user.isBlank() -> "Entre ton pseudo"
        user.trim().contains(' ') -> "Un pseudo Wattpad ne contient pas d'espace"
        else -> null
    }

    override suspend fun verify(user: String): String {
        val o = JSONObject(json("$B/api/v3/users/${enc(user)}?fields=username,name", "ui"))
        val name = o.optString("username").ifEmpty { error("Pseudo « $user » introuvable sur Wattpad") }
        val l = runCatching { lists(enc(user)) }.getOrDefault(emptyList())
        return "Profil trouvé : @$name · ${l.size} liste" + (if (l.size > 1) "s" else "") + " de lecture" +
            (if (l.isNotEmpty()) " (" + l.take(3).joinToString { it.second } + (if (l.size > 3) "…" else "") + ")" else "")
    }
}

object Ffn : Site {
    override val searchable = true

    /** Recherche d'utilisateurs via la recherche « writer » du site. */
    override suspend fun searchUsers(q: String): List<Account> {
        val t = q.trim()
        Regex("/u/(\\d+)").find(t)?.groupValues?.get(1)?.let { id -> return listOf(Account("$B/u/$id", "Profil n°$id", "Lien collé", "", "")) }
        val d = Web.html("$B/search/?ready=1&type=writer&keywords=${URLEncoder.encode(t, "UTF-8")}", "ui")
        val root = d.selectFirst("#content_wrapper_inner") ?: d.body()
        return root.select("a[href^=/u/]").mapNotNull { a ->
            val id = Regex("/u/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val name = a.text().trim().ifEmpty { return@mapNotNull null }
            val info = a.parent()?.ownText()?.trim()?.take(80) ?: ""
            Account("$B/u/$id", name, "fanfiction.net/u/$id", "", info)
        }.distinctBy { it.id }.take(12)
    }

    override fun format(user: String) = if (Regex("\\d{3,}").containsMatchIn(user)) null else "Colle le lien de ton profil, ex. fanfiction.net/u/1234567"

    override suspend fun verify(user: String): String {
        val uid = Regex("(\\d{3,})").find(user)?.value ?: error("Numéro de profil introuvable dans le lien")
        val d = Web.html("$M/u/$uid/?a=fs", "ui")
        val txt = d.text()
        if (d.title().contains("not exist", true) || txt.contains("User does not exist", true) || txt.contains("User is no longer an active member", true))
            error("Profil $uid introuvable sur FanFiction.net")
        val name = d.title().substringBefore('|').trim().takeIf { it.isNotEmpty() && !it.contains("FanFiction", true) } ?: "n°$uid"
        return "Profil trouvé : $name · ${d.select("a[href^=/s/]").map { it.attr("href").substringBefore("/", "").ifEmpty { it.attr("href") } }.distinct().size.coerceAtLeast(d.select("div.favstories").size)} histoires favorites sur la 1re page"
    }

    override val name = "FanFiction.net"
    override val home = "https://www.fanfiction.net/"
    override val userHint = "Lien ou numéro de ton profil (fanfiction.net/u/…)"
    private const val B = "https://www.fanfiction.net"
    override fun owns(url: String) = "fanfiction.net" in url
    private const val M = "https://m.fanfiction.net"
    private val IC = RegexOption.IGNORE_CASE
    private fun date(sec: String) = sec.toLongOrNull()?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000)) } ?: ""

    /** « words: 45k+ » → 45000 ; « words: 1,234 » → 1234. */
    private fun words(t: String): Int {
        val m = Regex("words?:\\s*([\\d.,]+)\\s*(k|m)?", IC).find(t) ?: return 0
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (n * when (m.groupValues[2].lowercase()) { "k" -> 1_000; "m" -> 1_000_000; else -> 1 }).toInt()
    }
    private fun chaptersOf(t: String) = Regex("chapters?:\\s*(\\d+)", IC).find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    private fun favsOf(t: String) = Regex("favs?:\\s*([\\d,]+)", IC).find(t)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
    private fun completeOf(t: String) = Regex("\\bcomplete\\b", IC).containsMatchIn(t)
    private fun ratedOf(t: String) = Regex("rated:\\s*(?:fiction\\s*)?([KTM]\\+?)", IC).find(t)?.groupValues?.get(1)
        ?: Regex(",\\s*([KTM]\\+?)\\s*,").find(t)?.groupValues?.get(1) ?: ""

    private fun work(id: String, title: String, author: String, fandom: String, meta: String, summary: String = "", cover: String = "") : Work {
        val n = chaptersOf(meta); val done = completeOf(meta)
        return Work("ffn:$id", title.ifBlank { "Histoire $id" }, author, listOf(fandom.ifBlank { name }), ratedOf(meta), words(meta),
            "$n/${if (done) n else "?"}", favsOf(meta), summary, emptyList(), Regex("updated:\\s*([^,]+)", IC).find(meta)?.groupValues?.get(1)?.trim() ?: "",
            done, setOf("Favori"), site = name, url = "$B/s/$id", cover = cover, checked = System.currentTimeMillis())
    }
    private fun img(e: org.jsoup.nodes.Element?): String = e?.let { it.absUrl("data-original").ifBlank { it.absUrl("src") } }?.replace("/75/", "/180/")?.takeIf { it.startsWith("http") && Regex("/image/\\d+/").containsMatchIn(it) } ?: ""
    private fun metaDesc(d: Document) = (d.selectFirst("meta[name=description]")?.attr("content") ?: d.selectFirst("meta[property=og:description]")?.attr("content") ?: "").trim()

    override suspend fun info(url: String): Work? {
        val id = Regex("/s/(\\d+)").find(url)?.groupValues?.get(1) ?: return null
        val d = Web.html("$M/s/$id/1/")
        val text = d.text()
        if (!Regex("chapters?:|words?:|rated:", IC).containsMatchIn(text)) error("page FanFiction.net illisible (vérification anti-robot ?)")
        // Titre de page : « Titre Chapter 1, a Naruto fanfic | FanFiction »
        val pageTitle = d.title()
        val title = d.selectFirst("#profile_top b.xcontrast_txt, #content b")?.text()?.ifBlank { null }
            ?: pageTitle.substringBefore(" | ").substringBefore(" Chapter ").substringBefore(", a ").trim()
        val fandom = d.select("#pre_story_links a").lastOrNull()?.text() ?: Regex(", an? (.+?) fanfic", IC).find(pageTitle)?.groupValues?.get(1) ?: name
        val metaEl = d.select("span.xgray, div.gray, .gray, div").firstOrNull { Regex("words?:", IC).containsMatchIn(it.ownText()) || Regex("words?:", IC).containsMatchIn(it.text()) && it.text().length < 600 }
        return work(id, title, d.selectFirst("a[href^=/u/]")?.text() ?: "", fandom, metaEl?.text() ?: text.take(2000),
            d.selectFirst("#profile_top div.xcontrast_txt")?.text()?.ifBlank { null } ?: metaDesc(d),
            img(d.selectFirst("#profile_top img.cimage, img.cimage")).ifBlank { d.selectFirst("meta[property=og:image]")?.attr("content") ?: "" })
    }

    override suspend fun download(w: Work): Pair<List<String>, String> {
        val id = w.id.removePrefix("ffn:")
        val first = Web.html("$M/s/$id/1/")
        val n = maxOf(w.published, chaptersOf(first.text()))
        val names = first.selectFirst("select#chap_select")?.select("option")?.map { it.text() } ?: emptyList()
        return sections((1..n).map { i ->
            val d = if (i == 1) first else Web.html("$M/s/$id/$i/")
            (names.getOrNull(i - 1) ?: "Chapitre $i") to (d.selectFirst("#storycontent, #storytext, .storycontent, .storytext")?.html() ?: mainText(d))
        })
    }

    /** Recherche d'histoires sur le site mobile. */
    suspend fun searchStories(q: String, page: Int): List<Work> {
        val d = Web.html("$M/search/?ready=1&type=story&keywords=${URLEncoder.encode(q.trim(), "UTF-8")}&ppage=$page", "ui")
        val out = LinkedHashMap<String, Work>()
        d.select("a[href^=/s/]").forEach { a ->
            val id = Regex("^/s/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@forEach
            if (id in out || a.text().isBlank()) return@forEach
            val box = generateSequence(a.parent()) { it.parent() }.firstOrNull { it.text().length > a.text().length + 20 || it.tagName() == "div" } ?: return@forEach
            val meta = box.selectFirst(".gray, .xgray, .z-padtop2")?.text() ?: box.text()
            val fandom = meta.substringBefore(",").takeIf { it.length in 2..60 && !it.contains(":") } ?: ""
            out[id] = work(id, a.text(), box.selectFirst("a[href^=/u/]")?.text() ?: "", fandom, meta,
                box.selectFirst(".z-indent, .z-padtop")?.ownText() ?: "", img(box.selectFirst("img"))).copy(sources = setOf("Recherche"))
        }
        return out.values.toList()
    }

    /** Favoris : page mobile « ?a=fs » (le navigateur intégré y est redirigé), paginée ; repli sur la version ordinateur. */
    override suspend fun library(user: String, known: Map<String, Work>): List<Work> {
        val uid = Regex("(\\d{3,})").find(user)?.value ?: return emptyList()
        val out = LinkedHashMap<String, Work>()
        for (p in 1..40) {
            val d = Web.html("$M/u/$uid/?a=fs&p=$p")
            var added = 0
            d.select("a[href^=/s/]").forEach { a ->
                val id = Regex("^/s/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return@forEach
                if (id in out || a.text().isBlank()) return@forEach
                val box = generateSequence(a.parent()) { it.parent() }.firstOrNull { it.text().length > a.text().length + 20 || it.tagName() == "div" } ?: a.parent() ?: return@forEach
                val meta = box.selectFirst(".gray, .xgray, .z-padtop2")?.text() ?: box.text()
                val fandom = meta.substringBefore(",").takeIf { it.length in 2..60 && !it.contains(":") } ?: ""
                out[id] = work(id, a.text(), box.selectFirst("a[href^=/u/]")?.text() ?: "", fandom, meta,
                    box.selectFirst(".z-indent, .z-padtop")?.ownText() ?: "", img(box.selectFirst("img")))
                added++
            }
            if (added == 0) break
        }
        if (out.isEmpty()) {
            // Repli : version ordinateur (blocs « favstories » avec attributs data-*)
            Web.html("$B/u/$uid/").select("div.favstories").forEach { e ->
                val id = e.attr("data-storyid").ifEmpty { return@forEach }
                val n = e.attr("data-chapters").toIntOrNull() ?: 1; val done = e.attr("data-statusid") == "2"
                out[id] = Work("ffn:$id", e.attr("data-title"), e.select("a[href^=/u/]").firstOrNull()?.text() ?: "", listOf(e.attr("data-category").ifEmpty { name }), "",
                    e.attr("data-wordcount").toIntOrNull() ?: 0, "$n/${if (done) n else "?"}", 0, e.selectFirst("div.z-indent")?.ownText() ?: "", emptyList(),
                    date(e.attr("data-dateupdate")), done, setOf("Favori"), site = name, url = "$B/s/$id")
            }
        }
        if (out.isEmpty()) error("aucune histoire favorite trouvée sur le profil $uid (vérification anti-robot ? ouvre le site une fois)")
        return out.values.toList()
    }
}

object Spirit : Site {
    override fun format(user: String) = when {
        user.isBlank() -> "Entre ton pseudo"
        user.trim().contains(' ') -> "Un pseudo Spirit ne contient pas d'espace"
        else -> null
    }

    override suspend fun verify(user: String): String {
        val d = Web.html("$B/perfil/${URLEncoder.encode(user.trim(), "UTF-8")}", "ui")
        val t = d.title()
        if (t.contains("404") || t.contains("encontrad", true) || d.selectFirst("h1") == null) error("Pseudo « $user » introuvable sur Spirit")
        return "Profil trouvé : ${d.selectFirst("h1")?.text() ?: user}"
    }

    override val name = "Spirit Fanfics"
    override val home = "https://www.spiritfanfiction.com/login"
    override val userHint = "Ton pseudo Spirit"
    private const val B = "https://www.spiritfanfiction.com"
    override fun owns(url: String) = "spiritfanfics.com" in url || "spiritfanfiction.com" in url
    private fun slug(url: String) = Regex("/historia/([a-z0-9-]+-\\d+)").find(url)?.groupValues?.get(1)
    private fun chapters(d: Document, s: String) =
        d.select("a[href*=/historia/$s/]").filter { Regex("/historia/$s/[^/?#]+").containsMatchIn(it.attr("href")) && it.text().isNotBlank() }
            .map { it.absUrl("href").substringBefore('#') to it.text() }.distinctBy { it.first }

    /**
     * Recherche (page « busca ») : chaque résultat donne titre, « escrita por » auteur, statut,
     * « Capítulos N », « Palavras N », « Categorias … », résumé et image.
     */
    /** Formulaire de recherche du site (catégorie, personnages, genres, tri…), lu sur la page « busca ». */
    suspend fun searchForm(): List<FormField> {
        val d = Web.html("$B/busca?query=a", "ui")
        return d.select("select[name]").mapNotNull { s ->
            val nm = s.attr("name").ifBlank { return@mapNotNull null }
            val opts = s.select("option").map { it.attr("value") to it.text().trim() }.filter { it.second.isNotBlank() }
            if (opts.size < 2) return@mapNotNull null
            val label = s.id().takeIf { it.isNotBlank() }?.let { d.selectFirst("label[for=$it]")?.text() }
                ?: generateSequence(s.previousElementSibling()) { it.previousElementSibling() }.firstOrNull { it.text().isNotBlank() }?.text()?.takeIf { it.length < 40 } ?: nm
            FormField(nm, label.trim(), opts)
        }.distinctBy { it.name }
    }

    suspend fun searchStories(q: String, page: Int, filters: Map<String, String> = emptyMap()): List<Work> {
        val extra = filters.filterValues { it.isNotBlank() }.entries.joinToString("") { "&" + URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val d = Web.html("$B/busca?query=${URLEncoder.encode(q.trim(), "UTF-8")}&pagina=$page$extra", "ui")
        val ic = RegexOption.IGNORE_CASE
        val out = LinkedHashMap<String, Work>()
        d.select("a[href*=/historia/]").forEach { a ->
            val sl = slug(a.attr("href")) ?: return@forEach
            if (Regex("/historia/$sl/").containsMatchIn(a.attr("href")) || a.text().isBlank() || "sp:$sl" in out) return@forEach
            val box = generateSequence(a.parent()) { it.parent() }.firstOrNull { e ->
                e.text().contains("escrita por", true) && e.select("a[href*=/historia/]").mapNotNull { slug(it.attr("href")) }.distinct().size <= 1
            } ?: a.parent() ?: return@forEach
            val t = box.text()
            val n = Regex("Cap[ií]tulos\\s+(\\d+)", ic).find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val done = Regex("Conclu[ií]d[ao]", ic).containsMatchIn(t)
            val cat = Regex("Categorias\\s+(.+?)(?:\\s+G[êe]neros|\\s+Personagens|$)", ic).find(t)?.groupValues?.get(1)?.trim()?.take(80) ?: name
            val summary = box.select("p, div").filter { it.children().isEmpty() || it.tagName() == "p" }.map { it.text() }
                .filter { it.length > 60 && !it.contains("escrita por", true) && !it.startsWith("Categorias") && !it.startsWith("Gêneros") }.maxByOrNull { it.length } ?: ""
            val im = box.selectFirst("img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } } ?: ""
            out["sp:$sl"] = Work("sp:$sl", a.text(), Regex("escrita por\\s+(\\S+)", ic).find(t)?.groupValues?.get(1) ?: "", listOf(cat.substringBefore(",").trim()), "",
                digits(Regex("Palavras\\s+([\\d.]+)", ic).find(t)?.groupValues?.get(1)), "$n/${if (done) n else "?"}",
                0, summary, emptyList(), Regex("Atualizada\\s+(.+?)\\s+Idioma", ic).find(t)?.groupValues?.get(1) ?: "", done, setOf("Recherche"),
                site = name, url = "$B/historia/$sl", cover = im.takeIf { it.startsWith("http") } ?: "", checked = System.currentTimeMillis())
        }
        return out.values.toList()
    }

    private fun pretty(sl: String) = sl.substringBeforeLast('-').replace('-', ' ').replaceFirstChar { it.uppercase() }

    override suspend fun info(url: String): Work? {
        val s = slug(url) ?: return null
        val d = Web.html("$B/historia/$s")
        val body = d.text()
        val ic = RegexOption.IGNORE_CASE
        val og = d.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val head = d.title() + " " + og
        // « Titre - História escrita por Auteur - Spirit Fanfics e Histórias »
        val raw = d.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: og.ifBlank { d.title() }
        val title = raw.split(Regex(" - Hist[óo]ria| - Spirit| \\| ", ic))[0].trim().takeIf { it.isNotBlank() && it != s } ?: pretty(s)
        val author = Regex("escrita por ([^-|]+)", ic).find(head)?.groupValues?.get(1)?.trim()
            ?: d.selectFirst("[itemprop=author], .autor a, a[rel=author]")?.text()?.trim() ?: ""
        val n = maxOf(chapters(d, s).size, Regex("(\\d+)\\s+cap[ií]tulos?", ic).find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtLeast(1)
        val done = Regex("Conclu[ií]da|Terminada|Completa", ic).containsMatchIn(body)
        return Work("sp:$s", title, author, listOf(d.selectFirst("a[href*=/categorias/]")?.text() ?: name), "",
            digits(Regex("([\\d.]+)\\s+palavras", ic).find(body)?.groupValues?.get(1)),
            "$n/${if (done) n else "?"}", 0, (d.selectFirst("meta[name=description]")?.attr("content") ?: d.selectFirst("meta[property=og:description]")?.attr("content") ?: "").trim(),
            emptyList(), "", done, setOf("Favori"), site = name, url = "$B/historia/$s",
            cover = d.selectFirst("meta[property=og:image]")?.attr("content") ?: "", checked = System.currentTimeMillis())
    }

    override suspend fun download(w: Work): Pair<List<String>, String> {
        val s = w.id.removePrefix("sp:")
        val chaps = chapters(Web.html("$B/historia/$s"), s).ifEmpty { listOf("$B/historia/$s" to w.title) }
        return sections(chaps.map { (u, t) -> t to mainText(Web.html(u)) })
    }

    override suspend fun library(user: String, known: Map<String, Work>): List<Work> {
        val u = URLEncoder.encode(user.trim(), "UTF-8")
        val ic = RegexOption.IGNORE_CASE
        val titles = LinkedHashMap<String, String>()          // slug -> titre
        val sources = LinkedHashMap<String, MutableSet<String>>() // slug -> collections
        val covers = HashMap<String, String>()
        fun addStories(d: Document, src: String) {
            d.select("a[href*=/historia/]").forEach { a ->
                val sl = slug(a.attr("href")) ?: return@forEach
                if (Regex("/historia/$sl/").containsMatchIn(a.attr("href"))) return@forEach // lien de chapitre
                if (titles[sl].isNullOrBlank() && a.text().isNotBlank()) titles[sl] = a.text() else titles.putIfAbsent(sl, "")
                sources.getOrPut(sl) { mutableSetOf() } += src
                val im = (a.selectFirst("img") ?: a.parent()?.selectFirst("img"))?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
                if (!im.isNullOrBlank() && im.startsWith("http") && sl !in covers) covers[sl] = im
            }
        }
        // Favoris et bibliothèque
        for ((path, src) in listOf("/perfil/$u/favoritos" to "Favori", "/perfil/$u/biblioteca" to "Bibliothèque", "/biblioteca" to "Bibliothèque")) {
            runCatching { Web.html(B + path) }.getOrNull()?.let { addStories(it, src) }
        }
        // Listes de lecture : liens « lista » du profil et de la page « Veja todas as Listas de leitura »
        val lists = LinkedHashMap<String, String>() // url -> nom
        fun collectLists(d: Document) = d.select("a[href*=lista]").forEach { a ->
            val name = a.text().replace(Regex("\\s*\\d+\\s+hist[óo]rias?.*$", ic), "").trim()
            if (name.isBlank() || name.startsWith("Veja", true) || name.contains("Listas de leitura", true)) return@forEach
            lists.putIfAbsent(a.absUrl("href").substringBefore('#'), name)
        }
        for (path in listOf("/perfil/$u", "/perfil/$u/listas")) {
            val d = runCatching { Web.html(B + path) }.getOrNull() ?: continue
            collectLists(d)
            d.select("a").firstOrNull { it.text().contains("Listas de leitura", true) && it.text().startsWith("Veja", true) }?.absUrl("href")
                ?.let { all -> runCatching { Web.html(all) }.getOrNull()?.let { collectLists(it) } }
        }
        lists.entries.take(40).forEach { (href, name) ->
            var url: String? = href; var n = 0
            while (url != null && n++ < 10) {
                val d = runCatching { Web.html(url!!) }.getOrNull() ?: break
                addStories(d, "Liste : $name")
                val next = d.select("a[rel=next], a").firstOrNull { it.attr("rel") == "next" || Regex("^pr[óo]xim", ic).containsMatchIn(it.text()) }?.absUrl("href")
                url = next?.takeIf { it.isNotBlank() && it != url }
            }
        }
        if (titles.isEmpty()) error("aucune histoire trouvée (favoris, bibliothèque et listes vides ou privés ; connecte-toi à l'étape 1)")
        return titles.map { (sl, t) ->
            val src = sources[sl] ?: mutableSetOf("Favori")
            known["sp:$sl"]?.let { k -> k.copy(sources = src, cover = k.cover.ifEmpty { covers[sl] ?: "" }) }
                ?: Work("sp:$sl", t.ifBlank { sl.substringBeforeLast('-').replace('-', ' ') }, "", listOf(name), "", 0, "?/?", 0, "", emptyList(), "", false, src,
                    site = name, url = "$B/historia/$sl", cover = covers[sl] ?: "")
        }
    }
}
