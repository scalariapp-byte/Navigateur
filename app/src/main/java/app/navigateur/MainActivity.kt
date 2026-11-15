package app.navigateur

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PersistableBundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Calendar

private const val HOME = "nv:home"
private const val PRIV = "nv_private"

class MainActivity : Activity() {
    private lateinit var s: Store
    private lateinit var drawer: DrawerLayout
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var addr: EditText
    private lateinit var lockIc: ImageView
    private lateinit var avatarBox: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var prog: ProgressBar
    private lateinit var tabsBtn: TextView
    private val views = HashMap<String, WebView>()
    private val icons = HashMap<String, Bitmap?>()
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var lastCap = ""
    private var pendingPhoto: String? = null
    private val multi by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
    private val icDir by lazy { File(filesDir, "ic").apply { mkdirs() } }
    private val prefs by lazy { getSharedPreferences("nv", MODE_PRIVATE) }
    private var darkSites: Boolean
        get() = prefs.getBoolean("dark", true)
        set(v) { prefs.edit().putBoolean("dark", v).apply() }
    private val d get() = s.cur
    private val tab get() = d.tabs.firstOrNull { it.id == d.current }
    private val web get() = tab?.let { views[it.id] }
    private val accent get() = d.p.color

    // ---------- Petits utilitaires UI ----------
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun host(u: String?) = Uri.parse(u ?: "").host?.removePrefix("www.") ?: ""
    private fun dec(u: String) = runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(u)
    private fun esc(v: String) = TextUtils.htmlEncode(v)
    private fun profName(t: Tab) = if (t.priv) PRIV else "nv_" + d.p.id
    private fun gen() = SecureRandom().let { r -> (1..20).map { CHARS[r.nextInt(CHARS.length)] }.joinToString("") }
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
    private fun rr(c: Int, r: Int, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
        setColor(c); cornerRadius = px(r).toFloat(); if (stroke > 0) setStroke(px(stroke), sc)
    }
    private fun ripple(bg: Drawable?, mask: Drawable? = bg) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, mask)
    private fun txt(v: CharSequence, size: Float = 15f, color: Int = TXT, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun icon(path: String, color: Int = TXT, pad: Int = 12, f: (() -> Unit)? = null) = ImageView(this).apply {
        setImageDrawable(Ic(path, color)); setPadding(px(pad), px(pad), px(pad), px(pad))
        if (f != null) { background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null); setOnClickListener { f() } }
    }
    private fun ic(path: String, color: Int = TXT) = icon(path, color, 8).apply { background = circle(SURF2) }
    private fun dot(c: Int) = FrameLayout(this).apply { addView(View(context).apply { background = circle(c) }, FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER)) }

    private fun avatarView(p: Profile, dp: Int, priv: Boolean = false): View {
        val f = File(filesDir, "av_${p.id}.jpg")
        val bmp = if (!priv && f.exists()) BitmapFactory.decodeFile(f.path) else null
        val v: View = when {
            bmp != null -> ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }
            priv -> icon(I.PRIVATE, TXT, dp / 5)
            else -> txt(p.name.take(1).uppercase(), dp * 0.4f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        }
        v.background = circle(if (priv) 0xFF3A3F4A.toInt() else p.color)
        v.outlineProvider = ViewOutlineProvider.BACKGROUND; v.clipToOutline = true
        v.layoutParams = lp(px(dp), px(dp))
        return v
    }

    private fun favicon(h: String): Bitmap? = icons.getOrPut(h) { File(icDir, "$h.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    private fun siteIcon(h: String, dp: Int): View {
        val b = favicon(h)
        val v: View = if (b != null) ImageView(this).apply { setImageBitmap(b); val p = dp / 5; setPadding(px(p), px(p), px(p), px(p)); background = circle(SURF2) }
        else txt(h.take(1).uppercase().ifEmpty { "•" }, dp * 0.38f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = circle(COLORS[Math.floorMod(h.hashCode(), COLORS.size)])
        }
        v.layoutParams = lp(px(dp), px(dp)); return v
    }

    // ---------- Fenêtres modernes (bas d'écran) ----------
    inner class Sheet(title: String, private val build: Sheet.() -> Unit) {
        private val dlg = Dialog(this@MainActivity)
        private val chips = LinearLayout(this@MainActivity)
        private val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        init {
            val r = px(28).toFloat()
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(14), px(10), px(14), px(18))
                background = GradientDrawable().apply { setColor(SURF); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
                addView(View(context).apply { background = rr(0xFF3A3F4A.toInt(), 2) }, lp(px(36), px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(14) })
                addView(txt(title, 19f, TXT, true).apply { setPadding(px(8), 0, 0, px(10)) })
                addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
                addView(ScrollView(context).apply { addView(list) })
            }
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dlg.setContentView(col)
            reload(); dlg.show()
            dlg.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -2); setGravity(Gravity.BOTTOM)
                setWindowAnimations(android.R.style.Animation_InputMethod); setDimAmount(0.6f)
            }
        }
        fun reload() { chips.removeAllViews(); list.removeAllViews(); build(); chips.visibility = if (chips.childCount > 0) View.VISIBLE else View.GONE }
        fun close() = dlg.dismiss()
        fun chip(label: String, path: String, f: () -> Unit) {
            chips.addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(12), px(8), px(16), px(8)); background = ripple(rr(SURF2, 20))
                addView(ImageView(context).apply { setImageDrawable(Ic(path, accent)) }, lp(px(18), px(18)))
                addView(txt(label, 13.5f).apply { setPadding(px(8), 0, 0, 0) })
                setOnClickListener { f() }
            }, lp(-2, -2).apply { rightMargin = px(8); bottomMargin = px(10); if (chips.childCount == 0) leftMargin = px(4) })
        }
        fun header(label: String, color: Int, more: (() -> Unit)? = null) {
            list.addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(10), px(12), 0, px(2))
                addView(View(context).apply { background = circle(color) }, lp(px(10), px(10)))
                addView(txt(label, 13f, color, true).apply { setPadding(px(10), 0, 0, 0) }, lp(0, -2, 1f))
                if (more != null) addView(icon(I.MORE, MUT, 8, more), lp(px(36), px(36)))
            })
        }
        fun row(lead: View?, title: String, sub: String? = null, bold: Boolean = false, color: Int = TXT,
                acts: List<Pair<String, () -> Unit>> = emptyList(), click: (() -> Unit)? = null) {
            list.addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(56); setPadding(px(8), px(6), px(4), px(6))
                if (click != null) { background = ripple(null, rr(Color.WHITE, 16)); setOnClickListener { click() } }
                if (bold) background = ripple(rr(SURF2, 16))
                lead?.let { val l = (it.layoutParams as? LinearLayout.LayoutParams) ?: lp(px(36), px(36)); l.rightMargin = px(14); addView(it, l) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(txt(title, 15f, color, bold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    sub?.let { addView(txt(it, 12.5f, MUT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
                }, lp(0, -2, 1f))
                acts.forEach { (p, f) -> addView(icon(p, MUT, 9, f), lp(px(40), px(40))) }
            })
        }
        fun empty(msg: String) { list.addView(txt(msg, 14f, MUT).apply { setPadding(px(10), px(16), px(10), px(16)) }) }
    }
    private fun sheet(title: String, build: Sheet.() -> Unit) { Sheet(title, build) }

    private fun ask(title: String, vararg fields: Pair<String, String>, msg: String? = null, ok: String = "OK", f: (List<String>) -> Unit) {
        val dlg = Dialog(this)
        val es = fields.map { (h, v) ->
            EditText(this).apply {
                hint = h; setText(v); isSingleLine = true; setTextColor(TXT); setHintTextColor(MUT); textSize = 15f
                background = rr(SURF2, 14); setPadding(px(14), px(12), px(14), px(12))
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(22), px(22), px(22), px(14)); background = rr(SURF, 26)
            addView(txt(title, 18f, TXT, true).apply { setPadding(0, 0, 0, px(12)) })
            msg?.let { addView(txt(it, 14f, MUT).apply { setPadding(0, 0, 0, px(12)) }) }
            es.forEach { addView(it, lp(-1, -2).apply { bottomMargin = px(10) }) }
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(txt("Annuler", 15f, MUT, true).apply { setPadding(px(16), px(10), px(16), px(10)); setOnClickListener { dlg.dismiss() } })
                addView(txt(ok, 15f, Color.WHITE, true).apply {
                    setPadding(px(22), px(10), px(22), px(10)); background = ripple(rr(accent, 22))
                    setOnClickListener { dlg.dismiss(); f(es.map { e -> e.text.toString().trim() }) }
                })
            })
        }
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE); dlg.setContentView(col); dlg.show()
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(resources.displayMetrics.widthPixels - px(36), -2)
            if (es.isNotEmpty()) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        es.firstOrNull()?.requestFocus()
    }
    private fun confirm(msg: String, f: () -> Unit) = ask(msg, ok = "Confirmer") { f() }

    private fun copy(v: String) {
        val clip = ClipData.newPlainText("", v).apply { description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) } }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); toast("Copié")
    }

    // ---------- Construction de l'écran ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        s = Store(filesDir)
        window.statusBarColor = BG; window.navigationBarColor = SURF
        avatarBox = FrameLayout(this).apply { setOnClickListener { profiles() } }
        lockIc = ImageView(this)
        addr = EditText(this).apply {
            background = null; isSingleLine = true; hint = "Rechercher ou saisir une adresse"; textSize = 15f
            setTextColor(TXT); setHintTextColor(MUT); setSelectAllOnFocus(true); setPadding(px(10), 0, px(4), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
            setOnFocusChangeListener { _, has -> showAddr(has) }
        }
        val pill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = rr(SURF2, 24); setPadding(px(14), 0, px(2), 0)
            addView(lockIc, lp(px(17), px(17)))
            addView(addr, lp(0, -1, 1f))
            addView(icon(I.REFRESH, MUT, 11) { web?.reload() }, lp(px(42), px(42)))
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(12), px(8), px(12), px(8)); setBackgroundColor(BG)
            addView(avatarBox, lp(px(40), px(40)).apply { rightMargin = px(10) })
            addView(pill, lp(0, px(46), 1f))
        }
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressBackgroundTintList = ColorStateList.valueOf(BG)
        }
        box = FrameLayout(this).apply { setBackgroundColor(BG) }
        val r = px(16).toFloat()
        val handle = ImageView(this).apply {
            setImageDrawable(Ic(I.STAR, TXT)); setPadding(px(5), px(24), px(3), px(24))
            background = GradientDrawable().apply { setColor(0xCC22262E.toInt()); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) }
            setOnClickListener { drawer.openDrawer(Gravity.END) }
        }
        val stage = FrameLayout(this).apply {
            addView(box, -1, -1)
            addView(handle, FrameLayout.LayoutParams(px(24), px(72), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        tabsBtn = txt("", 12f, TXT, true).apply { gravity = Gravity.CENTER }
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(SURF); setPadding(px(4), 0, px(4), 0)
            fun slot(v: View) = addView(v, lp(0, px(56), 1f))
            slot(icon(I.MENU, TXT, 16) { drawer.openDrawer(Gravity.START) })
            slot(icon(I.BACK, TXT, 16) { web?.takeIf { it.canGoBack() }?.goBack() })
            slot(icon(I.FWD, TXT, 16) { web?.takeIf { it.canGoForward() }?.goForward() })
            slot(icon(I.HOME, TXT, 16) { goHome() })
            slot(FrameLayout(context).apply {
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                addView(tabsBtn, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER)); setOnClickListener { tabs() }
            })
            slot(icon(I.STAR, TXT, 16) { drawer.openDrawer(Gravity.END) })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(BG)
            addView(top); addView(prog, -1, px(2)); addView(stage, lp(-1, 0, 1f)); addView(bottom)
        }
        fun panel(col: LinearLayout, left: Boolean) = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(SURF); cornerRadii = if (left) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            col.orientation = LinearLayout.VERTICAL; col.setPadding(0, 0, 0, px(24)); addView(col)
        }
        leftCol = LinearLayout(this); rightCol = LinearLayout(this)
        drawer = DrawerLayout(this).apply {
            setScrimColor(0x99000000.toInt())
            addView(content, DrawerLayout.LayoutParams(-1, -1))
            addView(panel(leftCol, true), DrawerLayout.LayoutParams(px(304), -1, Gravity.START))
            addView(panel(rightCol, false), DrawerLayout.LayoutParams(px(304), -1, Gravity.END))
        }
        setContentView(drawer)
        clearPriv()
        if (!multi) toast("WebView trop ancien : les profils partagent les cookies. Mettez à jour « Android System WebView ».")
        intent?.dataString?.let { newTab(it) } ?: refresh()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.dataString?.let { newTab(it) } }
    override fun onPause() { super.onPause(); s.save() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(Gravity.START) || drawer.isDrawerOpen(Gravity.END)) drawer.closeDrawers()
        else web?.takeIf { it.canGoBack() }?.goBack() ?: super.onBackPressed()
    }
    @Deprecated("") override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        if (rc == 2) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
        if (rc == 3 && res == RESULT_OK) passwords(true)
        if (rc == 4 && res == RESULT_OK) data?.data?.let { savePhoto(it) }
    }

    private fun refresh() {
        if (d.tabs.isEmpty()) return newTab()
        val t = tab ?: d.tabs.last().also { d.current = it.id }
        val w = views.getOrPut(t.id) { makeWeb(t) }
        if (w.parent != box) { box.removeAllViews(); box.addView(w) }
        showAddr(addr.hasFocus())
        tabsBtn.text = d.tabs.size.toString()
        tabsBtn.background = rr(Color.TRANSPARENT, 7, 2, d.groups.firstOrNull { it.id == t.group }?.color ?: TXT)
        avatarBox.removeAllViews(); avatarBox.addView(avatarView(d.p, 40, t.priv))
        prog.progressTintList = ColorStateList.valueOf(accent)
        buildLeft(); buildRight()
    }

    private fun showAddr(focus: Boolean) {
        val t = tab ?: return
        addr.setText(if (!t.url.startsWith("http")) "" else if (focus) t.url else host(t.url))
        if (focus) addr.selectAll()
        lockIc.setImageDrawable(Ic(when { t.priv -> I.PRIVATE; t.url.startsWith("https") -> I.LOCK; else -> I.SEARCH }, if (t.priv) accent else MUT))
    }

    // ---------- Tiroir gauche : actions ----------
    private fun buildLeft() {
        leftCol.removeAllViews()
        leftCol.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(20), px(32), px(16), px(20)); background = ripple(null, ColorDrawable(Color.WHITE))
            setOnClickListener { drawer.closeDrawers(); profiles() }
            addView(avatarView(d.p, 54))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, px(8), 0)
                addView(txt(d.p.name, 18f, TXT, true).apply { maxLines = 1 })
                addView(txt(d.p.email.ifEmpty { "Changer de profil" }, 13f, MUT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, lp(0, -2, 1f))
            addView(ImageView(context).apply { setImageDrawable(Ic(I.SWAP, MUT)) }, lp(px(22), px(22)))
        })
        leftCol.addView(View(this).apply { setBackgroundColor(SURF2) }, lp(-1, px(1)).apply { bottomMargin = px(8) })
        fun item(path: String, label: String, sub: String? = null, f: () -> Unit) {
            leftCol.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(22), px(13), px(16), px(13)); background = ripple(null, ColorDrawable(Color.WHITE))
                setOnClickListener { drawer.closeDrawers(); f() }
                addView(ImageView(context).apply { setImageDrawable(Ic(path, TXT)) }, lp(px(22), px(22)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(px(20), 0, 0, 0)
                    addView(txt(label, 15f)); sub?.let { addView(txt(it, 12f, MUT)) }
                }, lp(0, -2, 1f))
            })
        }
        item(I.ADD, "Nouvel onglet") { newTab() }
        item(I.PRIVATE, "Onglet privé", "Aucune trace") { newTab(priv = true) }
        item(I.TABS, "Onglets et groupes", "${d.tabs.size} onglet(s) · ${d.groups.size} groupe(s)") { tabs() }
        item(I.HISTORY, "Historique") { history() }
        item(I.KEY, "Mots de passe", "${d.creds.size} enregistré(s)") { passwords() }
        item(I.PERSON, "Remplir l'identifiant", host(tab?.url).ifEmpty { null }) { fill() }
        item(I.BLOCK, "Ne jamais enregistrer ce site") { addExclusion(host(tab?.url)) }
        item(I.EDIT, "Règles d'exclusion", "${d.exclusions.size} règle(s)") { exclusions() }
        item(I.SHARE, "Partager la page") { share() }
        item(I.MOON, "Sites en mode sombre", if (darkSites) "Activé" else "Désactivé") { darkSites = !darkSites; views.values.forEach { applyDark(it) }; buildLeft() }
    }

    // ---------- Tiroir droit : favoris ----------
    private fun buildRight() {
        rightCol.removeAllViews()
        rightCol.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(22), px(32), px(10), px(12))
            addView(txt("Favoris", 21f, TXT, true), lp(0, -2, 1f))
            addView(icon(I.ADD, accent, 10) { addBookmark() }.apply { background = ripple(circle(SURF2)) }, lp(px(42), px(42)))
        })
        if (d.bookmarks.isEmpty()) {
            rightCol.addView(txt("Aucun favori.\nAppuyez sur + pour ajouter la page ouverte.", 14f, MUT).apply { setPadding(px(22), px(8), px(22), 0) })
            return
        }
        d.bookmarks.chunked(3).forEach { items ->
            rightCol.addView(LinearLayout(this).apply {
                setPadding(px(10), 0, px(10), 0)
                items.forEach { e ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(4), px(12), px(4), px(12))
                        background = ripple(null, rr(Color.WHITE, 18))
                        addView(siteIcon(host(e.url), 50))
                        addView(txt(e.title, 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, px(7), 0, 0) }, lp(-1, -2))
                        setOnClickListener { drawer.closeDrawers(); open(e.url) }
                        setOnLongClickListener { bookmarkMenu(e); true }
                    }, lp(0, -2, 1f))
                }
                repeat(3 - items.size) { addView(View(context), lp(0, 1, 1f)) }
            })
        }
        rightCol.addView(txt("Appui long sur un favori pour le modifier", 12f, MUT).apply { gravity = Gravity.CENTER; setPadding(0, px(14), 0, 0) }, lp(-1, -2))
    }

    private fun bookmarkMenu(e: Entry) {
        sheet(e.title) {
            row(ic(I.OPEN), "Ouvrir dans un nouvel onglet") { close(); drawer.closeDrawers(); newTab(e.url) }
            row(ic(I.EDIT), "Modifier") {
                close(); ask("Modifier le favori", "Titre" to e.title, "URL" to e.url) { (n, u) -> if (u.startsWith("http")) { e.title = n.ifEmpty { u }; e.url = u; buildRight() } }
            }
            row(ic(I.DELETE, RED), "Supprimer", color = RED) { close(); d.bookmarks.remove(e); buildRight() }
        }
    }

    private fun addBookmark() {
        val t = tab?.takeIf { it.url.startsWith("http") } ?: return toast("Ouvrez d'abord une page")
        ask("Ajouter aux favoris", "Titre" to t.title, "URL" to t.url) { (n, u) ->
            if (u.startsWith("http")) { d.bookmarks.removeAll { it.url == u }; d.bookmarks += Entry(n.ifEmpty { u }, u); buildRight(); toast("Ajouté aux favoris") }
        }
    }

    // ---------- Onglets et groupes ----------
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

    private fun tabs() {
        sheet("Onglets · ${d.p.name}") {
            chip("Onglet", I.ADD) { close(); newTab(g = null) }
            chip("Groupe", I.FOLDER) { close(); newGroup() }
            chip("Privé", I.PRIVATE) { close(); newTab(priv = true) }
            fun section(g: Group?) {
                val ts = d.tabs.filter { it.group == g?.id }
                if (g == null && ts.isEmpty()) return
                header("${g?.name ?: "Sans groupe"} · ${ts.size}", g?.color ?: MUT, g?.let { gg -> { close(); groupMenu(gg) } })
                ts.forEach { t ->
                    row(if (t.priv) ic(I.PRIVATE) else siteIcon(host(t.url), 36), t.title.ifBlank { "Nouvel onglet" },
                        if (t.url.startsWith("http")) host(t.url) else null, bold = t.id == d.current,
                        acts = listOf(I.SWAP to { close(); moveTab(t) }, I.CLOSE to { closeTab(t); reload() })) { close(); d.current = t.id; refresh() }
                }
            }
            d.groups.forEach { section(it) }
            section(null)
        }
    }

    private fun moveTab(t: Tab) {
        sheet("Déplacer vers…") {
            d.groups.forEach { g -> row(dot(g.color), g.name, bold = g.id == t.group) { close(); t.group = g.id; refresh(); tabs() } }
            row(dot(MUT), "Sans groupe", bold = t.group == null) { close(); t.group = null; refresh(); tabs() }
            row(ic(I.ADD, accent), "Nouveau groupe…") { close(); newGroup { g -> t.group = g.id; refresh(); tabs() } }
        }
    }

    private fun newGroup(after: (Group) -> Unit = { newTab(g = it.id) }) = ask("Nouveau groupe", "Nom (ex : Travail)" to "") { (n) ->
        if (n.isNotEmpty()) Group(uid(), n, COLORS[d.groups.size % COLORS.size]).also { d.groups += it; after(it) }
    }

    private fun groupMenu(g: Group) {
        sheet(g.name) {
            row(ic(I.ADD), "Nouvel onglet dans ce groupe") { close(); newTab(g = g.id) }
            row(ic(I.EDIT), "Renommer") { close(); ask("Renommer le groupe", "Nom" to g.name) { (n) -> if (n.isNotEmpty()) g.name = n; tabs() } }
            row(dot(g.color), "Changer de couleur") { g.color = COLORS[(COLORS.indexOf(g.color) + 1) % COLORS.size]; refresh(); reload() }
            row(ic(I.FOLDER), "Dissocier", "Garde les onglets") { close(); d.tabs.filter { it.group == g.id }.forEach { it.group = null }; d.groups.remove(g); refresh(); tabs() }
            row(ic(I.CLOSE, RED), "Fermer le groupe et ses onglets", color = RED) {
                confirm("Fermer ${g.name} et ses onglets ?") { close(); d.groups.remove(g); d.tabs.filter { it.group == g.id }.forEach { closeTab(it) } }
            }
        }
    }

    private fun go(q: String) {
        val v = q.trim(); if (v.isEmpty()) return
        val u = when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            !v.contains(' ') && v.contains('.') -> "https://$v"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(v, "UTF-8")
        }
        open(u); addr.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(addr.windowToken, 0)
    }

    private fun open(u: String) { web?.loadUrl(u) }

    private fun goHome() {
        val t = tab ?: return
        t.url = HOME; t.title = "Nouvel onglet"
        web?.loadDataWithBaseURL(null, homeHtml(), "text/html", "utf-8", null); showAddr(false)
    }

    private fun share() {
        tab?.url?.takeIf { it.startsWith("http") }?.let {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), null))
        }
    }

    private fun homeHtml(): String {
        val hello = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 5..17) "Bonjour" else "Bonsoir"
        val tiles = d.bookmarks.take(16).joinToString("") { e ->
            val h = host(e.url); val f = File(icDir, "$h.png")
            val ico = if (f.exists()) "<img src='data:image/png;base64,${Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)}'>"
            else "<b style='background:${String.format("#%06X", COLORS[Math.floorMod(h.hashCode(), COLORS.size)] and 0xFFFFFF)}'>${esc(h.take(1).uppercase())}</b>"
            "<a href='${esc(e.url)}'><i>$ico</i><span>${esc(e.title)}</span></a>"
        }
        return """<meta name=viewport content='width=device-width,initial-scale=1'><style>
body{margin:0;background:#0E1014;color:#E8EAED;font-family:sans-serif;padding:56px 20px}
h1{font-weight:600;font-size:30px;margin:0 0 6px}p{color:#9AA0A6;margin:0 0 40px}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:22px 6px}
a{color:#E8EAED;text-decoration:none;display:flex;flex-direction:column;align-items:center;font-size:12px}
i{width:58px;height:58px;border-radius:20px;background:#22262E;display:flex;align-items:center;justify-content:center;margin-bottom:8px;overflow:hidden}
img{width:30px;height:30px}b{width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:22px;color:#fff}
span{max-width:74px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}</style>
<h1>$hello, ${esc(d.p.name)}</h1><p>${esc(d.p.email.ifEmpty { "Que voulez-vous explorer ?" })}</p><div class=g>$tiles</div>"""
    }

    private fun applyDark(w: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, darkSites)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(t: Tab) = WebView(this).apply {
        if (multi) WebViewCompat.setProfile(this, profName(t))
        setBackgroundColor(BG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.builtInZoomControls = true; settings.displayZoomControls = false
        if (t.priv) settings.cacheMode = WebSettings.LOAD_NO_CACHE
        // Retire le marqueur WebView : sinon Google refuse souvent la connexion au compte.
        settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        applyDark(this)
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
                if (t.id == d.current && !addr.hasFocus()) showAddr(false)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (t.id == d.current) { prog.progress = p; prog.visibility = if (p < 100) View.VISIBLE else View.INVISIBLE }
            }
            override fun onReceivedIcon(v: WebView, icon: Bitmap) {
                val h = host(v.url)
                if (t.priv || h.isEmpty() || excluded(v.url ?: "")) return
                icons[h] = icon
                runCatching { File(icDir, "$h.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
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
        val x = v.trim().lowercase()
        if (x.isEmpty() || x in d.exclusions) return
        d.exclusions += x
        d.history.removeAll { excluded(it.url, it.title) }
        toast("« $x » ne sera plus enregistré")
    }

    private fun history() {
        sheet("Historique · ${d.p.name}") {
            chip("Tout effacer", I.DELETE) { confirm("Effacer tout l'historique ?") { d.history.clear(); reload() } }
            chip("Exclusions", I.BLOCK) { close(); exclusions() }
            if (d.history.isEmpty()) empty("Aucun historique.")
            d.history.take(300).forEach { e ->
                row(siteIcon(host(e.url), 36), e.title, host(e.url) + " · " + DateUtils.getRelativeTimeSpanString(e.t),
                    acts = listOf(I.CLOSE to { d.history.remove(e); reload() })) { close(); open(e.url) }
            }
        }
    }

    private fun exclusions() {
        sheet("Jamais dans l'historique") {
            chip("Ajouter", I.ADD) {
                ask("Site ou mot-clé", "ex : youtube.com ou cadeau" to "", msg = "Les pages et recherches contenant ce texte ne seront jamais enregistrées. L'historique existant est nettoyé.") { (v) -> addExclusion(v); reload() }
            }
            chip("Ce site", I.BLOCK) { addExclusion(host(tab?.url)); reload() }
            if (d.exclusions.isEmpty()) empty("Aucune règle pour l'instant.")
            d.exclusions.toList().forEach { x -> row(ic(I.BLOCK, RED), x, acts = listOf(I.CLOSE to { d.exclusions.remove(x); reload() })) }
        }
    }

    // ---------- Profils ----------
    private fun profiles() {
        sheet("Profils") {
            chip("Nouveau profil", I.ADD) {
                close()
                ask("Nouveau profil", "Nom (ex : Travail)" to "", "Adresse e-mail" to "") { (n, e) ->
                    if (n.isNotEmpty()) Profile(uid(), n, e, COLORS[s.profiles.size % COLORS.size]).also { s.profiles += it; switchTo(it) }
                }
            }
            s.profiles.forEach { p ->
                val cur = p.id == d.p.id
                row(avatarView(p, 42), p.name, p.email.ifEmpty { if (cur) "Profil actif" else null }, bold = cur,
                    acts = listOf(I.MORE to { close(); manage(p) })) { close(); switchTo(p) }
            }
        }
    }

    private fun manage(p: Profile) {
        val photo = File(filesDir, "av_${p.id}.jpg")
        sheet(p.name) {
            row(ic(I.PHOTO), "Changer la photo") {
                close(); pendingPhoto = p.id
                @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 4)
            }
            if (photo.exists()) row(ic(I.CLOSE), "Retirer la photo") { close(); photo.delete(); refresh() }
            row(ic(I.EDIT), "Nom et adresse e-mail", p.email.ifEmpty { null }) {
                close(); ask("Modifier le profil", "Nom" to p.name, "Adresse e-mail" to p.email) { (n, e) -> if (n.isNotEmpty()) p.name = n; p.email = e; s.save(); refresh() }
            }
            row(dot(p.color), "Changer de couleur") { p.color = COLORS[(COLORS.indexOf(p.color) + 1) % COLORS.size]; s.save(); refresh(); reload() }
            if (p.email.isNotEmpty()) row(ic(I.COPY), "Copier l'adresse e-mail") { close(); copy(p.email) }
            if (p.id != d.p.id) row(ic(I.DELETE, RED), "Supprimer ce profil", "Onglets, favoris, historique et mots de passe", color = RED) {
                confirm("Supprimer ${p.name} et toutes ses données ?") {
                    close(); s.delete(p)
                    if (multi) runCatching { ProfileStore.getInstance().deleteProfile("nv_" + p.id) }
                }
            }
        }
    }

    private fun savePhoto(uri: Uri) {
        val id = pendingPhoto ?: return
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
            var ss = 1
            while (o.outWidth / ss > 768 || o.outHeight / ss > 768) ss *= 2
            val b = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = ss }) }!!
            val n = minOf(b.width, b.height)
            val sq = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, (b.width - n) / 2, (b.height - n) / 2, n, n), 256, 256, true)
            File(filesDir, "av_$id.jpg").outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            refresh()
        }.onFailure { toast("Image illisible") }
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
        sheet("Mots de passe · ${d.p.name}") {
            chip("Ajouter", I.ADD) { close(); editCred(null) }
            chip("Remplir ici", I.PERSON) { close(); fill() }
            chip("Générer", I.KEY) { copy(gen()) }
            if (d.creds.isEmpty()) empty("Aucun mot de passe. Ils vous sont proposés quand vous vous connectez à un site.")
            d.creds.sortedBy { it.host }.forEach { c ->
                row(siteIcon(c.host, 36), c.host, c.user.ifEmpty { "(sans identifiant)" }, acts = listOf(I.COPY to { copy(c.pass) })) { close(); credMenu(c) }
            }
        }
    }

    private fun credMenu(c: Cred) {
        var show = false
        sheet(c.host) {
            row(ic(I.KEY), if (show) c.pass else "••••••••••", "Toucher pour afficher / masquer") { show = !show; reload() }
            row(ic(I.COPY), "Copier le mot de passe") { close(); copy(c.pass) }
            row(ic(I.PERSON), "Copier l'identifiant", c.user) { close(); copy(c.user) }
            row(ic(I.EDIT), "Modifier") { close(); editCred(c) }
            row(ic(I.DELETE, RED), "Supprimer", color = RED) { confirm("Supprimer ${c.host} ?") { close(); d.creds.remove(c); d.saveCreds() } }
        }
    }

    private fun editCred(e: Cred?) = ask(if (e == null) "Nouveau mot de passe" else "Modifier",
        "Site (ex : github.com)" to (e?.host ?: host(tab?.url)), "Identifiant" to (e?.user ?: d.p.email),
        "Mot de passe (vide = générer)" to (e?.pass ?: "")) { (h, u, p) ->
        val hh = host(if (h.contains("://")) h else "https://$h")
        if (hh.isEmpty()) return@ask toast("Site invalide")
        e?.let { d.creds.remove(it) }
        d.creds.removeAll { it.host == hh && it.user == u }
        d.creds += Cred(hh, u, p.ifEmpty { gen() }); d.saveCreds(); toast("Enregistré")
    }

    private fun fill() {
        val h = host(web?.url); val c = d.creds.filter { it.host == h }
        when {
            c.isEmpty() -> toast("Aucun mot de passe pour ${h.ifEmpty { "cette page" }}")
            c.size == 1 -> inject(c[0])
            else -> sheet("Quel compte ?") { c.forEach { x -> row(siteIcon(x.host, 36), x.user, x.host) { close(); inject(x) } } }
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
                ask("Enregistrer le mot de passe ?", msg = "$h\n${user.ifEmpty { "(sans identifiant)" }}\nProfil : ${d.p.name}", ok = "Enregistrer") {
                    d.creds.removeAll { it.host == h && it.user == user }; d.creds += Cred(h, user, pass); d.saveCreds()
                }
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
