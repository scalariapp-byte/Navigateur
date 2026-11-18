package app.fanfichub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Traduction en français : Google Traduction (en ligne, meilleure qualité) par lots de paragraphes ;
 * (nécessite Internet).
 */
object Translator {
    /** Google limite les rafales (429) : on réessaie en patientant, en alternant deux points d'accès. */
    @Volatile private var useDict = false // mémorise le point d'accès qui répond
    private suspend fun google(text: String): String {
        var last: Exception? = null
        for (attempt in 0..4) {
            try { return (if ((attempt % 2 == 0) != useDict) gtx(text) else dict(text)) }
            catch (e: Exception) { last = e; useDict = !useDict; kotlinx.coroutines.delay(400L shl attempt) }
        }
        throw last!!
    }

    private suspend fun dict(text: String): String = withContext(Dispatchers.IO) {
        val c = URL("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=fr").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        c.setRequestProperty("User-Agent", BROWSER_UA)
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) error("Google Traduction a répondu ${c.responseCode}")
        val a = JSONArray(c.inputStream.bufferedReader().readText())
        a.opt(0).let { if (it is JSONArray) it.optString(0) else a.optString(0) }
    }

    private suspend fun gtx(text: String): String = withContext(Dispatchers.IO) {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=fr&dt=t").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        c.setRequestProperty("User-Agent", BROWSER_UA)
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) error("Google Traduction a répondu ${c.responseCode}")
        val a = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
        (0 until a.length()).joinToString("") { a.getJSONArray(it).optString(0) }
    }

    private suspend fun viaGoogle(blocks: List<Element>, progress: (Float) -> Unit) {
        val res = texts(blocks.map { it.text() }, progress)
        blocks.forEachIndexed { i, e -> e.text(res[i]).attr("data-fr", "1") }
    }

    /** Traduit une liste de textes ; lots de ~4 500 caractères envoyés 4 par 4 en parallèle ; cache mémoire. */
    suspend fun texts(items: List<String>, progress: (Float) -> Unit = {}, onPart: (List<Int>, List<String>) -> Unit = { _, _ -> }): List<String> = coroutineScope {
        val src = items.map { it.replace(Regex("\\s+"), " ").trim() }
        val out = src.map<String, String?> { if (it.isEmpty()) it else null }.toMutableList()
        val todo = src.indices.filter { out[it] == null }
        val groups = mutableListOf<List<Int>>(); var cur = mutableListOf<Int>(); var len = 0
        for (i in todo) {
            if (cur.isNotEmpty() && len + src[i].length > (if (groups.isEmpty()) 1200 else 4500)) { groups += cur; cur = mutableListOf(); len = 0 }
            cur += i; len += src[i].length + 2
        }
        if (cur.isNotEmpty()) groups += cur
        val sem = Semaphore(2); var done = 0; var failed = 0
        groups.map { g ->
            async {
                sem.withPermit {
                    // Un morceau refusé par Google reste en VO : le reste de la page est quand même traduit
                    runCatching {
                        val res = google(g.joinToString("\n\n") { src[it] }).split(Regex("\n\\s*\n"))
                        if (res.size == g.size) g.forEachIndexed { k, i -> out[i] = res[k].trim() }
                        else g.forEach { i -> out[i] = runCatching { google(src[i]).trim() }.getOrDefault(src[i]); kotlinx.coroutines.delay(150) }
                    }.onFailure { failed++ }
                    onPart(g, g.map { out[it] ?: src[it] }) // affichage au fil de l'eau
                    done += g.size; progress(done.toFloat() / maxOf(1, todo.size))
                }
            }
        }.awaitAll()
        if (groups.isNotEmpty() && failed == groups.size) error("Google refuse pour le moment")
        src.indices.map { out[it] ?: src[it] }
    }

    suspend fun toFrench(html: String, progress: (Float) -> Unit): String {
        val doc = Jsoup.parseBodyFragment(html)
        var blocks = doc.select("p, h2, h3, h4, li, blockquote").filter { it.text().isNotBlank() && it.select("p, li").none { c -> c !== it } }
        if (blocks.isEmpty()) blocks = doc.select("section").filter { it.text().isNotBlank() }
        if (blocks.isEmpty()) error("aucun texte à traduire")
        try { viaGoogle(blocks, progress) } catch (e: Exception) { error("connexion à Google Traduction impossible (${e.message})") }
        progress(1f)
        doc.select("[data-fr]").removeAttr("data-fr")
        return doc.body().html()
    }
}
