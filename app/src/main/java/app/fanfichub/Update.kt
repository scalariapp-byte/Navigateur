package app.fanfichub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Mise à jour depuis la release GitHub « fanfichub » (version.txt = numéro de build). */
object Updater {
    private const val REL = "https://github.com/scalariapp-byte/Navigateur/releases/download/fanfichub/"

    fun current(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
    }

    suspend fun latest(): Long? = withContext(Dispatchers.IO) { runCatching { URL(REL + "version.txt").readText().trim().toLong() }.getOrNull() }

    suspend fun download(ctx: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "update.apk")
        val c = URL(REL + "FanficHub.apk").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        val total = c.contentLengthLong.coerceAtLeast(1)
        val main = Handler(Looper.getMainLooper())
        c.inputStream.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    if (done - last > 256 * 1024) { last = done; val p = done.toFloat() / total; main.post { progress(p) } }
                }
            }
        }
        f
    }

    fun install(ctx: Context, f: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
