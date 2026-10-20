/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.BuildConfig
import com.metrolist.music.R
import com.metrolist.music.update.UpdateInstaller
import com.metrolist.music.utils.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

private const val GMS_VARIANT = "gms"
private const val FOSS_VARIANT = "foss"
private const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"

/**
 * Google Cast needs Play Services at runtime. Checked by package rather than
 * through GoogleApiAvailability, which only exists in the gms flavour.
 */
private fun hasPlayServices(context: Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(PLAY_SERVICES_PACKAGE, 0) }.isSuccess

/**
 * Lets the user move between the plain build and the one with Google Cast.
 * Both share an application id and signing key, so the download installs over
 * the current app and the library and settings survive.
 *
 * Not offered on the IzzyOnDroid build, which ships without the updater.
 */
@Composable
fun VariantSwitchCard(modifier: Modifier = Modifier) {
    if (!BuildConfig.UPDATER_AVAILABLE) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val targetVariant = if (Updater.currentVariant == GMS_VARIANT) FOSS_VARIANT else GMS_VARIANT
    val switchingToCast = targetVariant == GMS_VARIANT

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmMissingPlayServices by remember { mutableStateOf(false) }

    fun startSwitch() {
        busy = true
        progress = 0f
        errorMessage = null
        scope.launch {
            runCatching {
                val release = Updater.getLatestRelease(forceRefresh = true).getOrThrow()
                val url = Updater.getDownloadUrlForVariant(release, targetVariant)
                    ?: throw IllegalStateException("No $targetVariant asset in release ${release.tagName}")

                val dest = File(context.cacheDir, "variant-$targetVariant.apk")
                UpdateInstaller.downloadApk(url, dest) { read, total ->
                    if (total > 0) progress = read.toFloat() / total
                }

                val installed =
                    if (UpdateInstaller.isRootAvailable()) {
                        UpdateInstaller.installApkSilently(dest)
                    } else {
                        false
                    }

                withContext(Dispatchers.Main) {
                    if (installed) {
                        UpdateInstaller.relaunchApp(context)
                    } else {
                        // No root, or pm refused: hand the file to the system installer.
                        UpdateInstaller.installApkViaSystemPicker(context, dest)
                    }
                }
            }.onFailure {
                Timber.e(it, "Variant switch failed")
                errorMessage = it.message ?: "unknown error"
            }
            busy = false
        }
    }

    ElevatedCard(
        shape = RoundedCornerShape(32.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(
                    if (switchingToCast) R.string.variant_switch_to_cast_title
                    else R.string.variant_switch_to_plain_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(
                    if (switchingToCast) R.string.variant_switch_to_cast_description
                    else R.string.variant_switch_to_plain_description
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (busy) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.variant_switch_downloading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.variant_switch_failed, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (switchingToCast && !hasPlayServices(context)) {
                        confirmMissingPlayServices = true
                    } else {
                        startSwitch()
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (switchingToCast) R.string.variant_switch_to_cast_action
                        else R.string.variant_switch_to_plain_action
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    if (confirmMissingPlayServices) {
        AlertDialog(
            onDismissRequest = { confirmMissingPlayServices = false },
            title = { Text(stringResource(R.string.variant_switch_no_play_services_title)) },
            text = { Text(stringResource(R.string.variant_switch_no_play_services_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmMissingPlayServices = false
                    startSwitch()
                }) {
                    Text(stringResource(R.string.variant_switch_continue_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMissingPlayServices = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
