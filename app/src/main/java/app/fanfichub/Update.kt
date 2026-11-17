package app.fanfichub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.app.NotificationCompat
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

    /** Dernière version : nom de la release via l'API GitHub (pas de cache CDN), sinon version.txt sans cache. */
    suspend fun latest(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/scalariapp-byte/Navigateur/releases/tags/fanfichub").openConnection() as HttpURLConnection
            c.useCaches = false; c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("Cache-Control", "no-cache")
            Regex("\"name\"\\s*:\\s*\"Fanfic Hub v(\\d+)\"").find(c.inputStream.bufferedReader().readText())!!.groupValues[1].toLong()
        }.recoverCatching {
            val c = URL(REL + "version.txt?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.useCaches = false; c.setRequestProperty("Cache-Control", "no-cache")
            c.inputStream.bufferedReader().readText().trim().toLong()
        }.getOrNull()
    }

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
        // Session d'installation : l'APK est copié dans le système avant la confirmation,
        // donc l'installation continue même si l'app est fermée pendant l'analyse de sécurité (MIUI/HyperOS).
        runCatching {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName); setSize(f.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o) }; s.fsync(o) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags).intentSender)
            }
        }.onFailure {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Retour de la session d'installation : affiche la confirmation si le système la demande. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { Toast.makeText(ctx, "Mise à jour : $it", Toast.LENGTH_LONG).show() }
        }
    }
}

/** Après la mise à jour : relance l'app (ou notification si Android l'interdit). */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(open) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("update", "Mises à jour", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            nm.notify(77, NotificationCompat.Builder(ctx, "update").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Fanfic Hub mis à jour").setContentText("Version ${Updater.current(ctx)} installée · touche pour ouvrir")
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
