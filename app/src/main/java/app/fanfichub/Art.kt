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

    private suspend fun media(name: String): String? {
        val key = "m:" + name.lowercase()
        prefs.getString(key, null)?.let { return it.ifBlank { null } }
        val url = gql("query(\$s:String){Media(search:\$s,type:ANIME){coverImage{extraLarge}}}", name)
            ?.optJSONObject("Media")?.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
        prefs.edit().putString(key, url).apply(); return url.ifBlank { null }
    }

    /** Image pour une fiche : personnage (tags en tête = personnages sur AO3), sinon l'œuvre du fandom. */
    suspend fun forWork(w: Work): String? {
        for (t in w.tags.take(3)) {
            if (t.length > 40 || Regex("[/&:]").containsMatchIn(t)) continue
            character(t.substringBefore(" (").trim())?.let { return it }
        }
        val f = fandomKey(w).removePrefix("Crossover : ").split(" × ").firstOrNull()?.trim().orEmpty()
        return if (f.isBlank() || f == "Sans fandom") null else media(f)
    }
}
