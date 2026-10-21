/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.update.AppUpdateController
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.Updater
import java.util.Locale

@Composable
fun MandatoryUpdateScreen(releaseInfo: ReleaseInfo) {
    val context = LocalContext.current

    // Held by AppUpdateController rather than by this composable, so rotating
    // the device does not cancel a download that is already running.
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    // This screen has no navigation escape hatch: the system back gesture/button
    // must not be able to dismiss it while a mandatory update is pending.
    BackHandler(enabled = true) {}

    fun startUpdate() {
        AppUpdateController.start(
            context = context,
            purpose = AppUpdateController.Purpose.UPDATE,
            fileName = "update.apk",
        ) {
            Updater.getDownloadUrlForCurrentVariant(releaseInfo)
                ?: throw IllegalStateException("No compatible APK found for this device")
        }
    }

    MetrolistTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (val currentState = state) {
                        AppUpdateController.State.Idle -> {
                            Text(
                                text = stringResource(R.string.mandatory_update_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                // versionName mirrors upstream and repeats across our
                                // updates, so it cannot tell two of them apart on its own.
                                text = releaseInfo.versionCode?.let {
                                    stringResource(
                                        R.string.mandatory_update_version_code_format,
                                        releaseInfo.versionName,
                                        it,
                                    )
                                } ?: stringResource(
                                    R.string.mandatory_update_version_format,
                                    releaseInfo.versionName,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = stringResource(R.string.mandatory_update_blocking_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                                textAlign = TextAlign.Center,
                            )
                            if (releaseInfo.description.isNotBlank()) {
                                Text(
                                    text = releaseInfo.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
                                )
                            }
                            Button(
                                onClick = ::startUpdate,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp),
                            ) {
                                Text(stringResource(R.string.mandatory_update_download_now))
                            }
                        }

                        is AppUpdateController.State.Downloading -> {
                            Text(
                                text = stringResource(R.string.mandatory_update_downloading_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            val hasKnownSize = currentState.totalBytes > 0
                            val progress =
                                if (hasKnownSize) {
                                    (currentState.bytesRead.toFloat() / currentState.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            if (hasKnownSize) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp),
                                )
                                val downloadedMb = String.format(Locale.US, "%.1f", currentState.bytesRead / 1024f / 1024f)
                                val totalMb = String.format(Locale.US, "%.1f", currentState.totalBytes / 1024f / 1024f)
                                Text(
                                    text = stringResource(
                                        R.string.mandatory_update_progress_format,
                                        (progress * 100).toInt(),
                                        downloadedMb,
                                        totalMb,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp),
                                )
                            }
                        }

                        AppUpdateController.State.Installing -> {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.mandatory_update_installing),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 16.dp),
                                textAlign = TextAlign.Center,
                            )
                        }

                        AppUpdateController.State.AwaitingManualInstall -> {
                            Text(
                                text = stringResource(R.string.mandatory_update_confirm_install),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                        }

                        is AppUpdateController.State.Failed -> {
                            Text(
                                text = stringResource(R.string.mandatory_update_failed_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = currentState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                                textAlign = TextAlign.Center,
                            )
                            Button(
                                onClick = { AppUpdateController.reset() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp),
                            ) {
                                Text(stringResource(R.string.mandatory_update_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
