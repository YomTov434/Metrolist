/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.metrolist.music.utils.Updater
import timber.log.Timber
import java.io.File

/**
 * Periodic background check so a device that stays open on one screen (or just
 * plays music) for hours still gets a mandatory update without the app needing
 * to be relaunched. On a rooted device this installs silently -- which briefly
 * interrupts playback, the same tradeoff the foreground mandatory-update gate
 * already accepts. On a non-rooted device this only flags
 * [Updater.pendingMandatoryUpdate]; the gate then shows next time the app opens.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            val releaseInfo = Updater.refreshMandatoryUpdateState(forceRefresh = true)
            if (releaseInfo != null && UpdateInstaller.isRootAvailable()) {
                val downloadUrl = Updater.getDownloadUrlForCurrentVariant(releaseInfo)
                if (downloadUrl != null) {
                    val destFile = File(applicationContext.cacheDir, "update.apk")
                    UpdateInstaller.downloadApk(downloadUrl, destFile) { _, _ -> }
                    UpdateInstaller.installApkSilently(destFile)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Background update check failed")
            Result.retry()
        }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val UNIQUE_WORK_NAME = "update_check"
    }
}
