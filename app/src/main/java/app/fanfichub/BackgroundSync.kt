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
        val ctx = applicationContext
        Web.app = ctx
        val hub = Hub(ctx)
        val before = hub.works.associate { it.id to it.newChapters }
        hub.sync()
        val fresh = hub.works.filter { it.newChapters > (before[it.id] ?: 0) }
        if (fresh.isNotEmpty()) notify(ctx, fresh)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackgroundSync>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
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
