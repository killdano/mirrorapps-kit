package com.mirrorapps.kit.feedback

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mirrorapps.kit.KitColors
import com.mirrorapps.kit.LogsMode
import com.mirrorapps.kit.MirrorAppsKit
import com.mirrorapps.kit.feedback.media.MediaKind
import com.mirrorapps.kit.ui.kitColors
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The Feedback chat. Hosts show it as a tab and, inside their own activity,
 * from screens where the tabs aren't reachable (a paywall). Every place
 * shows the same [FeedbackSession].
 *
 * No app switch: the hub works out which app a report is about (see
 * [com.mirrorapps.kit.FeedbackOptions.app]).
 */
@Composable
fun FeedbackPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = kitColors()
    val config = MirrorAppsKit.config
    val chat by FeedbackSession.items.collectAsState()
    val busy by FeedbackSession.busy.collectAsState()
    val consentReason by FeedbackSession.consentReason.collectAsState()
    val lastBundle by FeedbackSession.lastBundle.collectAsState()
    val draft by FeedbackSession.draft.collectAsState()
    val attachments by FeedbackSession.attachments.collectAsState()
    val recordingSince by FeedbackSession.recordingSince.collectAsState()
    val logsMode by FeedbackSession.logsMode.collectAsState()
    var showPreview by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val standIn = FeedbackSession.usingStandIn

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri).orEmpty()
            val kind = if (type.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE
            FeedbackSession.attach(context, uri, kind)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            FeedbackSession.startVoice(context)
        } else {
            Toast.makeText(context, "Voice messages need the microphone.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { FeedbackSession.onShown() }

    // Follow the reply as it streams in.
    val lastLength = chat.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(chat.size, lastLength) {
        if (chat.isNotEmpty()) listState.scrollToItem(chat.lastIndex)
    }

    // The recording timer.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingSince) {
        if (recordingSince == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LogsChoiceRow(selected = logsMode, colors = c) { FeedbackSession.chooseLogs(it) }

        if (chat.isNotEmpty() || standIn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (standIn) {
                    Text(text = "STAND-IN", color = c.sectionFg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (chat.isNotEmpty() && !busy) {
                    LinkText("New chat", c.helpFg) { FeedbackSession.reset() }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chat, key = { it.id }) { item ->
                ChatItem(
                    item = item,
                    typing = busy && item.text.isEmpty(),
                    colors = c,
                    onView = { showPreview = true },
                )
            }
        }

        if (attachments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a -> AttachmentRow(a, c) }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { FeedbackSession.setDraft(it) },
                placeholder = { Text(text = "Describe the problem", color = c.helpFg, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
                enabled = recordingSince == null,
                maxLines = 4,
                shape = RoundedCornerShape(6.dp),
                textStyle = TextStyle(color = c.fg, fontSize = 14.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.cardBg,
                    unfocusedContainerColor = c.cardBg,
                    disabledContainerColor = c.cardBg,
                    focusedIndicatorColor = c.accent,
                    unfocusedIndicatorColor = c.disabledFg,
                    disabledIndicatorColor = c.disabledFg,
                    cursorColor = c.accent,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val since = recordingSince
                if (since != null) {
                    Text(
                        text = "Recording " + clock(now - since),
                        color = c.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    LinkText("Cancel", c.helpFg) { FeedbackSession.cancelVoice() }
                    Spacer(modifier = Modifier.width(8.dp))
                    AccentButton("Stop", enabled = true, colors = c) { FeedbackSession.stopVoice() }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Box {
                        LinkText("Attach", c.accent) { showAttachMenu = true }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Picture or screenshot") },
                                onClick = {
                                    showAttachMenu = false
                                    picker.launch(
                                        PickVisualMediaRequest.Builder()
                                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            .build(),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Video") },
                                onClick = {
                                    showAttachMenu = false
                                    picker.launch(
                                        PickVisualMediaRequest.Builder()
                                            .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                            .build(),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    LinkText("Voice", c.accent) {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            FeedbackSession.startVoice(context)
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // canSend reads the same flows collected above, so it
                    // recomposes with them.
                    AccentButton("Send", enabled = FeedbackSession.canSend(), colors = c) {
                        FeedbackSession.send(context)
                    }
                }
            }
        }
    }

    consentReason?.let { reason ->
        FullLogsDialog(
            reason = reason,
            explanation = config.feedback.fullLogsText,
            colors = c,
            onAllow = { FeedbackSession.answerConsent(context, granted = true) },
            onDecline = { FeedbackSession.answerConsent(context, granted = false) },
        )
    }

    val bundle = lastBundle
    if (showPreview && bundle != null) {
        SentDataDialog(bundle = bundle, logName = config.feedback.logName, onClose = { showPreview = false })
    }
}

/**
 * "Logs in bug reports" at the top of the chat, where logs are shared:
 * Full, Redacted or None. Every app shows it; the setting itself is the
 * host's ([com.mirrorapps.kit.FeedbackOptions.logsMode]).
 */
@Composable
private fun LogsChoiceRow(selected: LogsMode, colors: KitColors, onPick: (LogsMode) -> Unit) {
    val c = colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Logs in bug reports",
            color = c.fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LogsMode.FULL to "Full",
                LogsMode.REDACTED to "Redacted",
                LogsMode.NONE to "None",
            ).forEach { (mode, label) ->
                ChoiceButton(label = label, selected = mode == selected, colors = c) { onPick(mode) }
            }
        }
    }
}

/** One option of a choice: accent fill when selected, card with a divider border when not. */
@Composable
private fun ChoiceButton(label: String, selected: Boolean, colors: KitColors, onClick: () -> Unit) {
    val c = colors
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(if (selected) c.accent else c.cardBg, RoundedCornerShape(6.dp))
            .border(1.dp, if (selected) c.accent else c.cardDivider, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) c.accentFg else c.fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LinkText(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

@Composable
private fun AccentButton(text: String, enabled: Boolean, colors: KitColors, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.accentFg,
        ),
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** One attachment in the composer: what it is, how its upload is going, and Remove. */
@Composable
private fun AttachmentRow(a: FeedbackSession.Attachment, c: KitColors) {
    val status = when (a.state) {
        FeedbackSession.AttachState.UPLOADING -> "Uploading ${(a.progress * 100).toInt()}%"
        FeedbackSession.AttachState.TRANSCRIBING -> "Transcribing…"
        FeedbackSession.AttachState.READY -> "Ready"
        FeedbackSession.AttachState.FAILED -> a.error ?: "Failed"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.cardBg, RoundedCornerShape(6.dp))
            .border(1.dp, c.cardDivider, RoundedCornerShape(6.dp))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = a.label, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = status, color = c.helpFg, fontSize = 11.sp, modifier = Modifier.weight(1f))
        LinkText("Remove", c.helpFg) { FeedbackSession.removeAttachment(a.id) }
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
private fun ChatItem(item: FeedbackSession.Item, typing: Boolean, colors: KitColors, onView: () -> Unit) {
    val c = colors
    when (item.role) {
        FeedbackSession.Role.USER -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(c.accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (item.text.isNotEmpty()) {
                    Text(text = item.text, color = c.accentFg, fontSize = 14.sp)
                }
                if (item.attachments.isNotEmpty()) {
                    Text(
                        text = "Attached: " + item.attachments.joinToString(", "),
                        color = c.accentFg,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        FeedbackSession.Role.AGENT -> Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Text(
                text = if (typing) "…" else item.text.trimEnd(),
                color = c.fg,
                fontSize = 14.sp,
                modifier = Modifier
                    .background(c.cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, c.cardDivider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (item.offersPreview) {
                Text(
                    text = "View",
                    color = c.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(onClick = onView)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
        }
        FeedbackSession.Role.STATUS -> Text(
            text = item.text,
            color = c.helpFg,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Asks for full logs. The title, explanation and buttons are the app's own
 * text; only the quoted reason comes from the agent, capped at
 * [FeedbackEvent.MAX_REASON_CHARS]. Allow moves "Logs in bug reports" to
 * Full, so the question isn't asked again.
 */
@Composable
private fun FullLogsDialog(
    reason: String,
    explanation: String,
    colors: KitColors,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(text = "Send full logs?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (reason.isNotBlank()) {
                    Text(text = "“$reason”")
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(text = explanation)
            }
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.accentFg,
                ),
            ) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Not now") }
        },
    )
}

/**
 * Exactly what was last sent. Log text is shown line by line in a lazy list
 * because a full buffer runs to thousands of lines.
 */
@Composable
private fun SentDataDialog(bundle: JSONObject, logName: String, onClose: () -> Unit) {
    val lines = remember(bundle) { previewLines(bundle, logName) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(text = "What was sent", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

private fun previewLines(bundle: JSONObject, logName: String): List<String> {
    val copy = JSONObject(bundle.toString())
    val logs = copy.optJSONObject("logs")
    val hostLog = logs?.optString("dashLog")
    val logcat = logs?.optString("logcat")
    logs?.remove("dashLog")
    logs?.remove("logcat")
    val out = copy.toString(2).split('\n').toMutableList()
    if (!hostLog.isNullOrEmpty()) {
        out += ""
        out += "$logName:"
        out += hostLog.split('\n')
    }
    if (!logcat.isNullOrEmpty()) {
        out += ""
        out += "logcat:"
        out += logcat.split('\n')
    }
    return out
}
