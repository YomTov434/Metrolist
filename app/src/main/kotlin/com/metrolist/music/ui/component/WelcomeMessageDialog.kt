/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.metrolist.music.R

@Composable
fun WelcomeMessageDialog(onDismiss: () -> Unit) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.welcome_dialog_title)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.welcome_dialog_confirm))
            }
        },
    ) {
        Text(stringResource(R.string.welcome_dialog_message))
    }
}
