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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Login(val site: String) : Screen
    data class Detail(val id: String) : Screen
    data class Reader(val id: String) : Screen
    data class Setup(val site: String) : Screen
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
    init { amoled = prefs.getBoolean("amoled", true) }
    fun setDark(v: Boolean) { amoled = v; prefs.edit().putBoolean("amoled", v).apply() }
    /** Les tâches longues vivent ici : changer d'écran ne les interrompt plus. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var site by mutableStateOf(prefs.getString("site", null)); private set
    var syncProgress by mutableStateOf<Float?>(null)
    val siteStatus = mutableStateMapOf<String, String>()
    var intro by mutableStateOf<String?>(null)
    var bookAnim by mutableStateOf(prefs.getBoolean("bookAnim", true)); private set
    fun setBookAnim(v: Boolean) { bookAnim = v; prefs.edit().putBoolean("bookAnim", v).apply() }
    fun pickSite(s: String, animate: Boolean = false) {
        if (animate && bookAnim && s != "ALL" && s != site) intro = s
        site = s; prefs.edit().putString("site", s).apply()
    }
    fun account(s: String) = if (s == "AO3") user else users[s]
    fun startSync(full: Boolean = false, only: String? = null) { scope.launch { sync(full, only) } }
    fun startUpdate() { scope.launch { doUpdate() } }
    fun startImport(url: String) { scope.launch { importUrl(url) } }
    fun startCheck(manual: Boolean = false) { scope.launch { checkUpdate(manual) } }

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
    /** Recharge depuis le disque (la synchro d'arrière-plan a pu écrire entre-temps). */
    fun reload() { if (!syncing) { val l = store.load(); if (l.isNotEmpty()) { works.clear(); works.addAll(l) } } }
    fun update(w: Work) { val i = works.indexOfFirst { it.id == w.id }; if (i >= 0) works[i] = w else works += w; store.save(works) }
    fun saveUser(u: String?) { user = u?.trim()?.ifEmpty { null }; prefs.edit().putString("user", user).apply() }
    fun setReader(t: Int = theme, s: Int = size) { theme = t; size = s.coerceIn(14, 30); prefs.edit().putInt("theme", theme).putInt("size", size).apply() }
    fun logout() { CookieManager.getInstance().removeAllCookies(null); saveUser(null) }
    fun hasText(id: String) = store.text(id).exists()
    fun text(id: String) = store.text(id).readText()
    fun hasFr(id: String) = store.fr(id).exists()
    fun textFr(id: String) = store.fr(id).readText()
    suspend fun translate(id: String, progress: (Float) -> Unit) { store.fr(id).writeText(Translator.toFrench(text(id), progress)) }
    fun titles(id: String) = store.titles(id).takeIf { it.exists() }?.readLines() ?: emptyList()

    suspend fun detectUser() { withContext(Dispatchers.IO) { Ao3.username() }?.let { saveUser(it) } }

    suspend fun download(id: String) {
        val w = work(id) ?: return
        val (t, html) = if (w.site == "AO3") withContext(Dispatchers.IO) { Ao3.download(id) } else Sites.of(w.site)!!.download(w)
        store.text(id).writeText(html); store.titles(id).writeText(t.joinToString("\n")); store.fr(id).delete()
        work(id)?.let { update(it.copy(newChapters = 0)) }
    }

    /** Synchro incrémentale : s'arrête dès que les pages ne contiennent plus que des fanfictions connues et inchangées. */
    suspend fun sync(full: Boolean = false, only: String? = null) {
        val u = user.takeIf { only == null || only == "AO3" }
        if (only != null && account(only) == null && works.none { it.site == only }) { status = "Configure d'abord ton compte $only"; return }
        if (u == null && users.isEmpty() && works.none { it.site != "AO3" }) { status = "Connecte un compte pour synchroniser"; return }
        if (syncing) return
        syncing = true
        val old = works.associateBy { it.id }
        val seen = LinkedHashMap<String, Work>()
        fun add(w: Work) { seen[w.id] = seen[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        fun unchanged(w: Work) = old[w.id]?.let { it.chapters == w.chapters && it.updated == w.updated } == true
        fun publish() {
            val merged = LinkedHashMap<String, Work>()
            old.values.filter { !(full && it.site == "AO3" && u != null) }.forEach { merged[it.id] = it }
            seen.values.forEach { f ->
                val o = old[f.id]
                val gained = if (o == null || o.chapters.startsWith("?") || f.chapters.startsWith("?")) 0 else maxOf(0, f.published - o.published)
                merged[f.id] = if (o == null) f else if (f.chapters.startsWith("?") && !o.chapters.startsWith("?")) o.copy(sources = o.sources + f.sources) else f.copy(newChapters = o.newChapters + gained,
                    progress = o.progress, lastRead = o.lastRead, sources = if (full) f.sources else o.sources + f.sources)
            }
            works.clear(); works.addAll(merged.values); store.save(works)
        }
        suspend fun pages(start: String, src: String, max: Int, stop: (List<Work>) -> Boolean) {
            var url: String? = start; var n = 0
            while (url != null && n++ < max) {
                val (list, next) = withContext(Dispatchers.IO) { Ao3.page(url!!, src) }
                list.forEach { add(it) }; publish()
                status = "AO3 · ${src.lowercase()} · ${seen.size} fanfictions"; syncProgress = null
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
            val todo = ids.distinct()
            val sem = Semaphore(4); var done = 0
            coroutineScope {
                todo.map { id ->
                    async {
                        sem.withPermit {
                            val inSeen = seen[id]; val known = inSeen ?: old[id]
                            val w: Work? = when {
                                inSeen != null -> inSeen.copy(sources = setOf("Abonnement"))
                                known != null && known.complete && !full -> known.copy(sources = known.sources + "Abonnement")
                                known != null && !full -> {
                                    val n = withContext(Dispatchers.IO) { Ao3.chapterCount(id) }
                                    if (n != null && n <= known.published) known.copy(sources = known.sources + "Abonnement")
                                    else withContext(Dispatchers.IO) { Ao3.work(id, "Abonnement") }
                                }
                                else -> withContext(Dispatchers.IO) { Ao3.work(id, "Abonnement") }
                            }
                            w?.let { add(it) }
                            done++; status = "AO3 · abonnements $done/${todo.size}"; syncProgress = done.toFloat() / todo.size
                            if (done % 8 == 0) publish()
                        }
                    }
                }.awaitAll()
            }
            publish()
            siteStatus["AO3"] = "${seen.values.count { it.site == "AO3" }} fanfictions synchronisées"
          }
            // 5. Autres sites : bibliothèque du compte puis fanfictions suivies non terminées.
            for (s in Sites.all.filter { only == null || it.name == only }) {
                val su = users[s.name]
                if (su != null) runCatching {
                    status = "${s.name} · bibliothèque…"; syncProgress = null
                    val l = s.library(su, old); l.forEach { add(it) }; publish()
                    siteStatus[s.name] = "${l.size} fanfictions synchronisées"
                }.onFailure { siteStatus[s.name] = "Erreur : ${it.message}" }
                // Détails : fiches encore incomplètes (« ?/? ») et fanfictions suivies non terminées.
                val targets = (seen.values.filter { it.site == s.name && it.chapters.startsWith("?") } +
                    old.values.filter { it.site == s.name && it.id !in seen && (full || !it.complete) }).distinctBy { it.id }
                targets.forEachIndexed { i, w ->
                    status = "${s.name} · détails ${i + 1}/${targets.size}"; syncProgress = (i + 1f) / targets.size
                    runCatching { s.info(w.link) }.getOrNull()?.let { add(it.copy(sources = seen[w.id]?.sources ?: w.sources)) }
                    if (i % 4 == 3) publish()
                }
                publish()
            }
            if (only == null) prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            val mine = works.filter { only == null || it.site == only }
            val nw = mine.count { it.newChapters > 0 }
            status = (only?.let { "$it · " } ?: "") + "${mine.size} fanfictions · " + if (nw == 0) "tout est à jour" else "$nw avec de nouveaux chapitres"
        } catch (e: Exception) {
            publish()
            status = "Synchro interrompue : ${e.message ?: "réseau indisponible"}"
        } finally { syncing = false; syncProgress = null }
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
        BackgroundSync.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() { super.onResume(); if (::hub.isInitialized) hub.reload() }

    private lateinit var hub: Hub
    private fun handle(i: Intent?) { if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { hub.pendingImport = it } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
}
