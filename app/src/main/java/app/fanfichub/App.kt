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
    data class Web(val url: String, val title: String) : Screen
    data class Setup(val site: String) : Screen
}

class Hub(ctx: Context) {
    private val app = ctx.applicationContext
    private val store = Store(ctx.filesDir)
    private val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    val works = mutableStateListOf<Work>()
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
    /** Chargement des fanfictions hors du fil principal (ouverture instantanée) ; la synchro l'attend. */
    private val loadJob = CoroutineScope(Dispatchers.Main.immediate).launch {
        val l = withContext(Dispatchers.IO) { runCatching { store.clearFr() }; runCatching { store.load() }.getOrDefault(emptyList()) }
        works.addAll(l)
    }
    suspend fun awaitLoad() = loadJob.join()
    init { themeMode = prefs.getInt("themeMode", if (prefs.getBoolean("amoled", true)) 0 else 1) }
    fun setAppTheme(m: Int) { themeMode = m; prefs.edit().putInt("themeMode", m).apply() }
    /** Les tâches longues vivent ici : changer d'écran ne les interrompt plus. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var site by mutableStateOf(prefs.getString("site", null)); private set
    var syncProgress by mutableStateOf<Float?>(null)
    val siteStatus = mutableStateMapOf<String, String>()
    var intro by mutableStateOf<String?>(null)
    /** L'étagère s'affiche à chaque ouverture de l'app. */
    var showShelf by mutableStateOf(true)
    var bookAnim by mutableStateOf(prefs.getBoolean("bookAnim", true)); private set
    fun toggleBookAnim(v: Boolean) { bookAnim = v; prefs.edit().putBoolean("bookAnim", v).apply() }
    fun pickSite(s: String, animate: Boolean = false) {
        if (animate && bookAnim && s != "ALL") intro = s
        site = s; prefs.edit().putString("site", s).apply()
    }
    fun account(s: String) = if (s == "AO3") user else users[s]
    fun startSync(full: Boolean = false, only: String? = null, quick: Boolean = false) { scope.launch { sync(full, only, quick) } }
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
    fun work(id: String) = works.firstOrNull { it.id == id } ?: results.firstOrNull { it.id == id }
    fun inLibrary(id: String) = works.any { it.id == id }

    // ---------- Recherche ----------
    var query by mutableStateOf(SearchQuery())
    val results = mutableStateListOf<Work>()
    var searching by mutableStateOf(false); private set
    var searchErr by mutableStateOf<String?>(null); private set
    var searchMore by mutableStateOf(false); private set
    private var searchPage = 1
    private var searchJob: kotlinx.coroutines.Job? = null

    fun runSearch(more: Boolean = false) {
        val q = query
        if (q.text.isBlank() && q.fandom.isBlank()) return
        searchJob?.cancel()
        if (!more) { results.clear(); searchPage = 1 } else searchPage++
        searching = true; searchErr = null
        searchJob = scope.launch {
            try {
                val raw = when (q.site) {
                    "Wattpad" -> Wattpad.searchStories(q.text, (searchPage - 1) * 20)
                    "FanFiction.net" -> Ffn.searchStories(q.text, searchPage)
                    "Spirit Fanfics" -> Spirit.searchStories(q.text, searchPage)
                    else -> withContext(Dispatchers.IO) { Ao3.search(q, searchPage) }
                }
                // Filtres appliqués par l'app quand le site ne les propose pas
                var list = raw.filter { w ->
                    (q.status == 0 || (q.status == 1) == w.complete || w.chapters.startsWith("?")) &&
                    (q.minWords == 0 || w.words == 0 || w.words >= q.minWords)
                }
                if (q.site != "AO3") list = when (q.sort) { 1 -> list.sortedByDescending { it.kudos }; 3 -> list.sortedByDescending { it.words }; else -> list }
                val known = results.map { it.id }.toSet()
                results.addAll(list.filter { it.id !in known })
                searchMore = raw.size >= 10
                if (results.isEmpty()) searchErr = "Aucun résultat"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { searchErr = "Recherche impossible : ${e.message ?: "réseau"}" }
            finally { searching = false }
        }
    }

    fun addToLibrary(w: Work) { update(w.copy(sources = setOf("Ajouté"))) }
    /** Recharge depuis le disque (la synchro d'arrière-plan a pu écrire entre-temps). */
    fun reload() { if (!syncing && loadJob.isCompleted) { val l = store.load(); if (l.isNotEmpty()) { works.clear(); works.addAll(l) } } }
    fun update(w: Work) { val i = works.indexOfFirst { it.id == w.id }; if (i >= 0) works[i] = w else works += w; persist() }
    private var saveJob: kotlinx.coroutines.Job? = null
    /** Écriture disque groupée et hors du fil principal (plus de saccades). */
    fun persist() { saveJob?.cancel(); val snap = works.toList(); saveJob = scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(400); store.save(snap) } }
    fun flush() { saveJob?.cancel(); store.save(works.toList()) }
    var downloaded by mutableStateOf(store.downloadedIds()); private set
    /** Toucher un volume de l'étagère : animation puis bibliothèque du site. */
    fun openSite(s: String) { if (bookAnim) intro = s else enterSite(s) }
    fun enterSite(s: String) { pickSite(s); tab = 1; if (s != "ALL" && account(s) == null) push(Screen.Setup(s)) }
    fun saveUser(u: String?) { user = u?.trim()?.ifEmpty { null }; prefs.edit().putString("user", user).apply() }
    fun setReader(t: Int = theme, s: Int = size) { theme = t; size = s.coerceIn(14, 30); prefs.edit().putInt("theme", theme).putInt("size", size).apply() }
    fun logout() { CookieManager.getInstance().removeAllCookies(null); saveUser(null) }
    fun hasText(id: String) = id in downloaded
    fun text(id: String) = store.text(id).readText()
    /** Traduction préchargée en mémoire vive le temps de la lecture ; rien n'est enregistré. */
    val frText = mutableStateMapOf<String, String>()
    private val frJobs = HashMap<String, kotlinx.coroutines.Job>()
    fun hasFr(id: String) = frText[id] != null
    fun textFr(id: String) = frText[id] ?: ""
    val trProgress = mutableStateMapOf<String, Float>()
    val trError = mutableStateMapOf<String, String>()
    var webFr by mutableStateOf(prefs.getBoolean("webFr", false)); private set
    /** Traduction automatique en français (résumés et lecteur). */
    var autoTr by mutableStateOf(prefs.getBoolean("autoTr", true)); private set
    fun toggleAutoTr(v: Boolean) { autoTr = v; prefs.edit().putBoolean("autoTr", v).apply() }
    fun toggleWebFr(v: Boolean) { webFr = v; prefs.edit().putBoolean("webFr", v).apply() }

    fun pretranslate(id: String) {
        if (hasFr(id) || frJobs[id]?.isActive == true || !hasText(id)) return
        trProgress[id] = 0f; trError.remove(id)
        frJobs[id] = scope.launch {
            try { frText[id] = Translator.toFrench(text(id)) { trProgress[id] = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { trError[id] = e.message ?: "Traduction impossible" } finally { trProgress.remove(id) }
        }
    }
    fun dropFr(id: String) { frJobs.remove(id)?.cancel(); frText.remove(id); trProgress.remove(id); trError.remove(id) }
    fun titles(id: String) = store.titles(id).takeIf { it.exists() }?.readLines() ?: emptyList()

    suspend fun detectUser() { withContext(Dispatchers.IO) { Ao3.username() }?.let { saveUser(it) } }

    suspend fun download(id: String) {
        val w = work(id) ?: return
        val (t, html) = if (w.site == "AO3") withContext(Dispatchers.IO) { Ao3.download(id) } else Sites.of(w.site)!!.download(w)
        store.text(id).writeText(html); store.titles(id).writeText(t.joinToString("\n")); downloaded = downloaded + id
        work(id)?.let { update(it.copy(newChapters = 0)) }
    }

    /** Synchro incrémentale : s'arrête dès que les pages ne contiennent plus que des fanfictions connues et inchangées. */
    suspend fun sync(full: Boolean = false, only: String? = null, quick: Boolean = false) {
        awaitLoad()
        val now = System.currentTimeMillis()
        fun fresh(w: Work) = quick && now - w.checked < 3 * 3_600_000L
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
                    progress = o.progress, lastRead = o.lastRead, sources = if (full) f.sources else o.sources + f.sources,
                    checked = maxOf(f.checked, o.checked), cover = f.cover.ifEmpty { o.cover },
                    summary = f.summary.ifBlank { o.summary }, author = f.author.ifBlank { o.author })
            }
            works.clear(); works.addAll(merged.values); persist()
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
                                known != null && !full && fresh(known) -> known.copy(sources = known.sources + "Abonnement")
                                known != null && !full -> {
                                    val n = withContext(Dispatchers.IO) { Ao3.chapterCount(id) }
                                    if (n != null && n <= known.published) known.copy(sources = known.sources + "Abonnement", checked = now)
                                    else withContext(Dispatchers.IO) { Ao3.work(id, "Abonnement") }?.copy(checked = now)
                                }
                                else -> withContext(Dispatchers.IO) { Ao3.work(id, "Abonnement") }?.copy(checked = now)
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
                    siteStatus.remove(s.name)
                    status = "${s.name} · bibliothèque…"; syncProgress = null
                    val l = s.library(su, old); l.forEach { add(it) }; publish()
                    siteStatus[s.name] = "${l.size} fanfictions synchronisées"
                }.onFailure { e -> siteStatus[s.name] = if (old.values.any { it.site == s.name }) "Synchro partielle : ${e.message}" else "Erreur : ${e.message}" }
                // Détails : fiches encore incomplètes (« ?/? ») et fanfictions suivies non terminées.
                fun noSummary(w: Work) = w.summary.isBlank() && old[w.id]?.summary.isNullOrBlank() && !(quick && (old[w.id]?.let { now - it.checked < 3 * 3_600_000L } == true))
                fun slugTitle(w: Work) = Regex("^[a-z0-9-]+-\\d+$").matches(w.title)
                val targets = (seen.values.filter { it.site == s.name && (it.chapters.startsWith("?") || noSummary(it) || slugTitle(old[it.id] ?: it)) } +
                    old.values.filter { it.site == s.name && it.id !in seen && (full || !it.complete) && !fresh(it) }).distinctBy { it.id }
                targets.forEachIndexed { i, w ->
                    status = "${s.name} · détails ${i + 1}/${targets.size}"; syncProgress = (i + 1f) / targets.size
                    runCatching { s.info(w.link) }.getOrNull()?.let { add(it.copy(sources = seen[w.id]?.sources ?: w.sources, checked = now)) }
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
        } finally { syncing = false; syncProgress = null; prefetchCovers() }
    }

    /** Couvertures préchargées en arrière-plan : les étagères s'affichent sans attendre. */
    fun prefetchCovers() { scope.launch { Img.prefetch(works.sortedByDescending { it.lastRead }.map { it.cover }) } }

    fun syncDue(minutes: Int = 20) = (user != null || users.isNotEmpty()) && System.currentTimeMillis() - prefs.getLong("lastSync", 0) > minutes * 60_000L
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // Rapport de plantage : l'erreur est enregistrée puis affichée au lancement suivant.
        val crash = java.io.File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crash.writeText("Fanfic Hub v${Updater.current(this)}\n" + android.util.Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
        if (crash.exists()) {
            val text = crash.readText()
            setContent { CrashScreen(text) { crash.delete(); recreate() } }
            return
        }
        enableEdgeToEdge()
        Web.app = applicationContext
        Img.dir = java.io.File(cacheDir, "img").apply { mkdirs() }
        hub = Hub(this)
        handle(intent)
        setContent { HubApp(hub) }
        runCatching { hub.prefetchCovers() }
        BackgroundSync.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!::hub.isInitialized) return
        if (::hub.isInitialized) { hub.reload(); if (hub.syncDue(2)) hub.startSync(quick = true) }
    }
    override fun onPause() { super.onPause(); if (::hub.isInitialized) runCatching { hub.flush() } }

    private lateinit var hub: Hub
    private fun handle(i: Intent?) { if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { hub.pendingImport = it } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
}
