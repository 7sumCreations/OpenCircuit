package io.github.opencircuit.app.ring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The connection card: a status dot and the link's words, the ring's name, the battery with its
 * icon band, the charging / time line, "as of …" when out of date, the case battery while
 * docked, the charger hint, a status-request problem, and the one action for the link's state
 * (`ios/OpenCircuit/ContentView.swift:1105-1258` @ b1c2fdd). Drawn from [card] alone.
 */
@Composable
fun ConnectionCard(card: ConnectionCardUi, onAction: (LinkAction) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(connected = card.link.connected, modifier = Modifier.padding(top = 6.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    card.ringName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(card.link.headline, style = MaterialTheme.typography.bodyMedium)
                }
                card.battery?.let { BatteryColumn(it) }
            }
            card.link.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            card.chargerHint?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            card.problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                LinkActionButton(card.link.action, onAction)
            }
        }
    }
}

@Composable
private fun LinkActionButton(action: LinkAction, onAction: (LinkAction) -> Unit) {
    when (action) {
        LinkAction.SCAN_AND_CONNECT, LinkAction.TRY_AGAIN, LinkAction.TURN_ON_BLUETOOTH, LinkAction.BLUETOOTH_SETTINGS ->
            FilledTonalButton(onClick = { onAction(action) }) { Text(action.label) }
        LinkAction.CANCEL, LinkAction.STOP_RECONNECTING, LinkAction.DISCONNECT ->
            TextButton(onClick = { onAction(action) }) { Text(action.label) }
    }
}

@Composable
private fun BatteryColumn(battery: BatteryUi) {
    val colour = when {
        battery.charging -> MaterialTheme.colorScheme.primary
        battery.stale -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        battery.low -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val caption = MaterialTheme.typography.labelSmall
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BatteryIcon(
                band = battery.band,
                colour = colour,
                modifier = Modifier.semantics {
                    contentDescription = if (battery.charging) "Ring battery, charging" else "Ring battery"
                },
            )
            Text("${battery.percent}%", style = MaterialTheme.typography.titleSmall, color = colour)
            if (battery.charging) Text("⚡", style = caption, color = colour)
        }
        battery.asOf?.let { Text(it, style = caption, color = faint) }
        battery.inferredChargingLabel?.let { Text(it, style = caption, color = faint) }
        battery.timeLine?.let { Text(it, style = caption, color = faint) }
        battery.caseLine?.let {
            val caseColour = if (battery.caseCharging) MaterialTheme.colorScheme.primary else faint
            Text(if (battery.caseCharging) "$it ⚡" else it, style = caption, color = caseColour)
        }
    }
}

/** Green when the ring is connected and its data flows, an outline otherwise (CV:1108). */
@Composable
private fun StatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.size(10.dp)) {
        if (connected) {
            drawCircle(color = CONNECTED_GREEN)
        } else {
            drawCircle(color = outline, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** A battery outline filled by [band]: empty, ¼, ½, ¾ or full (CV:1505-1512). */
@Composable
private fun BatteryIcon(band: BatteryBand, colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 22.dp, height = 12.dp)) {
        val stroke = 1.5.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub, size.height)
        drawRoundRect(colour, size = body, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(width = stroke))
        drawRect(colour, topLeft = Offset(body.width, size.height / 3), size = Size(nub, size.height / 3))
        val fill = when (band) {
            BatteryBand.EMPTY -> 0f
            BatteryBand.QUARTER -> 0.25f
            BatteryBand.HALF -> 0.5f
            BatteryBand.THREE_QUARTERS -> 0.75f
            BatteryBand.FULL -> 1f
        }
        if (fill > 0f) {
            val inset = stroke * 1.5f
            drawRect(
                colour,
                topLeft = Offset(inset, inset),
                size = Size((body.width - 2 * inset) * fill, body.height - 2 * inset),
            )
        }
    }
}

private val CONNECTED_GREEN = Color(0xFF34A853)
