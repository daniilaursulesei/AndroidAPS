package app.aaps.pump.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.pump.common.hw.rileylink.R
import app.aaps.pump.common.hw.rileylink.diagnostics.CheckOutcome
import app.aaps.pump.common.hw.rileylink.diagnostics.DiagnosisReport
import app.aaps.pump.common.hw.rileylink.diagnostics.RepairAction
import app.aaps.pump.common.hw.rileylink.diagnostics.RepairResult

/**
 * What the check is doing right now.
 *
 * @property runningRepair the repair in progress, so its own button can show it rather than the
 *   whole dialog going blank.
 */
data class DiagnosisUiState(
    val running: Boolean = false,
    val report: DiagnosisReport? = null,
    val repairResults: List<RepairResult> = emptyList(),
    val runningRepair: RepairAction? = null
)

/**
 * Shows what the check found and offers the repairs it suggests.
 *
 * Written to be read by someone whose pump has just stopped working, so it leads with one sentence
 * they can act on and puts the measurements underneath. The repairs are listed in the order they
 * should be tried, each saying plainly what it will do, because two of them interrupt pump
 * communication and the user should be the one deciding that.
 */
@Composable
fun RileyLinkDiagnosisDialog(
    state: DiagnosisUiState,
    onRepair: (RepairAction) -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.running && state.runningRepair == null) onDismiss() },
        title = { Text(stringResource(R.string.rileylink_diag_result_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
            ) {
                if (state.running && state.report == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(AapsSpacing.large))
                        Text(stringResource(R.string.rileylink_diag_checking))
                    }
                }

                state.report?.let { report ->
                    Text(
                        text = report.headline,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colourFor(report.worst)
                    )

                    report.checks.forEach { check ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(AapsSpacing.medium).background(colourFor(check.outcome), CircleShape))
                                Spacer(Modifier.width(AapsSpacing.medium))
                                Text(
                                    text = check.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = check.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = AapsSpacing.extraLarge)
                            )
                            check.detail.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = AapsSpacing.extraLarge)
                                )
                            }
                        }
                    }

                    if (report.suggestedRepairs.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.rileylink_diag_repair_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        report.suggestedRepairs.forEach { action ->
                            val done = state.repairResults.lastOrNull { it.action == action }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(text = action.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = action.what,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                done?.let {
                                    Text(
                                        text = it.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (it.succeeded) AapsTheme.generalColors.statusNormal else AapsTheme.generalColors.statusCritical
                                    )
                                }
                                TextButton(
                                    onClick = { onRepair(action) },
                                    enabled = state.runningRepair == null && !state.running
                                ) {
                                    Text(
                                        stringResource(
                                            if (state.runningRepair == action) R.string.rileylink_diag_repair_running
                                            else R.string.rileylink_diag_repair_run
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRecheck, enabled = !state.running && state.runningRepair == null) {
                Text(stringResource(R.string.rileylink_diag_recheck))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.running && state.runningRepair == null) {
                Text(stringResource(R.string.rileylink_diag_close))
            }
        }
    )
}

@Composable
private fun colourFor(outcome: CheckOutcome): Color = when (outcome) {
    CheckOutcome.OK      -> AapsTheme.generalColors.statusNormal
    CheckOutcome.WARNING -> AapsTheme.generalColors.statusWarning
    CheckOutcome.FAILED  -> AapsTheme.generalColors.statusCritical
    CheckOutcome.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}
