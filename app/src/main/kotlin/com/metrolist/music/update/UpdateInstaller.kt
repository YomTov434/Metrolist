/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    private val httpClient = HttpClient()

    suspend fun downloadApk(
        url: String,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        httpClient.prepareGet(url).execute { response: HttpResponse ->
            val totalBytes = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            var bytesRead = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            destFile.outputStream().use { output ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                }
            }
        }

        destFile
    }

    fun isRootAvailable(): Boolean =
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            // su on a non-rooted or MDM-locked device can hang waiting for a permission
            // prompt that will never come, so don't let this block the caller forever.
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }

    suspend fun installApkSilently(apkFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder("su", "-c", "pm install -r '${apkFile.absolutePath}'")
                        .redirectErrorStream(true)
                        .start()
                // Drain the merged stream before waiting for exit so the process can't
                // block forever if pm's output fills the pipe buffer.
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    Timber.tag(TAG).e("Silent install failed (exit=$exitCode): $output")
                }
                exitCode == 0
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Silent install threw an exception")
                false
            }
        }

    fun installApkViaSystemPicker(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(intent)
    }

    fun relaunchApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) {
            context.startActivity(intent)
        }
        Runtime.getRuntime().exit(0)
    }
}
