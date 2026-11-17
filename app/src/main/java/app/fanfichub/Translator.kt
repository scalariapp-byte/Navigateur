package app.fanfichub

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Traduction Google ML Kit, sur l'appareil (le modèle de langue est téléchargé une fois). */
object Translator {
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    suspend fun toFrench(html: String, progress: (Float) -> Unit): String {
        val doc = Jsoup.parseBodyFragment(html)
        // Blocs de texte « feuilles » (paragraphes, titres…), sinon chaque nœud de texte.
        val blocks = doc.select("p, h2, h3, h4, li, blockquote").filter { it.text().isNotBlank() && it.select("p, li").none { c -> c !== it } }
        val sample = (if (blocks.isNotEmpty()) blocks.take(25).joinToString(" ") { it.text() } else doc.text()).take(3000)
        val lang = LanguageIdentification.getClient().identifyLanguage(sample).await().let { if (it == "und") "en" else it }
        if (lang == "fr") error("le texte est déjà en français")
        val src = TranslateLanguage.fromLanguageTag(lang) ?: error("langue « $lang » non prise en charge")
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.FRENCH).build())
        try {
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            if (blocks.isNotEmpty()) {
                blocks.forEachIndexed { i, e ->
                    e.text(tr.translate(e.text()).await())
                    if (i % 5 == 0) progress(i.toFloat() / blocks.size)
                }
            } else {
                val nodes = doc.body().select("*").flatMap { it.textNodes() }.filter { it.text().trim().length > 1 }
                nodes.forEachIndexed { i, n: TextNode ->
                    n.text(tr.translate(n.text()).await())
                    if (i % 5 == 0) progress(i.toFloat() / nodes.size)
                }
            }
        } finally { tr.close() }
        progress(1f)
        return doc.body().html()
    }
}
