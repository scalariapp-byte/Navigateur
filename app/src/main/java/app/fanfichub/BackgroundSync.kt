package app.fanfichub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Synchro automatique toutes les 3 h (réseau requis) + notification des nouveaux chapitres. */
class BackgroundSync(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Ext.init(applicationContext)
        val ctx = applicationContext
        Web.app = ctx
        val hub = Hub(ctx)
        val before = hub.works.associate { it.id to it.newChapters }
        hub.sync()
        hub.flush()
        val fresh = hub.works.filter { it.newChapters > (before[it.id] ?: 0) }
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        // Nouveaux commentaires AO3 : notification si le nombre de non lus augmente
        hub.user?.let { u -> Ao3.unread(u)?.let { n ->
            val p = ctx.getSharedPreferences("inbox", Context.MODE_PRIVATE)
            if (n > p.getInt("ao3", 0)) notifyComments(ctx, n)
            p.edit().putInt("ao3", n).apply()
        } }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackgroundSync>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun notifyComments(ctx: Context, n: Int) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("comments", "Nouveaux commentaires", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(2, NotificationCompat.Builder(ctx, "comments").setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("AO3 : $n commentaire" + (if (n > 1) "s" else "") + " non lu" + (if (n > 1) "s" else ""))
                .setContentText("Réglages → Commentaires & messages pour répondre").setContentIntent(open).setAutoCancel(true).build())
        }

        private fun notify(ctx: Context, works: List<Work>) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("chapters", "Nouveaux chapitres", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = if (works.size == 1) "Nouveau chapitre : ${works[0].title}" else "${works.size} fanfictions ont de nouveaux chapitres"
            val n = NotificationCompat.Builder(ctx, "chapters").setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle(title).setContentText(works.joinToString(" · ") { it.title })
                .setStyle(NotificationCompat.BigTextStyle().bigText(works.joinToString("\n") { "• ${it.title} (+${it.newChapters})" }))
                .setContentIntent(open).setAutoCancel(true).build()
            nm.notify(1, n)
        }
    }
}
