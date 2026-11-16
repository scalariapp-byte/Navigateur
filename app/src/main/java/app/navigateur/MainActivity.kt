package app.navigateur

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
import android.webkit.URLUtil
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
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Calendar

private const val HOME = "nv:home"
private val INTERNAL = setOf("http", "https", "about", "data", "blob", "javascript", "resource", "moz-extension")

class MainActivity : Activity() {
    private lateinit var s: Store
    private lateinit var rt: GeckoRuntime
    private lateinit var gv: GeckoView
    private lateinit var drawer: DrawerLayout
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var addr: EditText
    private lateinit var lockIc: ImageView
    private lateinit var avatarBox: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var prog: ProgressBar
    private lateinit var tabsBtn: TextView
    private lateinit var top: View
    private lateinit var bottom: View
    private lateinit var handle: View
    private val sessions = HashMap<String, GeckoSession>()
    private val canBack = HashSet<String>()
    private val canFwd = HashSet<String>()
    private val icons = HashMap<String, Bitmap?>()
    private val fetching = HashSet<String>()
    private var filePrompt: Pair<PromptDelegate.FilePrompt, (PromptResponse) -> Unit>? = null
    private var pendingPhoto: String? = null
    private val icDir by lazy { File(filesDir, "ic").apply { mkdirs() } }
    private val prefs by lazy { getSharedPreferences("nv", MODE_PRIVATE) }
    private var darkSites: Boolean
        get() = prefs.getBoolean("dark", true)
        set(v) { prefs.edit().putBoolean("dark", v).apply() }
    private val d get() = s.cur
    private val tab get() = d.tabs.firstOrNull { it.id == d.current }
    private val sess get() = tab?.let { sessions[it.id] }
    private val accent get() = d.p.color
    private fun scheme() = if (darkSites) GeckoRuntimeSettings.COLOR_SCHEME_DARK else GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM

    // ---------- Petits utilitaires UI ----------
    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun host(u: String?) = Uri.parse(u ?: "").host?.removePrefix("www.") ?: ""
    private fun dec(u: String) = runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(u)
    private fun esc(v: String) = TextUtils.htmlEncode(v)
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
    inner class Sheet(title: String, onDismiss: (() -> Unit)?, private val build: Sheet.() -> Unit) {
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
            onDismiss?.let { f -> dlg.setOnDismissListener { f() } }
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
    private fun sheet(title: String, onDismiss: (() -> Unit)? = null, build: Sheet.() -> Unit) { Sheet(title, onDismiss, build) }

    private fun ask(title: String, vararg fields: Pair<String, String>, msg: String? = null, ok: String = "OK", cancel: (() -> Unit)? = null, f: (List<String>) -> Unit) {
        val dlg = Dialog(this)
        var okd = false
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
                    setOnClickListener { okd = true; dlg.dismiss(); f(es.map { e -> e.text.toString().trim() }) }
                })
            })
        }
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE); dlg.setContentView(col)
        dlg.setOnDismissListener { if (!okd) cancel?.invoke() }
        dlg.show()
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
        rt = runtime ?: GeckoRuntime.create(applicationContext, GeckoRuntimeSettings.Builder()
            .preferredColorScheme(scheme()).loginAutofillEnabled(true).build()).also { runtime = it }
        rt.autocompleteStorageDelegate = Storage()
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
            addView(icon(I.REFRESH, MUT, 11) { sess?.reload() }, lp(px(42), px(42)))
        }
        top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(12), px(8), px(12), px(8)); setBackgroundColor(BG)
            addView(avatarBox, lp(px(40), px(40)).apply { rightMargin = px(10) })
            addView(pill, lp(0, px(46), 1f))
        }
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressBackgroundTintList = ColorStateList.valueOf(BG)
        }
        gv = GeckoView(this).apply { coverUntilFirstPaint(BG) }
        box = FrameLayout(this).apply { setBackgroundColor(BG); addView(gv, -1, -1) }
        val r = px(16).toFloat()
        handle = ImageView(this).apply {
            setImageDrawable(Ic(I.STAR, TXT)); setPadding(px(5), px(24), px(3), px(24))
            background = GradientDrawable().apply { setColor(0xCC22262E.toInt()); cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) }
            setOnClickListener { drawer.openDrawer(Gravity.END) }
        }
        val stage = FrameLayout(this).apply {
            addView(box, -1, -1)
            addView(handle, FrameLayout.LayoutParams(px(24), px(72), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        tabsBtn = txt("", 12f, TXT, true).apply { gravity = Gravity.CENTER }
        bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(SURF); setPadding(px(4), 0, px(4), 0)
            fun slot(v: View) = addView(v, lp(0, px(56), 1f))
            slot(icon(I.MENU, TXT, 16) { drawer.openDrawer(Gravity.START) })
            slot(icon(I.BACK, TXT, 16) { if (tab?.id in canBack) sess?.goBack() })
            slot(icon(I.FWD, TXT, 16) { if (tab?.id in canFwd) sess?.goForward() })
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
        intent?.dataString?.let { newTab(it) } ?: refresh()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.dataString?.let { newTab(it) } }
    override fun onPause() { super.onPause(); s.save() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(Gravity.START) || drawer.isDrawerOpen(Gravity.END) -> drawer.closeDrawers()
            top.visibility != View.VISIBLE -> sess?.exitFullScreen()
            tab?.id in canBack -> sess?.goBack()
            else -> super.onBackPressed()
        }
    }
    @Deprecated("") override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        if (rc == 2) filePrompt?.let { (p, done) ->
            filePrompt = null
            val uris = data?.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            done(when { res != RESULT_OK || uris.isEmpty() -> p.dismiss(); uris.size == 1 -> p.confirm(this, uris[0]); else -> p.confirm(this, uris.toTypedArray()) })
        }
        if (rc == 3 && res == RESULT_OK) passwords(true)
        if (rc == 4 && res == RESULT_OK) data?.data?.let { savePhoto(it) }
    }

    private fun refresh() {
        if (d.tabs.isEmpty()) return newTab()
        val t = tab ?: d.tabs.last().also { d.current = it.id }
        val se = session(t)
        if (gv.session != se) { gv.releaseSession(); gv.setSession(se) }
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
        item(I.BLOCK, "Ne jamais enregistrer ce site") { addExclusion(host(tab?.url)) }
        item(I.EDIT, "Règles d'exclusion", "${d.exclusions.size} règle(s)") { exclusions() }
        item(I.SHARE, "Partager la page") { share() }
        item(I.MOON, "Sites en mode sombre", if (darkSites) "Activé" else "Selon le système") {
            darkSites = !darkSites; rt.settings.setPreferredColorScheme(scheme()); buildLeft()
        }
    }

    // ---------- Onglets ----------
    private fun newTab(u: String = HOME, g: String? = tab?.group, priv: Boolean = false) {
        val t = Tab(uid(), u, "Nouvel onglet", g, priv)
        d.tabs += t; d.current = t.id; refresh()
        if (u == HOME) addr.requestFocus()
    }

    private fun closeTab(t: Tab) {
        sessions.remove(t.id)?.let { if (gv.session == it) gv.releaseSession(); it.close() }
        d.tabs.remove(t)
        if (d.current == t.id) d.current = d.tabs.lastOrNull()?.id
        refresh()
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

    private fun open(u: String) { sess?.loadUri(u) }

    private fun goHome() {
        val t = tab ?: return
        t.url = HOME; t.title = "Nouvel onglet"
        sess?.loadString(homeHtml(), "text/html"); showAddr(false)
    }

    private fun share() {
        tab?.url?.takeIf { it.startsWith("http") }?.let {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it), null))
        }
    }

    // ---------- Moteur Gecko ----------
    private fun session(t: Tab): GeckoSession = sessions.getOrPut(t.id) {
        GeckoSession(GeckoSessionSettings.Builder().contextId(if (t.priv) null else "nv_" + d.p.id).usePrivateMode(t.priv).build()).apply {
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                    if (url == null || !url.startsWith("http")) return
                    t.url = url; record(t, url)
                    if (t.id == d.current && !addr.hasFocus()) showAddr(false)
                }
                override fun onCanGoBack(session: GeckoSession, can: Boolean) { if (can) canBack += t.id else canBack -= t.id }
                override fun onCanGoForward(session: GeckoSession, can: Boolean) { if (can) canFwd += t.id else canFwd -= t.id }
                override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                    val u = request.uri
                    if (u.substringBefore(':').lowercase() in INTERNAL) return GeckoResult.allow()
                    runCatching {
                        startActivity(if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
                        } else Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    }
                    return GeckoResult.deny()
                }
                override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? { newTab(uri, priv = t.priv); return null }
            }
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    if (t.id == d.current) { prog.progress = progress; prog.visibility = if (progress < 100) View.VISIBLE else View.INVISIBLE }
                }
                override fun onPageStop(session: GeckoSession, success: Boolean) { if (success && !t.priv && !excluded(t.url)) fetchIcon(host(t.url)) }
            }
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    if (title.isNullOrBlank() || !t.url.startsWith("http")) return
                    t.title = title
                    val h = d.history.firstOrNull()
                    if (h != null && h.url == t.url && !t.priv) { if (excluded(t.url, title)) d.history.removeAt(0) else h.title = title }
                }
                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    val v = if (fullScreen) View.GONE else View.VISIBLE
                    top.visibility = v; bottom.visibility = v; handle.visibility = v; prog.visibility = v
                }
                override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                    val link = element.linkUri; val img = element.srcUri
                    if (link == null && img == null) return
                    sheet(link ?: img ?: "") {
                        link?.let { l ->
                            row(ic(I.OPEN), "Ouvrir dans un nouvel onglet") { close(); newTab(l, priv = t.priv) }
                            row(ic(I.PRIVATE), "Ouvrir dans un onglet privé") { close(); newTab(l, priv = true) }
                            row(ic(I.COPY), "Copier le lien") { close(); copy(l) }
                            row(ic(I.STAR), "Ajouter aux favoris") { close(); d.bookmarks += Entry(element.title ?: element.linkUri ?: l, l); buildRight() }
                        }
                        img?.let { i -> row(ic(I.PHOTO), "Ouvrir l'image") { close(); newTab(i, priv = t.priv) } }
                    }
                }
                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    val hd = response.headers.entries.associate { it.key.lowercase() to it.value }
                    runCatching {
                        val r = DownloadManager.Request(Uri.parse(response.uri)).setMimeType(hd["content-type"])
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(response.uri, hd["content-disposition"], hd["content-type"]))
                        getSystemService(DownloadManager::class.java).enqueue(r); toast("Téléchargement…")
                    }.onFailure { toast("Échec du téléchargement") }
                }
            }
            promptDelegate = Prompts(t)
            open(rt)
            if (t.url == HOME) loadString(homeHtml(), "text/html") else loadUri(t.url)
        }
    }

    /** Résultat asynchrone complété une seule fois (OK ou annulation). */
    private fun <T> later(start: ((T) -> Unit) -> Unit): GeckoResult<T> {
        val r = GeckoResult<T>(); var done = false
        start { if (!done) { done = true; r.complete(it) } }
        return r
    }

    /** Fenêtres des pages : alertes, confirmations, listes déroulantes, fichiers, mots de passe. */
    inner class Prompts(private val t: Tab) : PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse>? = later { done ->
            ask(prompt.title ?: host(t.url), msg = prompt.message, cancel = { done(prompt.dismiss()) }) { done(prompt.dismiss()) }
        }
        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse>? = later { done ->
            ask(prompt.title ?: host(t.url), msg = prompt.message, cancel = { done(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }) {
                done(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
            }
        }
        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse>? = later { done ->
            ask(prompt.title ?: host(t.url), "" to (prompt.defaultValue ?: ""), msg = prompt.message, cancel = { done(prompt.dismiss()) }) { (v) -> done(prompt.confirm(v)) }
        }
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse>? = later { done ->
            val all = prompt.choices.flatMap { c -> if (c.items != null) listOf(c) + c.items!!.toList() else listOf(c) }
            val multi = prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE
            val picked = all.filter { it.selected }.map { it.id }.toMutableSet()
            sheet(prompt.title ?: "Choisir", onDismiss = { done(if (multi) prompt.confirm(picked.toTypedArray()) else prompt.dismiss()) }) {
                all.forEach { c ->
                    when {
                        c.separator -> {}
                        c.items != null -> header(c.label, MUT)
                        else -> row(if (c.id in picked) ic(I.CHECK, accent) else null, c.label, bold = c.id in picked) {
                            if (c.disabled) return@row
                            if (multi) { if (!picked.remove(c.id)) picked += c.id; reload() } else { done(prompt.confirm(c)); close() }
                        }
                    }
                }
            }
        }
        override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse>? = later { done ->
            filePrompt = prompt to done
            val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(prompt.mimeTypes?.singleOrNull() ?: "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE)
            prompt.mimeTypes?.takeIf { it.size > 1 }?.let { i.putExtra(Intent.EXTRA_MIME_TYPES, it) }
            runCatching { @Suppress("DEPRECATION") startActivityForResult(i, 2) }.onFailure { filePrompt = null; done(prompt.dismiss()) }
        }
        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
        override fun onLoginSave(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSaveOption>): GeckoResult<PromptResponse>? {
            val opt = request.options.firstOrNull() ?: return null
            if (t.priv) return GeckoResult.fromValue(request.dismiss())
            val l = opt.value
            return later { done ->
                ask("Enregistrer le mot de passe ?", msg = "${host(l.origin)}\n${l.username ?: "(sans identifiant)"}\nProfil : ${d.p.name}",
                    ok = "Enregistrer", cancel = { done(request.dismiss()) }) { done(request.confirm(opt)) }
            }
        }
        override fun onLoginSelect(session: GeckoSession, request: PromptDelegate.AutocompleteRequest<Autocomplete.LoginSelectOption>): GeckoResult<PromptResponse>? = later { done ->
            sheet("Quel compte ?", onDismiss = { done(request.dismiss()) }) {
                request.options.forEach { o -> row(siteIcon(host(o.value.origin), 36), o.value.username ?: "(sans identifiant)", host(o.value.origin)) { done(request.confirm(o)); close() } }
            }
        }
    }

    /** Coffre du profil actif branché sur le remplissage automatique de Gecko. */
    inner class Storage : Autocomplete.StorageDelegate {
        override fun onLoginFetch(domain: String): GeckoResult<Array<Autocomplete.LoginEntry>>? =
            GeckoResult.fromValue(d.creds.filter { it.host == domain || it.host.endsWith(".$domain") || domain.endsWith(".${it.host}") }.map {
                Autocomplete.LoginEntry.Builder().origin("https://${it.host}").formActionOrigin("https://${it.host}")
                    .username(it.user).password(it.pass).guid(it.host + "|" + it.user).build()
            }.toTypedArray())
        override fun onLoginSave(login: Autocomplete.LoginEntry) {
            val h = host(login.origin); val u = login.username ?: ""; val p = login.password ?: return
            if (h.isEmpty()) return
            runOnUiThread { d.creds.removeAll { it.host == h && it.user == u }; d.creds += Cred(h, u, p); d.saveCreds(); buildLeft() }
        }
    }

    private fun fetchIcon(h: String) {
        if (h.isEmpty() || h in fetching || File(icDir, "$h.png").exists()) return
        fetching += h
        Thread {
            runCatching {
                val c = URL("https://$h/favicon.ico").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                val b = c.inputStream.use { BitmapFactory.decodeStream(it) }!!
                File(icDir, "$h.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                runOnUiThread { icons[h] = b; buildRight() }
            }
        }.start()
    }

    private fun switchTo(p: Profile) {
        if (p.id == d.p.id) return
        gv.releaseSession()
        sessions.values.forEach { it.close() }
        sessions.clear(); canBack.clear(); canFwd.clear()
        s.switch(p); refresh(); toast("Profil : ${p.name}")
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
                    rt.storageController.clearDataForSessionContext("nv_" + p.id)
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

    // ---------- Mots de passe ----------
    private fun passwords(authed: Boolean = false) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!authed && km.isDeviceSecure) {
            @Suppress("DEPRECATION")
            return startActivityForResult(km.createConfirmDeviceCredentialIntent("Mots de passe", d.p.name), 3)
        }
        sheet("Mots de passe · ${d.p.name}") {
            chip("Ajouter", I.ADD) { close(); editCred(null) }
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

    companion object {
        private var runtime: GeckoRuntime? = null
        private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*-_+=?"
    }
}
