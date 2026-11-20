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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Login(val site: String) : Screen
    data class Detail(val id: String) : Screen
    data class Reader(val id: String) : Screen
    data class Web(val url: String, val title: String, val reader: Boolean = true, val readOf: String = "") : Screen
    data class Setup(val site: String) : Screen
    data object Extensions : Screen
}

val DIAGS = linkedMapOf("all" to "Toute l'app", "covers" to "Couvertures", "sites" to "Sites et synchro", "read" to "Traduction et lecture", "app" to "App et stockage", "ext" to "Extensions")

class Hub(ctx: Context) {
    private val app = ctx.applicationContext
    private val store = Store(ctx.filesDir)
    private val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    val works = mutableStateListOf<Work>()
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableIntStateOf(0)
    var user by mutableStateOf(prefs.getString("user", null)); private set
    var syncing by mutableStateOf(false); private set
    /** État de la synchro : silencieux (jamais en bulle). */
    var syncInfo by mutableStateOf<String?>(null)
    /** Message court affiché en bulle (Toast) : uniquement après une action de l'utilisateur. */
    var status: String? = null
        set(v) { field = v; if (v != null) android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(app, v, android.widget.Toast.LENGTH_SHORT).show() } }
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
        works.addAll(l); lastStamp = store.stamp()
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
    private var lastCheck = 0L
    // Vérifie aussi toutes les 2 min tant que l'app est ouverte (une version publiée apparaît sans relancer l'app)
    init { scope.launch { while (true) { kotlinx.coroutines.delay(120_000); runCatching { checkUpdate() } } } }
    fun startCheck(manual: Boolean = false) {
        if (!manual && System.currentTimeMillis() - lastCheck < 60_000) return
        lastCheck = System.currentTimeMillis(); scope.launch { checkUpdate(manual) }
    }

    fun saveSiteUser(site: String, u: String?) {
        val v = u?.trim()?.ifEmpty { null }
        if (v == null) users.remove(site) else users[site] = v
        prefs.edit().putString("user_$site", v).apply()
    }

    suspend fun checkUpdate(manual: Boolean = false) {
        val l = Updater.latest()
        newVersion = l?.takeIf { it > version }
        if (manual && l == null) status = "Impossible de vérifier les mises à jour"
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
    /** Lire : reprend au dernier chapitre ouvert, sinon ouvre directement le 1er chapitre (jamais la page de présentation). */
    fun read(w: Work) { scope.launch {
        val last = prefs.getString("last:" + w.link, null)
        val u = last ?: when (w.site) {
            "Wattpad" -> Wattpad.firstPart(w.link)
            "Spirit Fanfics" -> Spirit.firstChapter(w.link)
            "FanFiction.net" -> Regex("/s/(\\d+)").find(w.link)?.let { "https://www.fanfiction.net/s/${it.groupValues[1]}/1" }
            else -> null
        } ?: w.link
        push(Screen.Web(u, w.title, readOf = w.link))
    } }
    /** Mémorise le chapitre en cours d'une histoire (pour reprendre au bon endroit). */
    fun rememberChapter(of: String, url: String) { if (of.isNotBlank() && url.startsWith("http")) prefs.edit().putString("last:$of", url).apply() }
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

    private var extChecked = false // extensions vérifiées (mise à jour) avant la 1re recherche
    fun runSearch(more: Boolean = false) {
        val q = query
        if (q.text.isBlank() && q.fandom.isBlank() && q.character.isBlank() && q.spirit.isEmpty()) return
        searchJob?.cancel()
        if (!more) { results.clear(); listHits.clear(); openedList = null; searchPage = 1 } else searchPage++
        if (q.site == "Wattpad" && q.wpLists) {
            // Tout Wattpad : plusieurs robots en parallèle cherchent les listes qui contiennent des histoires avec ce tag
            searching = true; searchErr = null; searchMore = false; listTag = q.text.trim(); searchInfo = "100 robots : recherche des histoires " + Wattpad.splitTags(listTag).joinToString(" + ") { "#$it" } + "…"
            searchJob = scope.launch {
                agg.clear(); aggDirty = false
                val ticker = launch { while (true) { kotlinx.coroutines.delay(350); if (aggDirty) { aggDirty = false; rebuildAgg() } } }
                try {
                    withContext(Dispatchers.Default) { Wattpad.scanLists(listTag,
                        onProgress = { msg -> scope.launch { searchInfo = msg } },
                        onHit = { h -> scope.launch {
                            if (listHits.none { it.id == h.id }) {
                                val at = listHits.indexOfFirst { it.score < h.score }.let { if (it < 0) listHits.size else it }
                                listHits.add(at, h)
                            }
                        } },
                        onStory = { w, m ->
                            agg.compute(w.id) { _, o -> if (o == null) Triple(w, m, 1) else Triple(o.first, maxOf(o.second, m), o.third + 1) }
                            aggDirty = true
                        }) }
                    rebuildAgg()
                    if (listHits.isEmpty() && results.isEmpty()) searchErr = "Rien trouvé avec #${listTag}"
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { searchErr = "Recherche impossible : ${e.message}" } finally { ticker.cancel(); searching = false; searchInfo = null }
            }
            return
        }
        searching = true; searchErr = null
        searchJob = scope.launch {
            try {
                val raw = when (q.site) {
                    "Wattpad" -> Wattpad.searchStories(q.text, (searchPage - 1) * 20)
                    "FanFiction.net" -> Ffn.searchStories(q.text, searchPage)
                    "Spirit Fanfics" -> Spirit.searchStories(q.text, searchPage, q.spirit.mapValues { it.value.first })
                    "AO3" -> withContext(Dispatchers.IO) { Ao3.search(q, searchPage) }
                    else -> { if (!extChecked) { extChecked = true; Ext.updateAll() }; Ext.byName(q.site)?.search(q.text, searchPage) ?: emptyList() }
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

    var diagRunning by mutableStateOf(false); private set
    /** Diagnostic Spirit : relit le compte en journalisant tout, puis propose de partager le rapport. */
    /**
     * Diagnostic rapide (≈20 s) : par site, état des fiches (résumé, couverture, titre) puis test ciblé
     * sur 2 fiches sans résumé et sur la page de liste/favoris. Tous les sites en parallèle, délai max 30 s chacun.
     */
    /** Vide le cache des pages web s'il dépasse 120 Mo (pages des sites, pas tes données). */
    fun trimCache() { scope.launch {
        val size = withContext(Dispatchers.IO) { app.cacheDir.walkTopDown().sumOf { it.length() } }
        if (size > 120L * 1024 * 1024) android.webkit.WebView(app).clearCache(true)
    } }

    private var diagJob: kotlinx.coroutines.Job? = null
    var diagKind by mutableStateOf("all"); private set
    fun cancelDiag() { diagJob?.cancel(); diagRunning = false }
    fun runDiag(kind: String = "all") { if (diagRunning) { cancelDiag(); return }; diagRunning = true; diagKind = kind; diagJob = scope.launch {
        fun on(vararg k: String) = kind == "all" || kind in k
        val diagStart = System.currentTimeMillis()
        val out = StringBuffer()
        fun log(s: String) { out.append(s).append('\n') }
        log("Diagnostic « ${DIAGS[kind] ?: kind} » · Fanfic Hub v$version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
        val ls = prefs.getLong("lastSync", 0)
        log("Dernière synchro complète : " + if (ls == 0L) "jamais" else "il y a ${(System.currentTimeMillis() - ls) / 60000} min" + if (syncing) " · synchro en cours" else "")
        // Toutes les sections tournent EN MÊME TEMPS (rapport assemblé dans l'ordre) ; délais courts pour le diagnostic complet
        val quick = kind == "all"
        val T = if (quick) 12_000L else 30_000L
        suspend fun <R> lim(ms: Long = T, f: suspend () -> R): R? = kotlinx.coroutines.withTimeoutOrNull(ms) { f() }
        fun sec(cond: Boolean, f: suspend StringBuilder.() -> Unit) = if (!cond) null else async { val r = StringBuilder(); runCatching { r.f() }.onFailure { r.append("  ! ${it.message}\n") }; r.toString() }

        val siteJobs = if (!on("sites")) emptyList() else Sites.all.map { site -> async(Dispatchers.IO) {
            val r = StringBuilder()
            val mine = works.filter { it.site == site.name }
            val noSum = mine.filter { it.summary.isBlank() }
            r.append("\n== ${site.name} · compte ${account(site.name) ?: "aucun"} · synchro : ${siteStatus[site.name] ?: "pas lancée depuis l'ouverture"}\n")
            r.append("  ${mine.size} fiches · sans résumé ${noSum.size} · sans couverture ${mine.count { !goodCover(it.cover) }} (vide ${mine.count { it.cover.isBlank() }}, doublon ${mine.count { it.cover.isNotBlank() && it.cover in dupCovers }}) · titres-identifiants ${mine.count { Regex("^[a-z0-9-]+-\\d+$").matches(it.title) }}\n")
            noSum.take(if (quick) 1 else 2).forEach { w ->
                val t0 = System.currentTimeMillis()
                val inf = lim { runCatching { site.info(w.link, "ui") }.getOrElse { e -> r.append("  ! ${w.title.take(40)} : ${e.message}\n"); null } }
                r.append("  • ${w.title.take(45)} (${System.currentTimeMillis() - t0} ms) → résumé « ${inf?.summary?.take(90) ?: "— (délai dépassé ou vide)"} »\n")
            }
            account(site.name)?.let { u -> (lim { runCatching { site.probe(u) }.getOrElse { "  ! sonde : ${it.message}" } } ?: "  ! sonde : délai dépassé (${T / 1000} s)").let { r.append(it).append('\n') } }
            r.toString()
        } }
        val ao3Job = sec(on("sites")) {
            append("\n== AO3 · compte ${user ?: "aucun"} · ${siteStatus["AO3"] ?: "pas de synchro depuis l'ouverture"}\n")
            works.filter { it.site == "AO3" }.let { a -> append("  ${a.size} fiches · sans résumé ${a.count { it.summary.isBlank() }} · nouveaux chapitres ${a.count { it.newChapters > 0 }}\n") }
            user?.let { u -> val t0 = System.currentTimeMillis()
                append("  test favoris : " + (lim { runCatching { withContext(Dispatchers.IO) { Ao3.page("/users/$u/bookmarks", "Favori").first.size } }.fold({ "$it fiches (${System.currentTimeMillis() - t0} ms)" }, { "ERREUR ${it.message}" }) } ?: "délai dépassé") + "\n") }
        }
        val appJob = sec(on("app")) {
            append("\n== App et bibliothèque\n")
            append("  version $version · dernière en ligne ${lim(8000) { runCatching { Updater.latest() }.getOrNull() } ?: "?"}\n")
            append("  fiches : ${works.size} au total · " + works.groupingBy { it.site }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" } + " · masquées ${hidden.size}\n")
            append("  sauvegardes : " + backups().joinToString(" · ") { (f, n) -> f.name.removePrefix("works-bak-").removeSuffix(".json") + " ($n)" }.ifEmpty { "aucune encore" } + "\n")
            withContext(Dispatchers.IO) { append("  stockage : données ${app.filesDir.walkTopDown().sumOf { it.length() } / 1024} Ko · cache ${app.cacheDir.walkTopDown().sumOf { it.length() } / 1024} Ko\n") }
            val rt = Runtime.getRuntime()
            append("  mémoire : ${(rt.totalMemory() - rt.freeMemory()) / 1048576} Mo utilisés / ${rt.maxMemory() / 1048576} Mo · WebView ${runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull() ?: "?"}\n")
            append(java.io.File(app.filesDir, "crash.txt").takeIf { it.exists() }?.let { "  DERNIER PLANTAGE :\n" + it.readText().take(600) + "\n" } ?: "  aucun plantage enregistré\n")
        }
        val readJob = sec(on("read")) {
            append("\n== Traduction et lecture\n")
            val t1 = System.currentTimeMillis()
            append("  traduction : " + (lim(10_000) { runCatching { Translator.texts(listOf("Hello, the pirate king is here.")).first() }.fold({ "« $it » (${System.currentTimeMillis() - t1} ms)" }, { "ERREUR ${it.message}" }) } ?: "délai dépassé") + "\n")
            append("  réglages : traduction auto $autoTr · thème $theme · taille $size · animation $bookAnim\n")
        }
        val coverJob = sec(on("covers")) {
            append("\n== Couvertures\n")
            val t2 = System.currentTimeMillis()
            append("  image test : " + (works.firstOrNull { goodCover(it.cover) && !it.cover.startsWith("montage:") }?.let { w -> lim(8000) { runCatching { Img.load(w.cover) }.getOrNull() }?.let { "chargée (${it.width}×${it.height}, ${System.currentTimeMillis() - t2} ms)" } ?: "échec ${w.cover.take(60)}" } ?: "aucune") + "\n")
            (Sites.all.map { it.name } + "AO3" + Ext.installed.map { it.name }).distinct().forEach { n -> val m = works.filter { it.site == n }
                if (m.isNotEmpty()) append("  $n : ${m.size} fiches · couverture valable ${m.count { goodCover(it.cover) }} · vide ${m.count { it.cover.isBlank() }} · doublon ${m.count { it.cover.isNotBlank() && it.cover in dupCovers }}\n") }
            append("  AO3 illustrées : $artLog · réseau ${Art.probe()}\n")
            works.filter { it.site == "AO3" }.let { a -> append("  ${a.size} fiches · montage ${a.count { it.cover.startsWith("montage:") }} · image seule ${a.count { it.cover.isNotBlank() && !it.cover.startsWith("montage:") }} · vides ${a.count { it.cover.isBlank() }}\n") }
            works.filter { it.site == "AO3" }.sortedByDescending { (if ("luffy" in it.title.lowercase()) 2 else 0) + (if (isCrossover(it)) 1 else 0) }.take(if (quick) 3 else 8).forEach { w ->
                append(runCatching { Art.explain(w) }.getOrElse { "  ! ${it.message}\n" }) }
        }
        val extJob = sec(on("ext")) {
            append("\n== Extensions\n")
            append("  mise à jour : " + (lim(10_000) { runCatching { Ext.updateAll(); "vérifiée" }.getOrElse { "échec ${it.message}" } } ?: "délai dépassé") + " · catalogue : " + Ext.catalog.joinToString { it.first } + "\n")
            if (Ext.installed.isEmpty()) append("  aucune extension installée\n")
            Ext.installed.toList().map { e -> async { runCatching { e.diag(quick = quick) }.getOrElse { "  ! ${e.name} : ${it.message}\n" } } }.awaitAll().forEach { append(it) }
        }
        siteJobs.awaitAll().forEach { out.append(it) }
        listOf(ao3Job, appJob, readJob, coverJob, extJob).forEach { j -> j?.await()?.let { out.append(it) } }
        out.append("\n(diagnostic en ${(System.currentTimeMillis() - diagStart) / 1000} s)\n")
        diagRunning = false
        val txt = out.toString()
        runCatching { (app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("diag", txt)) }
        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Rapport de diagnostic")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } }

    private val detailing = HashSet<String>()

    /** Tri des listes : 0 = A–Z, 1 = récent, 2 = crossovers d'abord. */
    var sortMode by mutableIntStateOf(prefs.getInt("sortMode", 1)); private set
    fun setSort(m: Int) { sortMode = m; prefs.edit().putInt("sortMode", m).apply() }

    // ---- Suppression (masquage) avec annuler / rétablir / tout restaurer
    val hidden = mutableStateListOf<String>().apply { addAll(prefs.getStringSet("hidden", emptySet()) ?: emptySet()) }
    private val undoStack = ArrayDeque<Pair<Boolean, Set<String>>>() // (masqué ?, fiches)
    private val redoStack = ArrayDeque<Pair<Boolean, Set<String>>>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private fun applyHide(hide: Boolean, ids: Set<String>) {
        if (hide) hidden.addAll(ids.filter { it !in hidden }) else hidden.removeAll(ids)
        prefs.edit().putStringSet("hidden", hidden.toSet()).apply()
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
    }
    fun hide(ids: Set<String>) { if (ids.isEmpty()) return; undoStack.addLast(true to ids); redoStack.clear(); applyHide(true, ids) }
    fun undo() { val a = undoStack.removeLastOrNull() ?: return; redoStack.addLast(a); applyHide(!a.first, a.second) }
    fun redo() { val a = redoStack.removeLastOrNull() ?: return; undoStack.addLast(a); applyHide(a.first, a.second) }
    fun restoreAll() { if (hidden.isEmpty()) return; val all = hidden.toSet(); undoStack.addLast(false to all); redoStack.clear(); applyHide(false, all) }
    /** À l'ouverture d'une fiche sans résumé : va chercher la page de l'histoire et complète (résumé, mots, chapitres). */
    fun ensureDetails(w: Work) {
        if ((w.summary.isNotBlank() && !badTitle(w.title)) || !detailing.add(w.id)) return
        val site = Sites.all.firstOrNull { it.name == w.site } ?: return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { site.info(w.link, "ui") } }.getOrNull()?.let { inf ->
                val cur = work(w.id) ?: w
                val upd = cur.copy(summary = inf.summary.ifBlank { cur.summary }, words = if (inf.words > 0) inf.words else cur.words,
                    author = cur.author.ifBlank { inf.author }, cover = cur.cover.takeIf { goodCover(it) } ?: inf.cover,
                    title = if (badTitle(cur.title)) inf.title else cur.title)
                if (works.any { it.id == w.id }) update(upd) else { val i = results.indexOfFirst { it.id == w.id }; if (i >= 0) results[i] = upd }
            }
        }
    }

    fun addToLibrary(w: Work) { update(w.copy(sources = setOf("Ajouté"))) }

    val listHits = mutableStateListOf<ListHit>()
    var openedList by mutableStateOf<ListHit?>(null); private set
    var spiritForm by mutableStateOf<List<FormField>>(emptyList()); private set
    fun loadSpiritForm() { if (spiritForm.isEmpty()) scope.launch { runCatching { Spirit.searchForm() }.onSuccess { spiritForm = it } } }
    private var listTag = ""
    /** Fanfictions trouvées par les robots : histoire, nb de tags portés, nb de listes où elle apparaît. */
    private val agg = java.util.concurrent.ConcurrentHashMap<String, Triple<Work, Int, Int>>()
    @Volatile private var aggDirty = false
    val aggCount get() = agg.size
    private fun rebuildAgg() {
        if (openedList != null) return
        val sorted = agg.values.sortedWith(compareByDescending<Triple<Work, Int, Int>> { it.second }.thenByDescending { it.third }.thenByDescending { it.first.kudos })
            .take(600).map { it.first }
        results.clear(); results.addAll(sorted)
    }

    // ---- Ajout sur le compte Wattpad, rangé automatiquement dans une liste par tag(s)
    private val wpListIds = HashMap<String, String>()
    private val wpLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun addWp(w: Work): Boolean? {
        val user = account("Wattpad") ?: return null
        val tags = Wattpad.splitTags(listTag)
        val name = tags.filter { Wattpad.tagged(w.tags, w.title, it) }.ifEmpty { tags }.joinToString(" × ") { it.replaceFirstChar(Char::uppercase) }.ifBlank { "Fanfic Hub" }
        val id = wpLock.withLock {
            if (wpListIds.isEmpty()) runCatching { Wattpad.userLists(user) }.getOrDefault(emptyList()).forEach { wpListIds[it.second.lowercase()] = it.first }
            wpListIds[name.lowercase()] ?: Wattpad.createList(name)?.also { wpListIds[name.lowercase()] = it }
        }
        update(w.copy(sources = setOf("Liste : $name")))
        return id != null && Wattpad.addToList(id, w.id.removePrefix("wp:"))
    }
    fun addToWattpad(w: Work) { scope.launch {
        status = when (addWp(w)) { null -> "Ajoutée dans l'app. Connecte ton compte Wattpad pour l'ajouter aussi sur Wattpad"
            true -> "Ajoutée sur Wattpad, rangée automatiquement"; false -> "Ajoutée dans l'app ; Wattpad a refusé (connecte-toi via « Se connecter sur le site »)" }
    } }
    fun addAllWattpad() { scope.launch {
        val todo = results.filter { !inLibrary(it.id) }
        status = "Ajout de ${todo.size} fanfictions…"
        val gate = kotlinx.coroutines.sync.Semaphore(4); var ok = 0
        todo.map { w -> async { gate.withPermit { if (addWp(w) == true) ok++ } } }.awaitAll()
        status = if (account("Wattpad") == null) "${todo.size} ajoutées dans l'app (connecte Wattpad pour les envoyer sur ton compte)" else "$ok/${todo.size} ajoutées sur Wattpad, rangées par tag"
    } }
    var searchInfo by mutableStateOf<String?>(null); private set
    fun openList(h: ListHit?) {
        openedList = h
        if (h == null) { results.clear(); if (agg.isNotEmpty()) rebuildAgg(); return }
        results.clear(); searchErr = null
        searching = true
        scope.launch {
            try { results.addAll(Wattpad.listWorks(h.id).sortedByDescending { w -> Wattpad.splitTags(listTag).count { t -> Wattpad.tagged(w.tags, w.title, t) } }); if (results.isEmpty()) searchErr = "Liste vide ou privée" }
            catch (e: Exception) { searchErr = "Liste illisible : ${e.message}" } finally { searching = false }
        }
    }
    /** Recharge depuis le disque (la synchro d'arrière-plan a pu écrire entre-temps). */
    /** Relit les fiches seulement si la synchro de fond a modifié le fichier (lecture hors du fil principal). */
    private var lastStamp = 0L
    fun reload() {
        if (syncing || !loadJob.isCompleted) return
        scope.launch {
            val st = withContext(Dispatchers.IO) { store.stamp() }
            if (st <= lastStamp) return@launch
            val l = withContext(Dispatchers.IO) { store.load() }
            if (l.isNotEmpty() && !syncing) { works.clear(); works.addAll(l) }
            lastStamp = st
        }
    }
    fun update(w: Work) { val i = works.indexOfFirst { it.id == w.id }; if (i >= 0) works[i] = w else works += w; persist() }
    private var saveJob: kotlinx.coroutines.Job? = null
    /** Écriture disque groupée et hors du fil principal (plus de saccades). */
    fun persist() { saveJob?.cancel(); val snap = works.toList(); saveJob = scope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(400); store.save(snap); lastStamp = store.stamp() } }
    /** Sauvegardes de la bibliothèque (une par jour) et restauration. */
    fun backups() = store.backups()
    fun restoreBackup(file: java.io.File) { val l = store.loadFile(file); if (l.isNotEmpty()) { works.clear(); works.addAll(l); saveJob?.cancel(); scope.launch(Dispatchers.IO) { store.save(l, allowShrink = true); lastStamp = store.stamp() } } }
    fun flush() { saveJob?.cancel(); store.save(works.toList()); lastStamp = store.stamp() }
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
        val u = user.takeIf { (only == null || only == "AO3") && Ext.on("AO3") }
        if (only != null && account(only) == null && works.none { it.site == only }) { syncInfo = "Configure d'abord ton compte $only"; return }
        if (u == null && users.isEmpty() && works.none { it.site != "AO3" }) { syncInfo = "Connecte un compte pour synchroniser"; return }
        if (syncing) return
        syncing = true
        val old = works.associateBy { it.id }
        SyncMode.quick = quick && !full; SyncMode.known = old.keys
        val seen = LinkedHashMap<String, Work>()
        fun add(w: Work) { seen[w.id] = seen[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
        val libIds = HashSet<String>() // fanfictions renvoyées par la bibliothèque d'un site (collections à jour)
        fun unchanged(w: Work) = old[w.id]?.let { it.chapters == w.chapters && it.updated == w.updated } == true
        fun publish() {
            val merged = LinkedHashMap<String, Work>()
            old.values.filter { !(full && it.site == "AO3" && u != null) }.forEach { merged[it.id] = it }
            seen.values.forEach { f ->
                val o = old[f.id]
                // Pas de « nouveaux chapitres » si l'ancienne fiche était fausse (page de connexion, titre-identifiant, 1 chapitre par défaut)
                val gained = if (o == null || o.chapters.startsWith("?") || f.chapters.startsWith("?") || badTitle(o.title) || (o.published <= 1 && f.published - o.published > 3)) 0
                    else maxOf(0, f.published - o.published)
                // Hors AO3, la bibliothèque du site fait foi : on remplace les collections (on garde seulement « Ajouté »)
                val src = if (full || (f.site != "AO3" && f.id in libIds)) f.sources + o?.sources.orEmpty().filter { it == "Ajouté" } else o?.sources.orEmpty() + f.sources
                merged[f.id] = if (o == null) f else if (f.chapters.startsWith("?") && !o.chapters.startsWith("?")) o.copy(sources = src) else f.copy(newChapters = o.newChapters + gained,
                    progress = o.progress, lastRead = o.lastRead, sources = src,
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
                syncInfo = "AO3 · ${src.lowercase()} · ${seen.size} fanfictions"; syncProgress = null
                if (!full && stop(list)) break
                url = next
            }
        }
        try { coroutineScope {
          // Wattpad, FF.net et Spirit en parallèle d'AO3 (et entre eux) : un site lent ne bloque plus les autres
          val others = async {
            // 5. Autres sites : bibliothèque du compte puis fanfictions suivies non terminées.
            Sites.all.filter { only == null || it.name == only }.map { s -> async {
                val su = users[s.name]
                if (su != null) siteStatus[s.name] = "Synchro en cours…"
                if (su != null) runCatching {
                    syncInfo = "${s.name} · bibliothèque…"; syncProgress = null
                    val l = s.library(su, old)
                    // Résultat anormalement maigre (site qui freine, déconnexion) : on ajoute sans retirer les collections connues
                    val known = old.values.count { it.site == s.name }
                    val trusted = !SyncMode.quick && (known < 20 || l.size >= known / 2)
                    l.forEach { add(it); if (trusted) libIds += it.id }; publish()
                    if (SyncMode.quick) { siteStatus[s.name] = "${l.count { it.id !in old }} nouvelles · à jour"; return@runCatching }
                    if (!trusted) { siteStatus[s.name] = "Incomplet : ${l.size}/$known lues (le site freine), réessai à la prochaine synchro"; return@runCatching }
                    siteStatus[s.name] = "${l.size} fanfictions synchronisées"
                }.onFailure { e -> siteStatus[s.name] = if (old.values.any { it.site == s.name }) "Synchro partielle : ${e.message}" else "Erreur : ${e.message}" }
                // Détails : fiches encore incomplètes (« ?/? ») et fanfictions suivies non terminées.
                fun noSummary(w: Work) = w.summary.isBlank() && old[w.id]?.summary.isNullOrBlank() && !(quick && (old[w.id]?.let { now - it.checked < 3 * 3_600_000L } == true))
                fun slugTitle(w: Work) = badTitle(w.title)
                // La page mobile FF.net n'a pas de résumé (il vient de la liste des favoris) : inutile d'y aller pour ça.
                val wantSummary = true
                val targets = (seen.values.filter { it.site == s.name && (it.chapters.startsWith("?") || (wantSummary && noSummary(it)) || slugTitle(old[it.id] ?: it)) } +
                    old.values.filter { it.site == s.name && it.id !in seen && (full || !it.complete) && !fresh(it) }).distinctBy { it.id }.take(80)
                targets.forEachIndexed { i, w ->
                    syncInfo = "${s.name} · détails ${i + 1}/${targets.size}"; syncProgress = (i + 1f) / targets.size
                    // Détails : complètent la fiche (résumé, chapitres…) sans écraser ce que la bibliothèque a déjà de bon
                    runCatching { s.info(w.link) }.getOrNull()?.let { inf ->
                        val p = seen[w.id]
                        seen[w.id] = if (p == null) inf.copy(sources = w.sources, checked = now) else inf.copy(sources = p.sources, checked = now,
                            title = if (slugTitle(p)) inf.title else p.title, author = p.author.ifBlank { inf.author },
                            summary = inf.summary.ifBlank { p.summary }, cover = p.cover.takeIf { goodCover(it) } ?: inf.cover,
                            fandoms = if (p.fandoms.isEmpty() || p.fandoms == listOf(s.name)) inf.fandoms else p.fandoms,
                            // La fiche de liste (Spirit, FF.net) est plus fiable que la page : on garde ses chiffres
                            words = if (p.words > 0) p.words else inf.words,
                            chapters = if (p.chapters.startsWith("?") || p.chapters.isBlank()) inf.chapters else p.chapters, complete = p.complete || inf.complete)
                    }
                    if (i % 4 == 3) publish()
                }
                publish()
            } }.awaitAll()
          }
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
                            done++; syncInfo = "AO3 · abonnements $done/${todo.size}"; syncProgress = done.toFloat() / todo.size
                            if (done % 8 == 0) publish()
                        }
                    }
                }.awaitAll()
            }
            publish()
            siteStatus["AO3"] = "${seen.values.count { it.site == "AO3" }} fanfictions synchronisées"
          }
            others.await()
            if (only == null) prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
            val mine = works.filter { only == null || it.site == only }
            val nw = mine.count { it.newChapters > 0 }
            syncInfo = (only?.let { "$it · " } ?: "") + "${mine.size} fanfictions · " + if (nw == 0) "tout est à jour" else "$nw avec de nouveaux chapitres"
        } } catch (e: Exception) {
            publish()
            syncInfo = "Synchro interrompue : ${e.message ?: "réseau indisponible"}"
        } finally { syncing = false; syncProgress = null; fillArt(); prefetchCovers() }
    }

    /** Couvertures préchargées en arrière-plan : les étagères s'affichent sans attendre. */
    /** Couvertures illustrées (AniList) pour les fiches AO3 sans image ; 2 requêtes par seconde au plus. */
    private var arting = false
    private var artLog = "jamais lancé"
    /** Version du choix des couvertures : on ne recalcule une histoire que si l'algorithme a changé depuis. */
    private val ART_V = 4
    fun fillArt() {
        if (arting) return; arting = true
        Art.used.clear()
        scope.launch {
            val t0 = System.currentTimeMillis(); var done = 0; var changed = 0; var fail = 0
            val doneIds = if (prefs.getInt("artV", 0) == ART_V) prefs.getStringSet("artDone", emptySet())!!.toMutableSet() else mutableSetOf()
            val todoList = works.filter { it.site == "AO3" && (it.cover.isBlank() || (("anilist" in it.cover || "myanimelist" in it.cover) && it.id !in doneIds)) }
            val todo = todoList.size
            artLog = if (todo == 0) "à jour (rien à recalculer)" else "en cours : 0/$todo"
            val pending = HashMap<String, String>()
            fun apply() { if (pending.isEmpty()) return
                // Une seule mise à jour de la liste par lot : évite de redessiner la bibliothèque à chaque couverture
                var any = false
                for ((id, url) in pending) { val i = works.indexOfFirst { it.id == id }; if (i >= 0 && works[i].cover != url) { works[i] = works[i].copy(cover = url); changed++; any = true } }
                pending.clear(); if (any) persist()
                prefs.edit().putInt("artV", ART_V).putStringSet("artDone", HashSet(doneIds)).apply()
            }
            try {
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                todoList.map { w -> async { gate.withPermit {
                    // Calcul (images, scores) hors du fil de l'interface : l'app reste fluide
                    withContext(Dispatchers.Default) { runCatching { Art.montage(w) ?: Art.forWork(w) } }.onFailure { fail++ }.getOrNull()?.let { pending[w.id] = it }
                    doneIds += w.id; done++
                    if (done % 10 == 0) apply()
                    artLog = "en cours : $done/$todo · $changed changées · ${(System.currentTimeMillis() - t0) / 1000} s"
                } } }.awaitAll()
                apply()
            } finally { arting = false; if (todo > 0) artLog = "dernier passage : $done histoires traitées, $changed couvertures changées, $fail erreurs, ${(System.currentTimeMillis() - t0) / 1000} s" }
        }
    }

    fun prefetchCovers() { scope.launch { Img.prefetch(works.sortedByDescending { it.lastRead }.flatMap { if (it.cover.startsWith("montage:")) it.cover.removePrefix("montage:").split('|') else listOf(it.cover) }) } }

    /** Vrai une seule fois après l'installation d'une nouvelle version. */
    /** Remise à zéro unique des pastilles faussées par les anciennes fiches incorrectes (hors AO3). */
    fun fixBadges() {
        if (prefs.getBoolean("badgesFixed116", false)) { startSync(); return }
        scope.launch {
            awaitLoad()
            works.toList().forEachIndexed { i, w -> if (w.site != "AO3" && w.newChapters > 0) works[i] = w.copy(newChapters = 0) }
            persist(); prefs.edit().putBoolean("badgesFixed116", true).apply()
            sync() // après la remise à zéro, pour ne pas la réécraser
        }
    }
    fun firstRunOfVersion(): Boolean {
        if (prefs.getLong("ranVersion", 0) == version) return false
        prefs.edit().putLong("ranVersion", version).apply(); return true
    }
    fun syncDue(minutes: Int = 20) = (user != null || users.isNotEmpty()) && System.currentTimeMillis() - prefs.getLong("lastSync", 0) > minutes * 60_000L
}

class MainActivity : ComponentActivity() {
    companion object { @Volatile var visible = false }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { super.onStop(); visible = false }
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
        Ext.init(this); Art.init(this)
        hub = Hub(this)
        handle(intent)
        setContent { HubApp(hub) }
        runCatching { hub.prefetchCovers(); hub.fillArt() }
        hub.scope.launch { Ext.updateAll() }
        BackgroundSync.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        if (!::hub.isInitialized) return
        if (::hub.isInitialized) { hub.reload(); hub.startCheck(); hub.trimCache()
            if (hub.firstRunOfVersion()) hub.fixBadges() else if (hub.syncDue(2)) hub.startSync(quick = true) }
    }
    override fun onPause() { super.onPause(); if (::hub.isInitialized) runCatching { hub.flush() } }

    private lateinit var hub: Hub
    private fun handle(i: Intent?) { if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { hub.pendingImport = it } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
}
