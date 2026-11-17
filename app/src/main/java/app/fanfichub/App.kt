package app.fanfichub

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Login : Screen
    data class Detail(val id: String) : Screen
    data class Reader(val id: String) : Screen
}

class Hub(ctx: Context) {
    private val store = Store(ctx.filesDir)
    private val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    val works = mutableStateListOf<Work>().apply { addAll(store.load()) }
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableIntStateOf(0)
    var user by mutableStateOf(prefs.getString("user", null)); private set
    var syncing by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null)
    var theme by mutableIntStateOf(prefs.getInt("theme", 0)); private set
    var size by mutableIntStateOf(prefs.getInt("size", 19)); private set

    fun push(s: Screen) { stack += s }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun work(id: String) = works.firstOrNull { it.id == id }
    fun update(w: Work) { val i = works.indexOfFirst { it.id == w.id }; if (i >= 0) works[i] = w else works += w; store.save(works) }
    fun saveUser(u: String?) { user = u?.trim()?.ifEmpty { null }; prefs.edit().putString("user", user).apply() }
    fun setReader(t: Int = theme, s: Int = size) { theme = t; size = s.coerceIn(14, 30); prefs.edit().putInt("theme", theme).putInt("size", size).apply() }
    fun logout() { CookieManager.getInstance().removeAllCookies(null); saveUser(null) }
    fun hasText(id: String) = store.text(id).exists()
    fun text(id: String) = store.text(id).readText()
    fun titles(id: String) = store.titles(id).takeIf { it.exists() }?.readLines() ?: emptyList()

    suspend fun detectUser() { withContext(Dispatchers.IO) { Ao3.username() }?.let { saveUser(it) } }

    suspend fun download(id: String) {
        val (t, html) = withContext(Dispatchers.IO) { Ao3.download(id) }
        store.text(id).writeText(html); store.titles(id).writeText(t.joinToString("\n"))
        work(id)?.let { update(it.copy(newChapters = 0)) }
    }

    /** Synchro incrémentale : s'arrête dès que les pages ne contiennent plus que des fanfictions connues et inchangées. */
    suspend fun sync(full: Boolean = false) {
        val u = user ?: run { status = "Connecte ton compte AO3 pour synchroniser"; return }
        if (syncing) return
        syncing = true
        val old = works.associateBy { it.id }
        val seen = LinkedHashMap<String, Work>()
        fun add(w: Work) { seen[w.id] = seen[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        fun unchanged(w: Work) = old[w.id]?.let { it.chapters == w.chapters && it.updated == w.updated } == true
        fun publish() {
            val merged = LinkedHashMap<String, Work>()
            if (!full) old.values.forEach { merged[it.id] = it }
            seen.values.forEach { f ->
                val o = old[f.id]
                merged[f.id] = if (o == null) f else f.copy(newChapters = o.newChapters + maxOf(0, f.published - o.published),
                    progress = o.progress, lastRead = o.lastRead, sources = if (full) f.sources else o.sources + f.sources)
            }
            works.clear(); works.addAll(merged.values); store.save(works)
        }
        suspend fun pages(start: String, src: String, max: Int, stop: (List<Work>) -> Boolean) {
            var url: String? = start; var n = 0
            while (url != null && n++ < max) {
                val (list, next) = withContext(Dispatchers.IO) { Ao3.page(url!!, src) }
                list.forEach { add(it) }; publish()
                status = "$src… ${seen.size} fanfictions"
                if (!full && stop(list)) break
                url = next
            }
        }
        try {
            // 1. Favoris triés par date de mise à jour de l'œuvre : tout ce qui suit une œuvre inchangée est inchangé.
            pages("/users/$u/bookmarks?bookmark_search%5Bsort_column%5D=bookmarkable_date", "Favori", 40) { l -> l.any { unchanged(it) } }
            // 2. Nouveaux favoris (tri par date d'ajout) jusqu'au premier déjà connu.
            if (!full) pages("/users/$u/bookmarks", "Favori", 10) { l -> l.any { it.id in old } }
            // 3. À lire plus tard.
            runCatching { pages("/users/$u/readings?show=to-read", "À lire", if (full) 20 else 3) { l -> l.all { unchanged(it) } } }
            // 4. Abonnements : on ne revérifie pas les fanfictions terminées.
            val ids = mutableListOf<String>(); var url: String? = "/users/$u/subscriptions?type=works"; var n = 0
            while (url != null && n++ < 15) { val (l, next) = withContext(Dispatchers.IO) { Ao3.subsPage(url!!) }; ids += l; url = next }
            ids.distinct().forEachIndexed { i, id ->
                val known = seen[id] ?: old[id]
                when {
                    seen[id] != null -> add(seen[id]!!.copy(sources = setOf("Abonnement")))
                    known != null && known.complete && !full -> add(known.copy(sources = known.sources + "Abonnement"))
                    else -> withContext(Dispatchers.IO) { Ao3.work(id, "Abonnement") }?.let { add(it) }
                }
                status = "Abonnements ${i + 1}/${ids.distinct().size}"
                if (i % 5 == 4) publish()
            }
            publish()
            prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            val nw = works.count { it.newChapters > 0 }
            status = "${works.size} fanfictions · " + if (nw == 0) "tout est à jour" else "$nw avec de nouveaux chapitres"
        } catch (e: Exception) {
            publish()
            status = "Synchro interrompue : ${e.message ?: "réseau indisponible"}"
        } finally { syncing = false }
    }

    fun syncDue() = user != null && System.currentTimeMillis() - prefs.getLong("lastSync", 0) > 20 * 60_000
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        val hub = Hub(this)
        setContent { HubApp(hub) }
    }
}
