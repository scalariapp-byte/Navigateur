package app.fanfichub

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

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
