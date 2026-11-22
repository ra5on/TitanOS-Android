package io.github.ra5on.titanos.watch.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.wear.input.RemoteInputIntentHelper

private const val KEY = "text"

/** Eingabe auf der Uhr: Tastatur oder Sprache des Systems (RemoteInput), wie Wear OS es anbietet. */
fun textInputIntent(label: String): Intent {
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY).setLabel(label).build()))
    RemoteInputIntentHelper.putTitleExtra(intent, label)
    return intent
}

@Composable
fun rememberTextInput(onText: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = r.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(KEY)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) onText(text)
    }
