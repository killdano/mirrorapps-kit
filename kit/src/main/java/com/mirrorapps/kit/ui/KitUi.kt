package com.mirrorapps.kit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirrorapps.kit.KitApp
import com.mirrorapps.kit.KitColors
import com.mirrorapps.kit.MirrorAppsKit

/** The host's palette (see [com.mirrorapps.kit.KitTheme]). */
@Composable
fun kitColors(): KitColors = MirrorAppsKit.config.theme.colors()

/**
 * A settings row with a switch on the right: 14sp bold title, 11sp detail,
 * the same geometry as Dash's own rows.
 */
@Composable
fun KitToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val c = kitColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = help, color = c.helpFg, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        KitTogglePill(checked = checked)
    }
}

/** On/off pill: accent when on, gray when off. No animation. */
@Composable
fun KitTogglePill(checked: Boolean) {
    val c = kitColors()
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(22.dp)
            .background(if (checked) c.accent else c.disabledFg, shape = RoundedCornerShape(50)),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .padding(horizontal = 2.dp)
                .background(Color.White, shape = RoundedCornerShape(50)),
        )
    }
}

/**
 * Two or more apps side by side, one selected: the MirrorMap | MirrorMov
 * switch at the top of Dash's Feedback tab.
 */
@Composable
fun KitAppSwitch(
    apps: List<KitApp>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = kitColors()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, c.accent, shape),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        apps.forEach { app ->
            val on = app.id == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) c.accent else Color.Transparent, shape)
                    .clickable(enabled = enabled && !on) { onSelect(app.id) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.label,
                    color = if (on) c.accentFg else if (enabled) c.fg else c.disabledFg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
