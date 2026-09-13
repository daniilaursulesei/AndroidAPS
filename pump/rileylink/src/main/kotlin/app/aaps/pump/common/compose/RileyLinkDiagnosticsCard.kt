package app.aaps.pump.common.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.pump.common.hw.rileylink.R
import app.aaps.pump.common.hw.rileylink.diagnostics.ChipState
import app.aaps.pump.common.hw.rileylink.diagnostics.VersionSource

/**
 * What the diagnostics card shows.
 *
 * All values arrive already formatted as text. The card only lays them out, so nothing on screen
 * depends on a locale or a date helper that this module would otherwise have to reach for.
 */
data class RileyLinkDiagnosticsUiState(
    val linkUp: Boolean,
    val ble113Version: String?,
    val chipState: ChipState,
    val silentStreak: Int,
    val silentSince: String?,
    val firmwareVersion: String?,
    val versionSource: VersionSource,
    val protocolFormat: String,
    val encoding: String?,
    val lastCommandName: String?,
    val lastCommandHex: String?,
    val lastCommandDetail: String?,
    val lastCommandAt: String?,
    val lastResponseHex: String?,
    val lastResponseAt: String?,
    val noResponseWaited: String?,
    val gattBusy: Boolean,
    val readerQueue: Int,
    val pendingPermits: Int,
    val commandQueue: Int,
    val unexpectedDisconnects: Int,
    val gattWriteTimeouts: Int,
    val writesRefused: Int,
    val versionSlips: Int,
    val concurrentInitPeak: Int
)

/**
 * A single card showing the state of both RileyLink chips and of the app's own plumbing.
 *
 * The two chips are shown separately on purpose. A RileyLink is a BLE113 that talks to the phone
 * and a CC1110 that owns the radio, joined by a serial link. They fail independently, and in the
 * logs that led to this screen the BLE113 kept answering perfectly while the CC1110 was off the
 * air. One combined "connected" light cannot say that, and reading it as one device sends you
 * looking in the wrong place.
 *
 * The last command is shown twice: as bytes, and as the values the radio will act on. A hex dump
 * hides a wire format mismatch completely, because the same bytes are a 25 second listen in one
 * format and a listen of over an hour in the other.
 */
@Composable
fun RileyLinkDiagnosticsCard(
    state: RileyLinkDiagnosticsUiState,
    modifier: Modifier = Modifier
) {
    AapsCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)
        ) {
            Text(
                text = stringResource(R.string.rileylink_diag_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            HorizontalDivider()

            StatusRow(
                label = stringResource(R.string.rileylink_diag_ble113),
                value = state.ble113Version
                    ?: stringResource(if (state.linkUp) R.string.rileylink_diag_link_up else R.string.rileylink_diag_link_down),
                dot = if (state.linkUp) AapsTheme.generalColors.statusNormal else AapsTheme.generalColors.statusCritical
            )

            StatusRow(
                label = stringResource(R.string.rileylink_diag_cc1110),
                value = when (state.chipState) {
                    ChipState.RESPONDING -> stringResource(R.string.rileylink_diag_chip_responding)
                    ChipState.SILENT     -> stringResource(R.string.rileylink_diag_chip_silent)
                    ChipState.UNKNOWN    -> stringResource(R.string.rileylink_diag_chip_unknown)
                },
                dot = when (state.chipState) {
                    ChipState.RESPONDING -> AapsTheme.generalColors.statusNormal
                    ChipState.SILENT     -> AapsTheme.generalColors.statusCritical
                    ChipState.UNKNOWN    -> AapsTheme.generalColors.statusWarning
                }
            )
            if (state.chipState == ChipState.SILENT) {
                DetailText(stringResource(R.string.rileylink_diag_silent_streak, state.silentStreak))
                state.silentSince?.let { DetailText(stringResource(R.string.rileylink_diag_since, it)) }
            }

            StatusRow(
                label = stringResource(R.string.rileylink_diag_firmware),
                value = state.firmwareVersion ?: stringResource(R.string.rileylink_diag_none),
                dot = when (state.versionSource) {
                    VersionSource.RADIO    -> AapsTheme.generalColors.statusNormal
                    VersionSource.CACHE    -> AapsTheme.generalColors.statusWarning
                    VersionSource.FALLBACK -> AapsTheme.generalColors.statusCritical
                    VersionSource.NONE     -> AapsTheme.generalColors.statusWarning
                }
            )
            DetailText(
                when (state.versionSource) {
                    VersionSource.RADIO    -> stringResource(R.string.rileylink_diag_source_radio)
                    VersionSource.CACHE    -> stringResource(R.string.rileylink_diag_source_cache)
                    VersionSource.FALLBACK -> stringResource(R.string.rileylink_diag_source_fallback)
                    VersionSource.NONE     -> stringResource(R.string.rileylink_diag_source_none)
                }
            )
            if (state.versionSource == VersionSource.FALLBACK) {
                Text(
                    text = stringResource(R.string.rileylink_diag_fallback_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = AapsTheme.generalColors.statusCritical
                )
            }

            Spacer(Modifier.size(AapsSpacing.small))
            HorizontalDivider()

            LabelRow(stringResource(R.string.rileylink_diag_last_command), state.lastCommandAt)
            MonoText(state.lastCommandName?.let { name -> state.lastCommandHex?.let { "$name  $it" } ?: name })
            state.lastCommandDetail?.let {
                DetailText(stringResource(R.string.rileylink_diag_as_radio_reads))
                MonoText(it)
            }

            LabelRow(stringResource(R.string.rileylink_diag_last_response), state.lastResponseAt)
            if (state.lastResponseHex != null) {
                MonoText(state.lastResponseHex)
            } else {
                Text(
                    text = state.noResponseWaited?.let { stringResource(R.string.rileylink_diag_no_response, it) }
                        ?: stringResource(R.string.rileylink_diag_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = AapsTheme.generalColors.statusCritical
                )
            }

            Spacer(Modifier.size(AapsSpacing.small))
            HorizontalDivider()

            Text(
                text = stringResource(R.string.rileylink_diag_low_level),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            ValueRow(
                stringResource(R.string.rileylink_diag_gatt_operation),
                stringResource(if (state.gattBusy) R.string.rileylink_diag_gatt_busy else R.string.rileylink_diag_gatt_idle)
            )
            ValueRow(stringResource(R.string.rileylink_diag_reader_queue), state.readerQueue.toString())
            ValueRow(stringResource(R.string.rileylink_diag_pending_permits), state.pendingPermits.toString())
            ValueRow(stringResource(R.string.rileylink_diag_command_queue), state.commandQueue.toString())
            ValueRow(stringResource(R.string.rileylink_diag_protocol), state.protocolFormat)
            state.encoding?.let { ValueRow(stringResource(R.string.rileylink_diag_encoding), it) }

            Spacer(Modifier.size(AapsSpacing.small))
            HorizontalDivider()

            Text(
                text = stringResource(R.string.rileylink_diag_counters),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            ValueRow(stringResource(R.string.rileylink_diag_unexpected_disconnects), state.unexpectedDisconnects.toString())
            ValueRow(stringResource(R.string.rileylink_diag_gatt_timeouts), state.gattWriteTimeouts.toString())
            ValueRow(stringResource(R.string.rileylink_diag_writes_refused), state.writesRefused.toString())
            ValueRow(stringResource(R.string.rileylink_diag_version_slips), state.versionSlips.toString())
            ValueRow(stringResource(R.string.rileylink_diag_concurrent_init_peak), state.concurrentInitPeak.toString())
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(AapsSpacing.medium).background(dot, CircleShape))
        Spacer(Modifier.width(AapsSpacing.medium))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabelRow(label: String, trailing: String?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MonoText(text: String?) {
    if (text == null) return
    Text(text = text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}
