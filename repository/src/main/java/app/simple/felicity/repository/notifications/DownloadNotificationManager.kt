package app.simple.felicity.repository.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.simple.felicity.repository.R

class DownloadNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        setupChannel()
    }

    private fun setupChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress and status of YouTube track downloads"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun postProgress(title: String, progress: Int, currentTrackIndex: Int, totalTracks: Int) {
        val headerText = if (totalTracks > 1) "Downloading ($currentTrackIndex/$totalTracks)" else "Downloading Track"
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_felicity_full)
            .setContentTitle(headerText)
            .setContentText(title)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(DOWNLOAD_PROGRESS_NOTIFICATION_ID, notification)
    }

    fun postSuccess(title: String, currentTrackIndex: Int, totalTracks: Int) {
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_felicity_full)
            .setContentTitle("Download Complete ($currentTrackIndex/$totalTracks)")
            .setContentText(title)
            .setSilent(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val id = DOWNLOAD_RESULT_NOTIFICATION_ID_BASE + (title.hashCode() and 0xFFFF)
        notificationManager.notify(id, notification)
    }

    fun postFailure(title: String, reason: String, currentTrackIndex: Int, totalTracks: Int) {
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_felicity_full)
            .setContentTitle("Download Failed ($currentTrackIndex/$totalTracks)")
            .setContentText("$title: $reason")
            .setSilent(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val id = DOWNLOAD_RESULT_NOTIFICATION_ID_BASE + (title.hashCode() and 0xFFFF)
        notificationManager.notify(id, notification)
    }

    fun postBatchSummary(successCount: Int, failureCount: Int, totalTracks: Int) {
        notificationManager.cancel(DOWNLOAD_PROGRESS_NOTIFICATION_ID)

        val summaryText = "Completed $successCount of $totalTracks downloads" +
                if (failureCount > 0) " ($failureCount failed)" else ""

        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_felicity_full)
            .setContentTitle("Batch Download Finished")
            .setContentText(summaryText)
            .setSilent(false)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(DOWNLOAD_SUMMARY_NOTIFICATION_ID, notification)
    }

    fun dismissProgress() {
        notificationManager.cancel(DOWNLOAD_PROGRESS_NOTIFICATION_ID)
    }

    companion object {
        private const val DOWNLOAD_CHANNEL_ID = "felicity_youtube_downloads"
        private const val DOWNLOAD_PROGRESS_NOTIFICATION_ID = 2001
        private const val DOWNLOAD_SUMMARY_NOTIFICATION_ID = 2002
        private const val DOWNLOAD_RESULT_NOTIFICATION_ID_BASE = 2100
    }
}
