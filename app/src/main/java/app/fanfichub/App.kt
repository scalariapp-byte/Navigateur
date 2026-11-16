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

    suspend fun sync() {
        val u = user ?: run { status = "Connecte ton compte AO3 pour synchroniser"; return }
        if (syncing) return
        syncing = true; status = "Synchronisation avec AO3…"
        try {
            val fresh = withContext(Dispatchers.IO) {
                val m = LinkedHashMap<String, Work>()
                fun add(w: Work) { m[w.id] = m[w.id]?.let { it.copy(sources = it.sources + w.sources) } ?: w }
                runCatching { Ao3.list("/users/$u/bookmarks", "Favori") }.getOrDefault(emptyList()).forEach { add(it) }
                runCatching { Ao3.list("/users/$u/readings?show=to-read", "À lire") }.getOrDefault(emptyList()).forEach { add(it) }
                runCatching { Ao3.subscriptionIds(u) }.getOrDefault(emptyList()).forEach { id ->
                    val known = m[id]
                    if (known != null) m[id] = known.copy(sources = known.sources + "Abonnement") else Ao3.work(id, "Abonnement")?.let { add(it) }
                }
                m.values.toList()
            }
            if (fresh.isEmpty()) { status = "Rien trouvé : vérifie la connexion à ton compte"; return }
            val old = works.associateBy { it.id }
            val merged = fresh.map { f ->
                val o = old[f.id] ?: return@map f
                f.copy(newChapters = o.newChapters + maxOf(0, f.published - o.published), progress = o.progress, lastRead = o.lastRead)
            }
            works.clear(); works.addAll(merged); store.save(works)
            val n = merged.count { it.newChapters > 0 }
            status = "${merged.size} fanfictions · " + if (n == 0) "tout est à jour" else "$n avec de nouveaux chapitres"
        } catch (e: Exception) {
            status = "Échec : ${e.message ?: "réseau indisponible"}"
        } finally { syncing = false }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge()
        val hub = Hub(this)
        setContent { HubApp(hub) }
    }
}
