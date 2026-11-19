package com.mirrorapps.kit.sessions

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mirrorapps.kit.MirrorAppsKit
import com.mirrorapps.kit.ui.KitToggleRow
import com.mirrorapps.kit.ui.kitColors
import kotlinx.coroutines.launch

/**
 * The host's words for its "Share my …" row and dialogs. The consent text
 * is what the user agrees to: when its meaning changes, bump the host's
 * [SessionSpec.consentVersion] and `CONSENT_VERSION` on the backend together.
 */
class SharingWording(
    /** "Share my trips". */
    val rowLabel: String,
    /** One line under the switch. */
    val rowHelp: String,
    /** Under the switch for the owner's credential, which always records. */
    val ownerHelp: String,
    /** "Delete my trips". */
    val deleteLink: String,
    val consentTitle: String,
    val consentParagraphs: List<String>,
    /** The accept button. */
    val consentAccept: String,
    /** Linked as "Privacy policy" under the consent text, if set. */
    val privacyUrl: String?,
    val deleteTitle: String,
    val deleteText: String,
    /** Toast after a delete: "Deleted 3 trips". */
    val deleted: (Int) -> String,
    /** Toast when the backend can't be reached, while it keeps retrying an off. */
    val unreachableRetry: String,
    /** Toast when the backend can't be reached. */
    val unreachable: String,
)

/**
 * "Share my …": a user's opt-in to session recording for one app. Off by
 * default. Turning it on goes through the consent dialog first; turning it
 * off stops recording on the phone at once. The owner's credential always
 * records, so for it the row is on and can't be switched, and there's no
 * delete.
 *
 * Shown only with a credential: every session request is authenticated by it.
 *
 * @param topSpacing Space above the row, drawn only when the row is.
 */
@Composable
fun SessionSharingSection(
    recorder: SessionRecorder,
    wording: SharingWording,
    topSpacing: Dp = 0.dp,
) {
    val liveCode by MirrorAppsKit.config.credential.collectAsState()
    if (liveCode.isNullOrBlank()) return
    val state by recorder.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = kitColors()
    var busy by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val owner = state.isOwner

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    if (topSpacing > 0.dp) Spacer(modifier = Modifier.height(topSpacing))
    KitToggleRow(
        label = wording.rowLabel,
        help = if (owner) wording.ownerHelp else wording.rowHelp,
        checked = state.record,
        enabled = !owner && !busy,
        onCheckedChange = { on ->
            if (on) {
                showConsent = true
            } else {
                busy = true
                scope.launch {
                    val r = recorder.setConsent(on = false)
                    busy = false
                    if (r !is SessionRecorder.ConsentResult.Ok) toast(wording.unreachableRetry)
                }
            }
        },
    )
    if (!owner) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = wording.deleteLink,
                color = c.helpFg,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(enabled = !busy) { showDelete = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }

    if (showConsent) {
        SessionConsentDialog(
            wording = wording,
            onAccept = {
                showConsent = false
                busy = true
                scope.launch {
                    when (recorder.setConsent(on = true)) {
                        is SessionRecorder.ConsentResult.Ok -> Unit
                        // The wording changed on the backend: ask again.
                        SessionRecorder.ConsentResult.Outdated -> showConsent = true
                        else -> toast(wording.unreachable)
                    }
                    busy = false
                }
            },
            onDismiss = { showConsent = false },
        )
    }
    if (showDelete) {
        DeleteSessionsDialog(
            wording = wording,
            onConfirm = {
                showDelete = false
                busy = true
                scope.launch {
                    val r = recorder.setConsent(on = false, deleteData = true)
                    busy = false
                    if (r is SessionRecorder.ConsentResult.Ok) {
                        toast(wording.deleted(r.deleted))
                    } else {
                        toast(wording.unreachable)
                    }
                }
            },
            onDismiss = { showDelete = false },
        )
    }
}

/**
 * Full-screen consent. The one place an app explains itself at length,
 * because this is what the user agrees to.
 */
@Composable
fun SessionConsentDialog(
    wording: SharingWording,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = kitColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = wording.consentTitle,
                        color = c.fg,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    wording.consentParagraphs.forEach { paragraph ->
                        Text(text = paragraph, color = c.fg, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    val url = wording.privacyUrl
                    if (url != null) {
                        Text(
                            text = "Privacy policy",
                            color = c.accent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Not now", color = c.fg)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onAccept,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = c.accentFg,
                        ),
                    ) {
                        Text(wording.consentAccept)
                    }
                }
            }
        }
    }
}

/** Confirm before deleting everything the user shared. */
@Composable
fun DeleteSessionsDialog(
    wording: SharingWording,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = kitColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = wording.deleteTitle, fontWeight = FontWeight.Bold) },
        text = { Text(text = wording.deleteText) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.accentFg,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
