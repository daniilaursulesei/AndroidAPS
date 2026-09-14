package app.aaps.pump.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.pump.common.hw.rileylink.R
import app.aaps.pump.common.hw.rileylink.diagnostics.InjectableFault

/**
 * Asks the tester to confirm the pump is off the body, before anything can be broken on purpose.
 *
 * Separate from the fault list on purpose. Test mode makes insulin delivery fail deliberately, so
 * the confirmation is its own deliberate act rather than fine print above a row of buttons.
 */
@Composable
fun RileyLinkTestModeWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.rileylink_diag_test_warning_title),
                color = AapsTheme.generalColors.statusCritical
            )
        },
        text = { Text(stringResource(R.string.rileylink_diag_test_warning)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.rileylink_diag_test_confirm),
                    color = AapsTheme.generalColors.statusCritical
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rileylink_diag_test_cancel)) }
        }
    )
}

/**
 * The list of faults that can be armed.
 *
 * Each one reproduces something taken from a real log, and says what should be expected to happen,
 * so the tester can tell a working recovery from a broken one without reading the code.
 */
@Composable
fun RileyLinkTestModePickerDialog(
    onArm: (InjectableFault) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rileylink_diag_test_mode)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
            ) {
                Text(
                    text = stringResource(R.string.rileylink_diag_test_pick),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                InjectableFault.entries.forEach { fault ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(text = fault.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = fault.what,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { onArm(fault) }) {
                            Text(
                                text = stringResource(R.string.rileylink_diag_test_mode),
                                color = AapsTheme.generalColors.statusCritical
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.rileylink_diag_close)) }
        }
    )
}
