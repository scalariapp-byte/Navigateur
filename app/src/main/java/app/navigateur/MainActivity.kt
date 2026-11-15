package app.navigateur

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PersistableBundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

private const val HOME = "nv:home"
private const val PRIV = "nv_private"

class MainActivity : Activity() {
    private lateinit var s: Store
    private lateinit var addr: EditText
    private lateinit var box: FrameLayout
    private lateinit var prog: ProgressBar
    private lateinit var tabsBtn: TextView
    private lateinit var avatar: TextView
    private val views = HashMap<String, WebView>()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var lastCap = ""
    private val multi by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    private val d get() = s.cur
    private val tab get() = d.tabs.firstOrNull { it.id == d.current }
    private val web get() = tab?.let { views[it.id] }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun host(u: String?) = Uri.parse(u ?: "").host?.removePrefix("www.") ?: ""
    private fun dec(u: String) = runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(u)
    private fun profName(t: Tab) = if (t.priv) PRIV else "nv_" + d.p.id
    private fun gen() = SecureRandom().let { r -> (1..20).map { CHARS[r.nextInt(CHARS.length)] }.joinToString("") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Store(filesDir)
        avatar = TextView(this).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 16f; setOnClickListener { profiles() } }
        addr = EditText(this).apply {
            isSingleLine = true; hint = "Rechercher ou saisir une URL"; setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        tabsBtn = TextView(this).apply { gravity = Gravity.CENTER; textSize = 14f; setTypeface(null, Typeface.BOLD); setOnClickListener { tabs() } }
        val menuBtn = TextView(this).apply { text = "⋮"; textSize = 24f; gravity = Gravity.CENTER; setOnClickListener { menu(it) } }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(8), px(4), px(4), px(4))
            addView(avatar, LinearLayout.LayoutParams(px(34), px(34)))
            addView(addr, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tabsBtn, LinearLayout.LayoutParams(px(32), px(32)))
            addView(menuBtn, LinearLayout.LayoutParams(px(40), px(40)))
        }
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        box = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            addView(top); addView(prog, -1, px(3)); addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        clearPriv()
        if (!multi) toast("WebView trop ancien : profils et onglets privés partagent les cookies. Mettez à jour « Android System WebView ».")
        intent?.dataString?.let { newTab(it) } ?: refresh()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.dataString?.let { newTab(it) } }
    override fun onPause() { super.onPause(); s.save() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { web?.takeIf { it.canGoBack() }?.goBack() ?: super.onBackPressed() }
    @Deprecated("") override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        if (rc == 2) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
        if (rc == 3 && res == RESULT_OK) passwords(true)
    }

    // ---------- Onglets ----------
    private fun refresh() {
        if (d.tabs.isEmpty()) return newTab()
        val t = tab ?: d.tabs.last().also { d.current = it.id }
        val w = views.getOrPut(t.id) { makeWeb(t) }
        if (w.parent != box) { box.removeAllViews(); box.addView(w) }
        addr.setText(if (t.url.startsWith("http")) t.url else "")
        tabsBtn.text = d.tabs.size.toString()
        tabsBtn.background = GradientDrawable().apply { cornerRadius = px(6).toFloat(); setStroke(px(2), d.groups.firstOrNull { it.id == t.group }?.color ?: Color.GRAY) }
        avatar.text = if (t.priv) "🕶" else d.p.name.take(1).uppercase()
        avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (t.priv) Color.DKGRAY else d.p.color) }
    }

    private fun newTab(u: String = HOME, g: String? = tab?.group, priv: Boolean = false) {
        val t = Tab(uid(), u, "Nouvel onglet", g, priv)
        d.tabs += t; d.current = t.id; refresh()
        if (u == HOME) addr.requestFocus()
    }

    private fun closeTab(t: Tab) {
        views.remove(t.id)?.let { box.removeView(it); it.destroy() }
        d.tabs.remove(t)
        if (t.priv && d.tabs.none { it.priv }) clearPriv()
        if (d.current == t.id) d.current = d.tabs.lastOrNull()?.id
        refresh()
    }

    private fun clearPriv() {
        if (multi) ProfileStore.getInstance().getOrCreateProfile(PRIV).run { cookieManager.removeAllCookies(null); webStorage.deleteAllData() }
    }

    private fun go(q: String) {
        val v = q.trim(); if (v.isEmpty()) return
        val u = when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            !v.contains(' ') && v.contains('.') -> "https://$v"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(v, "UTF-8")
        }
        web?.loadUrl(u); addr.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(addr.windowToken, 0)
    }

    private fun homeHtml() = "<meta name=viewport content='width=device-width'><style>body{font-family:sans-serif;padding:16px;color:#222}" +
        "a{display:block;padding:14px;margin:8px 0;background:#f1f3f4;border-radius:12px;color:#222;text-decoration:none}</style>" +
        "<h3>${TextUtils.htmlEncode(d.p.name)}</h3><p style='color:#888'>${TextUtils.htmlEncode(d.p.email)}</p>" +
        d.bookmarks.joinToString("") { "<a href='${TextUtils.htmlEncode(it.url)}'>${TextUtils.htmlEncode(it.title)}</a>" }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(t: Tab) = WebView(this).apply {
        if (multi) WebViewCompat.setProfile(this, profName(t))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true; settings.displayZoomControls = false
        if (t.priv) settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // Retire le marqueur WebView : sinon Google refuse souvent la connexion au compte.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        addJavascriptInterface(Bridge(t), "NV")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (u.startsWith("http")) return false
                runCatching {
                    startActivity(if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                    } else Intent(Intent.ACTION_VIEW, r.url))
                }
                return true
            }
            override fun onPageFinished(v: WebView, u: String) {
                v.evaluateJavascript(JS, null)
                if (!u.startsWith("http")) return
                t.url = u; t.title = v.title?.ifBlank { null } ?: u
                record(t, u)
                if (t.id == d.current && !addr.hasFocus()) addr.setText(u)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (t.id == d.current) { prog.progress = p; prog.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { @Suppress("DEPRECATION") startActivityForResult(p.createIntent(), 2); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        setDownloadListener { u, ua, cd, mime, _ ->
            runCatching {
                val cookie = if (multi) ProfileStore.getInstance().getProfile(profName(t))?.cookieManager?.getCookie(u) else CookieManager.getInstance().getCookie(u)
                val r = DownloadManager.Request(Uri.parse(u)).addRequestHeader("User-Agent", ua).setMimeType(mime)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(u, cd, mime))
                cookie?.let { r.addRequestHeader("Cookie", it) }
                getSystemService(DownloadManager::class.java).enqueue(r); toast("Téléchargement…")
            }.onFailure { toast("Échec du téléchargement") }
        }
        if (t.url == HOME) loadDataWithBaseURL(null, homeHtml(), "text/html", "utf-8", null) else loadUrl(t.url)
    }

    // ---------- Historique avec exclusions ----------
    private fun excluded(u: String, title: String = "") = dec(u).let { x -> d.exclusions.any { x.contains(it, true) || title.contains(it, true) } }

    private fun record(t: Tab, u: String) {
        if (t.priv || excluded(u, t.title) || d.history.firstOrNull()?.url == u) return
        d.history.add(0, Entry(t.title, u))
        if (d.history.size > 3000) d.history.removeAt(d.history.lastIndex)
    }

    private fun addExclusion(v: String) {
        if (v.isEmpty()) return
        d.exclusions += v.lowercase()
        d.history.removeAll { excluded(it.url, it.title) }
        toast("« $v » ne sera plus enregistré")
    }

    private fun exclusions() {
        val b = AlertDialog.Builder(this).setTitle("Jamais dans l'historique (toucher = retirer)")
            .setItems(d.exclusions.toTypedArray()) { _, i -> d.exclusions.removeAt(i); exclusions() }
            .setPositiveButton("+ Ajouter") { _, _ -> input("Site ou mot-clé", "ex : youtube.com ou cadeau" to "") { (v) -> addExclusion(v); exclusions() } }
            .setNegativeButton("Fermer", null)
        if (d.exclusions.isEmpty()) b.setMessage("Aucune règle. Les sites et recherches contenant ces mots ne seront jamais enregistrés (et sont effacés de l'historique existant).")
        b.show()
    }

    // ---------- Menus / dialogues ----------
    private fun menu(v: View) {
        val m = listOf<Pair<String, () -> Any?>>(
            "Nouvel onglet" to { newTab() },
            "Nouvel onglet privé" to { newTab(priv = true) },
            "☆ Ajouter aux favoris" to { addBookmark() },
            "Favoris" to { entries("Favoris", d.bookmarks) },
            "Historique" to { entries("Historique", d.history, "Tout effacer" to { d.history.clear() }) },
            "🚫 Ne jamais enregistrer ce site" to { addExclusion(host(tab?.url)) },
            "Règles d'exclusion" to { exclusions() },
            "🔑 Remplir le mot de passe" to { fill() },
            "Mots de passe" to { passwords() },
            "Profils" to { profiles() },
            "Partager" to { tab?.url?.let { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), null)) } },
            "Avancer" to { web?.goForward() },
            "Recharger" to { web?.reload() })
        PopupMenu(this, v).apply {
            m.forEachIndexed { i, (n, _) -> menu.add(0, i, i, n) }
            setOnMenuItemClickListener { m[it.itemId].second(); true }
        }.show()
    }

    private fun pick(title: String, items: List<String>, f: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> f(i) }.show()
    }

    private fun confirm(msg: String, f: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK") { _, _ -> f() }.setNegativeButton("Annuler", null).show()
    }

    private fun input(title: String, vararg fields: Pair<String, String>, f: (List<String>) -> Unit) {
        val es = fields.map { (h, v) -> EditText(this).apply { hint = h; setText(v); isSingleLine = true } }
        AlertDialog.Builder(this).setTitle(title)
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0); es.forEach { addView(it) } })
            .setPositiveButton("OK") { _, _ -> f(es.map { it.text.toString().trim() }) }.setNegativeButton("Annuler", null).show()
    }

    private fun copy(v: String) {
        val clip = ClipData.newPlainText("", v).apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); toast("Copié")
    }

    // ---------- Gestionnaire d'onglets et groupes ----------
    private fun tabs() {
        lateinit var dlg: AlertDialog
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(4), px(16), px(4)) }
        fun section(g: Group?) {
            val ts = d.tabs.filter { it.group == g?.id }
            if (g == null && ts.isEmpty()) return
            list.addView(TextView(this).apply {
                text = "${g?.name ?: "Sans groupe"}  (${ts.size})" + if (g != null) "  ⋯" else ""
                setTextColor(g?.color ?: Color.GRAY); textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, px(14), 0, px(4))
                if (g != null) setOnClickListener { dlg.dismiss(); groupMenu(g) }
            })
            ts.forEach { t -> list.addView(row(t) { dlg.dismiss() }) }
        }
        d.groups.forEach { section(it) }
        section(null)
        dlg = AlertDialog.Builder(this).setTitle("Onglets — ${d.p.name}").setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("+ Onglet") { _, _ -> newTab(g = null) }
            .setNeutralButton("+ Groupe") { _, _ -> newGroup() }
            .setNegativeButton("🕶 Privé") { _, _ -> newTab(priv = true) }.show()
    }

    private fun row(t: Tab, done: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = (if (t.priv) "🕶 " else "") + t.title.ifBlank { t.url }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 15f; setPadding(0, px(10), 0, px(10))
            if (t.id == d.current) setTypeface(null, Typeface.BOLD)
            setOnClickListener { d.current = t.id; refresh(); done() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = "⇄"; textSize = 18f; setPadding(px(14), 0, px(10), 0); setOnClickListener { done(); moveTab(t) } })
        addView(TextView(context).apply { text = "✕"; textSize = 18f; setPadding(px(10), 0, 0, 0); setOnClickListener { done(); closeTab(t); tabs() } })
    }

    private fun moveTab(t: Tab) = pick("Déplacer vers", d.groups.map { it.name } + "Sans groupe" + "+ Nouveau groupe") { i ->
        when {
            i < d.groups.size -> { t.group = d.groups[i].id; refresh(); tabs() }
            i == d.groups.size -> { t.group = null; refresh(); tabs() }
            else -> newGroup { g -> t.group = g.id; refresh(); tabs() }
        }
    }

    private fun newGroup(after: (Group) -> Unit = { newTab(g = it.id) }) = input("Nouveau groupe", "Nom (ex : Travail)" to "") { (n) ->
        if (n.isNotEmpty()) Group(uid(), n, COLORS[d.groups.size % COLORS.size]).also { d.groups += it; after(it) }
    }

    private fun groupMenu(g: Group) = pick(g.name, listOf("Nouvel onglet dans ce groupe", "Renommer", "Changer de couleur", "Fermer le groupe et ses onglets", "Dissocier (garder les onglets)")) {
        when (it) {
            0 -> newTab(g = g.id)
            1 -> input("Renommer", "Nom" to g.name) { (n) -> if (n.isNotEmpty()) g.name = n; tabs() }
            2 -> { g.color = COLORS[(COLORS.indexOf(g.color) + 1) % COLORS.size]; refresh(); tabs() }
            3 -> confirm("Fermer ${g.name} et ses onglets ?") { d.groups.remove(g); d.tabs.filter { t -> t.group == g.id }.forEach { t -> closeTab(t) } }
            4 -> { d.tabs.filter { t -> t.group == g.id }.forEach { t -> t.group = null }; d.groups.remove(g); refresh(); tabs() }
            else -> {}
        }
    }

    // ---------- Favoris / historique ----------
    private fun addBookmark() {
        val t = tab?.takeIf { it.url.startsWith("http") } ?: return toast("Rien à ajouter")
        input("Ajouter aux favoris", "Titre" to t.title, "URL" to t.url) { (n, u) ->
            if (u.startsWith("http")) { d.bookmarks.removeAll { it.url == u }; d.bookmarks += Entry(n.ifEmpty { u }, u); toast("Ajouté") }
        }
    }

    private fun entries(title: String, list: MutableList<Entry>, extra: Pair<String, () -> Unit>? = null) {
        val shown = list.take(300)
        val b = AlertDialog.Builder(this).setTitle(title)
            .setItems(shown.map { it.title + "\n" + it.url }.toTypedArray()) { _, i ->
                val e = shown[i]
                pick(e.title, listOf("Ouvrir", "Ouvrir dans un nouvel onglet", "Supprimer")) {
                    when (it) { 0 -> web?.loadUrl(e.url); 1 -> newTab(e.url); 2 -> list.remove(e); else -> {} }
                }
            }.setNegativeButton("Fermer", null)
        if (list.isEmpty()) b.setMessage("Vide")
        extra?.let { (n, f) -> b.setNeutralButton(n) { _, _ -> confirm("$n ?", f) } }
        b.show()
    }

    // ---------- Profils ----------
    private fun profiles() {
        val ps = s.profiles
        AlertDialog.Builder(this).setTitle("Profils")
            .setItems(ps.map { (if (it.id == d.p.id) "● " else "○ ") + it.name + if (it.email.isNotEmpty()) "\n   " + it.email else "" }.toTypedArray()) { _, i ->
                val p = ps[i]
                pick(p.name, listOf("Utiliser", "Modifier", "Copier l'e-mail", "Supprimer")) {
                    when (it) {
                        0 -> switchTo(p)
                        1 -> input("Modifier", "Nom" to p.name, "E-mail" to p.email) { (n, e) -> if (n.isNotEmpty()) p.name = n; p.email = e; s.save(); refresh() }
                        2 -> copy(p.email)
                        3 -> if (p.id == d.p.id) toast("Impossible : profil actif") else confirm("Supprimer ${p.name} et toutes ses données ?") {
                            s.delete(p)
                            if (multi) runCatching { ProfileStore.getInstance().deleteProfile("nv_" + p.id) }
                        }
                        else -> {}
                    }
                }
            }
            .setPositiveButton("+ Nouveau profil") { _, _ ->
                input("Nouveau profil", "Nom (ex : Travail)" to "", "Adresse e-mail" to "") { (n, e) ->
                    if (n.isNotEmpty()) Profile(uid(), n, e, COLORS[ps.size % COLORS.size]).also { ps += it; switchTo(it) }
                }
            }.show()
    }

    private fun switchTo(p: Profile) {
        if (p.id == d.p.id) return
        views.values.forEach { box.removeView(it); it.destroy() }
        views.clear()
        s.switch(p); refresh(); toast("Profil : ${p.name}")
    }

    // ---------- Mots de passe ----------
    private fun passwords(authed: Boolean = false) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!authed && km.isDeviceSecure) {
            @Suppress("DEPRECATION")
            return startActivityForResult(km.createConfirmDeviceCredentialIntent("Mots de passe", d.p.name), 3)
        }
        val c = d.creds.sortedBy { it.host }
        val b = AlertDialog.Builder(this).setTitle("Mots de passe — ${d.p.name}")
            .setItems(c.map { "${it.host}\n${it.user}" }.toTypedArray()) { _, i ->
                val e = c[i]
                pick(e.host, listOf("Copier le mot de passe", "Copier l'identifiant", "Afficher", "Modifier", "Supprimer")) {
                    when (it) {
                        0 -> copy(e.pass); 1 -> copy(e.user)
                        2 -> AlertDialog.Builder(this).setTitle(e.user).setMessage(e.pass).show()
                        3 -> editCred(e)
                        4 -> confirm("Supprimer ${e.host} ?") { d.creds.remove(e); d.saveCreds() }
                        else -> {}
                    }
                }
            }
            .setPositiveButton("+ Ajouter") { _, _ -> editCred(null) }.setNegativeButton("Fermer", null)
        if (c.isEmpty()) b.setMessage("Aucun mot de passe. Ils vous sont proposés à l'enregistrement quand vous vous connectez à un site.")
        b.show()
    }

    private fun editCred(e: Cred?) = input(if (e == null) "Nouveau mot de passe" else "Modifier",
        "Site (ex : github.com)" to (e?.host ?: host(tab?.url)), "Identifiant" to (e?.user ?: d.p.email),
        "Mot de passe (vide = générer)" to (e?.pass ?: "")) { (h, u, p) ->
        val hh = host(if (h.contains("://")) h else "https://$h")
        if (hh.isEmpty()) return@input toast("Site invalide")
        e?.let { d.creds.remove(it) }
        d.creds.removeAll { it.host == hh && it.user == u }
        d.creds += Cred(hh, u, p.ifEmpty { gen() }); d.saveCreds(); toast("Enregistré")
    }

    private fun fill() {
        val h = host(web?.url); val c = d.creds.filter { it.host == h }
        when {
            c.isEmpty() -> toast("Aucun mot de passe pour $h")
            c.size == 1 -> inject(c[0])
            else -> pick("Compte", c.map { it.user }) { inject(c[it]) }
        }
    }

    private fun inject(c: Cred) {
        if (host(web?.url) == c.host) web?.evaluateJavascript("nvFill(${JSONObject.quote(c.user)},${JSONObject.quote(c.pass)})", null)
    }

    /** Seule méthode exposée aux pages : proposer un identifiant à enregistrer (jamais de lecture). */
    inner class Bridge(private val t: Tab) {
        @JavascriptInterface
        fun captured(user: String, pass: String) {
            runOnUiThread {
                val h = host(views[t.id]?.url)
                val k = "$h|$user|$pass"
                if (t.priv || pass.isEmpty() || h.isEmpty() || k == lastCap || d.creds.any { "${it.host}|${it.user}|${it.pass}" == k }) return@runOnUiThread
                lastCap = k
                AlertDialog.Builder(this@MainActivity).setTitle("Enregistrer le mot de passe ?")
                    .setMessage("$h\n${user.ifEmpty { "(sans identifiant)" }}\nProfil : ${d.p.name}")
                    .setPositiveButton("Enregistrer") { _, _ -> d.creds.removeAll { it.host == h && it.user == user }; d.creds += Cred(h, user, pass); d.saveCreds() }
                    .setNegativeButton("Non", null).show()
            }
        }
    }

    companion object {
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*-_+=?"
        private const val JS = """(function(){if(window.nvOk)return;window.nvOk=1;
var vis=function(e){return e&&e.offsetParent!==null&&!e.disabled};
var pws=function(r){return [].slice.call(r.querySelectorAll('input[type=password]')).filter(vis)};
var users=function(r){return [].slice.call(r.querySelectorAll('input[autocomplete=username],input[type=email],input[type=text],input[type=tel],input:not([type])')).filter(vis)};
var before=function(r,p){return users(r).filter(function(i){return p.compareDocumentPosition(i)&2}).pop()};
function grab(s){var p=pws(s).filter(function(i){return i.value}).pop();
 if(!p){var u=users(s).filter(function(i){return i.value})[0];if(u)sessionStorage.nvU=u.value;return}
 var u=before(p.form||document,p);NV.captured(u?u.value:(sessionStorage.nvU||''),p.value)}
document.addEventListener('submit',function(e){grab(e.target)},true);
document.addEventListener('keydown',function(e){if(e.key==='Enter'&&e.target.tagName==='INPUT')grab(e.target.form||document)},true);
document.addEventListener('click',function(e){var b=e.target.closest&&e.target.closest('button,input[type=submit],[role=button]');if(b)grab(b.form||document)},true);
function set(el,v){if(!el)return;el.focus();Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);
 el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))}
window.nvFill=function(u,p){var pw=pws(document)[0];set(pw?before(pw.form||document,pw):users(document)[0],u);set(pw,p)};})()"""
    }
}
