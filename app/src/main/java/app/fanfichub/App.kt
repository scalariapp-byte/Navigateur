package app.fanfichub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Login(val site: String) : Screen
    data class Detail(val id: String) : Screen
    data class Reader(val id: String) : Screen
}

class Hub(ctx: Context) {
    private val app = ctx.applicationContext
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
    val users = mutableStateMapOf<String, String>().apply { Sites.all.forEach { s -> prefs.getString("user_${s.name}", null)?.let { put(s.name, it) } } }
    var pendingImport by mutableStateOf<String?>(null)
    var newVersion by mutableStateOf<Long?>(null); private set
    var updating by mutableStateOf<Float?>(null); private set
    val version = Updater.current(ctx)

    fun saveSiteUser(site: String, u: String?) {
        val v = u?.trim()?.ifEmpty { null }
        if (v == null) users.remove(site) else users[site] = v
        prefs.edit().putString("user_$site", v).apply()
    }

    suspend fun checkUpdate(manual: Boolean = false) {
        val l = Updater.latest()
        newVersion = l?.takeIf { it > version }
        if (manual) status = if (l == null) "Impossible de vérifier les mises à jour" else if (newVersion == null) "L'app est à jour (version $version)" else "Version $l disponible"
    }

    suspend fun doUpdate() {
        if (updating != null) return
        updating = 0f
        try { Updater.install(app, Updater.download(app) { updating = it }) } catch (e: Exception) { status = "Mise à jour échouée : ${e.message}" }
        updating = null
    }

    /** Ajoute une fanfiction à partir d'un lien (partage depuis Chrome ou collage). */
    suspend fun importUrl(raw: String) {
        val url = Regex("https?://\\S+").find(raw)?.value ?: run { status = "Aucun lien trouvé"; return }
        status = "Ajout en cours…"
        try {
            val w = Regex("archiveofourown\\.org/works/(\\d+)").find(url)?.groupValues?.get(1)?.let { id -> withContext(Dispatchers.IO) { Ao3.work(id, "Ajouté") } }
                ?: Sites.find(url)?.info(url)?.copy(sources = setOf("Ajouté"))
                ?: run { status = "Site non pris en charge"; return }
            update(work(w.id)?.let { o -> w.copy(sources = o.sources + w.sources, progress = o.progress, lastRead = o.lastRead) } ?: w)
            status = "« ${w.title} » ajoutée"
            push(Screen.Detail(w.id))
        } catch (e: Exception) { status = "Ajout impossible : ${e.message}" }
    }

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
        val w = work(id) ?: return
        val (t, html) = if (w.site == "AO3") withContext(Dispatchers.IO) { Ao3.download(id) } else Sites.of(w.site)!!.download(w)
        store.text(id).writeText(html); store.titles(id).writeText(t.joinToString("\n"))
        work(id)?.let { update(it.copy(newChapters = 0)) }
    }

    /** Synchro incrémentale : s'arrête dès que les pages ne contiennent plus que des fanfictions connues et inchangées. */
    suspend fun sync(full: Boolean = false) {
        val u = user
        if (u == null && users.isEmpty() && works.none { it.site != "AO3" }) { status = "Connecte un compte pour synchroniser"; return }
        if (syncing) return
        syncing = true
        val old = works.associateBy { it.id }
        val seen = LinkedHashMap<String, Work>()
        fun add(w: Work) { seen[w.id] = seen[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        fun unchanged(w: Work) = old[w.id]?.let { it.chapters == w.chapters && it.updated == w.updated } == true
        fun publish() {
            val merged = LinkedHashMap<String, Work>()
            old.values.filter { !full || it.site != "AO3" }.forEach { merged[it.id] = it }
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
          if (u != null) {
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
          }
            // 5. Autres sites : bibliothèque du compte puis fanfictions suivies non terminées.
            for (s in Sites.all) {
                val su = users[s.name]
                if (su != null) runCatching {
                    status = "${s.name}…"
                    s.library(su, old).forEach { add(it) }; publish()
                }.onFailure { status = "${s.name} : ${it.message}" }
                old.values.filter { it.site == s.name && it.id !in seen && (full || !it.complete) }.forEach { w ->
                    status = "${s.name} : ${w.title.take(30)}"
                    runCatching { s.info(w.link) }.getOrNull()?.let { add(it.copy(sources = w.sources)) }
                }
                publish()
            }
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
        Web.app = applicationContext
        hub = Hub(this)
        handle(intent)
        setContent { HubApp(hub) }
    }

    private lateinit var hub: Hub
    private fun handle(i: Intent?) { if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { hub.pendingImport = it } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
}
