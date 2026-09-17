package app.aaps.pump.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.pump.common.hw.rileylink.R

/**
 * One RileyLink the user can block or unblock.
 *
 * @param address the MAC address, which is what a block is actually keyed on.
 * @param name the name the device reports, or the address again when it has none.
 * @param isBlocked true when this app is already staying away from it.
 * @param isInUse true when this is the RileyLink the app is set up to use.
 */
data class BlockableRileyLink(
    val address: String,
    val name: String,
    val isBlocked: Boolean,
    val isInUse: Boolean
)

/**
 * Asks which RileyLink to block, and shows which ones already are.
 *
 * A block is per device rather than a single on/off switch, because blocking is for handing one
 * RileyLink to a laptop or a second phone while the others carry on as normal. The address is
 * always shown next to the name: two RileyLinks can report the same name, and the address is what
 * the block is keyed on.
 */
@Composable
fun RileyLinkBlockDialog(
    devices: List<BlockableRileyLink>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rileylink_block_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
            ) {
                Text(
                    text = stringResource(R.string.rileylink_block_message),
                    style = MaterialTheme.typography.bodySmall
                )
                if (devices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rileylink_block_none),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    devices.forEach { device ->
                        HorizontalDivider()
                        BlockableRileyLinkRow(device = device, onToggle = onToggle)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rileylink_diag_close)) }
        }
    )
}

@Composable
private fun BlockableRileyLinkRow(
    device: BlockableRileyLink,
    onToggle: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = device.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
            val tag = when {
                device.isBlocked -> stringResource(R.string.rileylink_block_blocked)
                device.isInUse   -> stringResource(R.string.rileylink_block_in_use)
                else             -> null
            }
            tag?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (device.isBlocked) AapsTheme.generalColors.statusCritical
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = { onToggle(device.address) }) {
            Text(
                text = stringResource(
                    if (device.isBlocked) R.string.rileylink_block_unblock_action
                    else R.string.rileylink_block_action
                ),
                color = if (device.isBlocked) MaterialTheme.colorScheme.primary
                else AapsTheme.generalColors.statusCritical
            )
        }
    }
}
