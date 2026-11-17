package app.fanfichub

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Traduction en français : Google Traduction (en ligne, meilleure qualité) par lots de paragraphes ;
 * si le service refuse, bascule sur Google ML Kit (sur l'appareil).
 */
object Translator {
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    private suspend fun google(text: String): String = withContext(Dispatchers.IO) {
        val c = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=fr&dt=t").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        c.setRequestProperty("User-Agent", BROWSER_UA)
        c.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) error("Google Traduction a répondu ${c.responseCode}")
        val a = JSONArray(c.inputStream.bufferedReader().readText()).getJSONArray(0)
        (0 until a.length()).joinToString("") { a.getJSONArray(it).optString(0) }
    }

    /** Groupes de paragraphes (< 4 500 caractères) séparés par une ligne vide. */
    private suspend fun viaGoogle(blocks: List<Element>, progress: (Float) -> Unit) {
        var i = 0
        while (i < blocks.size) {
            var j = i; var len = 0
            while (j < blocks.size && (j == i || len + blocks[j].text().length < 4500)) { len += blocks[j].text().length + 2; j++ }
            val group = blocks.subList(i, j)
            val out = google(group.joinToString("\n\n") { it.text() }).split(Regex("\n\\s*\n"))
            if (out.size == group.size) group.forEachIndexed { k, e -> e.text(out[k].trim()).attr("data-fr", "1") }
            else group.forEach { e -> e.text(google(e.text())).attr("data-fr", "1") } // découpage différent : un par un
            i = j
            progress(i.toFloat() / blocks.size)
            delay(150)
        }
    }

    private suspend fun viaMlKit(blocks: List<Element>, sample: String, progress: (Float) -> Unit) {
        val lang = LanguageIdentification.getClient().identifyLanguage(sample).await().let { if (it == "und") "en" else it }
        if (lang == "fr") error("le texte est déjà en français")
        val src = TranslateLanguage.fromLanguageTag(lang) ?: error("langue « $lang » non prise en charge")
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.FRENCH).build())
        try {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            blocks.forEachIndexed { i, e ->
                if (e.attr("data-fr") != "1") e.text(tr.translate(e.text()).await())
                if (i % 5 == 0) progress(i.toFloat() / blocks.size)
            }
        } finally { tr.close() }
    }

    suspend fun toFrench(html: String, progress: (Float) -> Unit): String {
        val doc = Jsoup.parseBodyFragment(html)
        var blocks = doc.select("p, h2, h3, h4, li, blockquote").filter { it.text().isNotBlank() && it.select("p, li").none { c -> c !== it } }
        if (blocks.isEmpty()) blocks = doc.select("section").filter { it.text().isNotBlank() }
        if (blocks.isEmpty()) error("aucun texte à traduire")
        val sample = blocks.take(25).joinToString(" ") { it.text() }.take(3000)
        try {
            viaGoogle(blocks, progress)
        } catch (e: Exception) {
            // Repli hors ligne ; l'erreur d'origine est gardée si le repli échoue aussi.
            try { viaMlKit(blocks, sample, progress) } catch (e2: Exception) { error("${e.message} ; traducteur hors ligne : ${e2.message ?: e2.javaClass.simpleName}") }
        }
        progress(1f)
        doc.select("[data-fr]").removeAttr("data-fr")
        return doc.body().html()
    }
}
