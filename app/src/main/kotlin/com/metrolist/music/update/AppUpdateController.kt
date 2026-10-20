/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Owns the download-and-install job behind both the mandatory update screen
 * and the variant switch.
 *
 * The job runs on a process-wide scope rather than a composition's, because
 * MainActivity is recreated on a configuration change: a download started from
 * a composable was cancelled, and its progress lost, as soon as the user
 * rotated the device or navigated away.
 */
object AppUpdateController {
    enum class Purpose { UPDATE, VARIANT_SWITCH }

    sealed interface State {
        data object Idle : State

        /** [totalBytes] is -1 until the server reports a content length. */
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : State

        data object Installing : State
        data object AwaitingManualInstall : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _purpose = MutableStateFlow<Purpose?>(null)
    val purpose: StateFlow<Purpose?> = _purpose.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Downloads [fileName] from the URL that [resolveUrl] produces and installs
     * it. Silent through root when available, otherwise handed to the system
     * installer. A second call while a job is in flight is ignored.
     */
    fun start(
        context: Context,
        purpose: Purpose,
        fileName: String,
        resolveUrl: suspend () -> String,
    ) {
        if (isRunning) return

        val appContext = context.applicationContext
        _purpose.value = purpose
        _state.value = State.Downloading(0L, -1L)

        job = scope.launch {
            try {
                val url = resolveUrl()
                val destination = File(appContext.cacheDir, fileName)

                UpdateInstaller.downloadApk(url, destination) { bytesRead, totalBytes ->
                    _state.value = State.Downloading(bytesRead, totalBytes)
                }

                _state.value = State.Installing

                val installedSilently =
                    UpdateInstaller.isRootAvailable() &&
                        UpdateInstaller.installApkSilently(destination)

                if (installedSilently) {
                    withContext(Dispatchers.Main) { UpdateInstaller.relaunchApp(appContext) }
                } else {
                    _state.value = State.AwaitingManualInstall
                    withContext(Dispatchers.Main) {
                        UpdateInstaller.installApkViaSystemPicker(appContext, destination)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Update job failed")
                _state.value = State.Failed(e.message ?: "unknown error")
            }
        }
    }

    /** Clears a finished or failed job so the caller can offer a retry. */
    fun reset() {
        job = null
        _purpose.value = null
        _state.value = State.Idle
    }
}
