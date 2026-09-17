package app.aaps.pump.medtronic.compose

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.ActionCategory
import app.aaps.core.ui.compose.pump.PumpAction
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.common.compose.DiagEventLine
import app.aaps.pump.common.compose.DiagnosisUiState
import app.aaps.pump.common.compose.RileyLinkDiagnosticsUiState
import app.aaps.pump.common.events.EventRileyLinkDeviceStatusChange
import app.aaps.pump.common.extensions.stringResource
import app.aaps.pump.common.hw.rileylink.ble.RFSpy
import app.aaps.pump.common.compose.BlockableRileyLink
import app.aaps.pump.common.hw.rileylink.ble.RileyLinkBLE
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.hw.rileylink.diagnostics.DiagSeverity
import app.aaps.pump.common.hw.rileylink.diagnostics.FaultInjector
import app.aaps.pump.common.hw.rileylink.diagnostics.InjectableFault
import app.aaps.pump.common.hw.rileylink.diagnostics.RepairAction
import app.aaps.pump.common.hw.rileylink.diagnostics.RileyLinkSelfTest
import app.aaps.pump.common.hw.rileylink.diagnostics.RileyLinkDiag
import app.aaps.pump.common.hw.rileylink.diagnostics.RileyLinkDiagSnapshot
import app.aaps.pump.common.hw.rileylink.service.FirmwareVersionStore
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.common.hw.rileylink.service.tasks.ResetRileyLinkConfigurationTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.defs.MedtronicCommandType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.events.EventMedtronicPumpConfigurationChanged
import app.aaps.pump.medtronic.events.EventMedtronicPumpValuesChanged
import app.aaps.pump.medtronic.util.MedtronicUtil
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.Inject
import app.aaps.core.ui.R as CoreUiR
import app.aaps.pump.common.hw.rileylink.R as RileyLinkR

sealed class MedtronicOverviewEvent {
    data object ShowHistory : MedtronicOverviewEvent()
    data object ShowRileyLinkPairWizard : MedtronicOverviewEvent()
    data object ShowRileyLinkStats : MedtronicOverviewEvent()
    data class ShowDialog(val title: String, val message: String) : MedtronicOverviewEvent()
    data class ShowSnackbar(val message: String) : MedtronicOverviewEvent()
    data object ShowTestModeWarning : MedtronicOverviewEvent()
}

@Stable
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Inject
class MedtronicOverviewViewModel(
    private val rh: ResourceHelper,
    private val ch: ConcentrationHelper,
    private val medtronicPumpPlugin: MedtronicPumpPlugin,
    private val medtronicPumpStatus: MedtronicPumpStatus,
    private val medtronicUtil: MedtronicUtil,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val serviceTaskExecutor: ServiceTaskExecutor,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val resetRileyLinkConfigurationTaskProvider: () -> ResetRileyLinkConfigurationTask,
    private val wakeAndTuneTaskProvider: () -> WakeAndTuneTask,
    private val context: Context,
    private val rileyLinkDiag: RileyLinkDiag,
    private val rfSpy: RFSpy,
    private val rileyLinkBLE: RileyLinkBLE,
    private val firmwareVersionStore: FirmwareVersionStore,
    private val selfTest: RileyLinkSelfTest,
    private val faultInjector: FaultInjector
) : ViewModel() {

    companion object {

        private const val PLACEHOLDER = "-"
        private const val PROTOCOL_V1 = "v1"
        private const val PROTOCOL_V2 = "v2"
    }

    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, viewModelScope)

    private val _events = MutableSharedFlow<MedtronicOverviewEvent>(extraBufferCapacity = 5)
    val events: SharedFlow<MedtronicOverviewEvent> = _events

    private val medtronicRefresh = MutableStateFlow(0L).also { flow ->
        viewModelScope.launch {
            rxBus.toFlow(EventMedtronicPumpValuesChanged::class)
                .collect { flow.value = System.currentTimeMillis() }
        }
        viewModelScope.launch {
            rxBus.toFlow(EventRileyLinkDeviceStatusChange::class)
                .collect { flow.value = System.currentTimeMillis() }
        }
        viewModelScope.launch {
            rxBus.toFlow(EventMedtronicPumpConfigurationChanged::class)
                .collect {
                    aapsLogger.debug(LTag.PUMP, "EventMedtronicPumpConfigurationChanged triggered")
                    medtronicPumpPlugin.rileyLinkService?.verifyConfiguration()
                    flow.value = System.currentTimeMillis()
                }
        }
    }

    val uiState: StateFlow<PumpOverviewUiState> = combine(
        communicationStatus.refreshTrigger,
        medtronicRefresh,
        tickerFlow(60_000L)
    ) { _, _, _ -> buildUiState() }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = buildUiState()
    )

    /**
     * State for the RileyLink diagnostics card.
     *
     * Kept apart from [uiState] because it moves on a different clock: the pump overview is
     * rebuilt when pump values change or once a minute, while the diagnostics are meant to be
     * watched live while something is going wrong.
     */
    val diagnosticsState: StateFlow<RileyLinkDiagnosticsUiState> = combine(
        rileyLinkDiag.snapshot,
        tickerFlow(2_000L)
    ) { snapshot, _ -> buildDiagnosticsState(snapshot) }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = buildDiagnosticsState(rileyLinkDiag.snapshot.value)
    )

    private fun buildDiagnosticsState(snapshot: RileyLinkDiagSnapshot): RileyLinkDiagnosticsUiState =
        RileyLinkDiagnosticsUiState(
            linkUp = snapshot.linkUp,
            ble113Version = snapshot.ble113Version,
            chipState = snapshot.chipState,
            silentStreak = snapshot.silentStreak,
            silentSince = snapshot.silentSinceMillis?.let { dateUtil.timeString(it) },
            firmwareVersion = snapshot.firmwareVersion,
            versionSource = snapshot.versionSource,
            cachedFirmware = firmwareVersionStore.get(rileyLinkServiceData.rileyLinkAddress)?.name,
            protocolFormat = if (snapshot.protocolV2) PROTOCOL_V2 else PROTOCOL_V1,
            encoding = snapshot.encoding?.name,
            lastCommandName = snapshot.lastCommandName,
            lastCommandHex = snapshot.lastCommandHex,
            lastCommandDetail = snapshot.lastCommandDetail,
            lastCommandAt = snapshot.lastCommandAtMillis?.let { dateUtil.timeStringWithSeconds(it) },
            lastResponseHex = snapshot.lastResponseHex,
            lastResponseAt = snapshot.lastResponseAtMillis?.let { dateUtil.timeStringWithSeconds(it) },
            noResponseWaited = snapshot.lastWaitedMs
                ?.takeIf { snapshot.lastResponseHex == null }
                ?.let { rh.gs(RileyLinkR.string.rileylink_diag_millis, it.toInt()) },
            gattBusy = rileyLinkBLE.gattOperationBusy,
            readerQueue = rfSpy.queuedResponses,
            pendingPermits = rfSpy.pendingPermits,
            commandQueue = commandQueue.size(),
            unexpectedDisconnects = snapshot.unexpectedDisconnects,
            gattWriteTimeouts = snapshot.gattWriteTimeouts,
            writesRefused = snapshot.writesWhileLinkDown,
            blockedDevice = blockedDeviceLabel(),
            writesWhileBlocked = snapshot.writesWhileBlocked,
            versionSlips = snapshot.versionSlipsSeen,
            concurrentInitPeak = snapshot.concurrentInitPeak,
            armedFault = faultInjector.armed.value?.title,
            events = snapshot.events.map { event ->
                DiagEventLine(
                    time = dateUtil.timeStringWithSeconds(event.atMillis),
                    text = if (event.detail.isEmpty()) event.event else "${event.event}  ${event.detail}",
                    warn = event.severity == DiagSeverity.WARN
                )
            }
        )

    /** The RileyLinks offered by the block picker, or null while it is closed. */
    private val _blockPicker = MutableStateFlow<List<BlockableRileyLink>?>(null)
    val blockPicker: StateFlow<List<BlockableRileyLink>?> = _blockPicker

    private val _diagnosis = MutableStateFlow(DiagnosisUiState())

    /** State of the connection check dialog. Null report means it has not been run yet. */
    val diagnosis: StateFlow<DiagnosisUiState> = _diagnosis

    /**
     * Runs the connection check.
     *
     * Refused while a bolus is being delivered: the check talks to the radio, and nothing should
     * compete with insulin already on its way. Every other queue state is allowed, because a stuck
     * queue is exactly the situation this is for.
     */
    fun runDiagnosis() {
        if (commandQueue.isRunning(Command.CommandType.BOLUS)) {
            _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(RileyLinkR.string.rileylink_diag_busy_bolus)))
            return
        }
        _diagnosis.update { it.copy(running = true, repairResults = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val report = selfTest.run()
            _diagnosis.update { it.copy(running = false, report = report) }
        }
    }

    /** Carries out one of the repairs the check suggested, then leaves the result on screen. */
    fun runRepair(action: RepairAction) {
        _diagnosis.update { it.copy(runningRepair = action) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = selfTest.repair(action)
            _diagnosis.update { it.copy(runningRepair = null, repairResults = it.repairResults + result) }
        }
    }

    fun dismissDiagnosis() {
        _diagnosis.value = DiagnosisUiState()
    }

    /** Opens test mode, which always starts with the detach-the-pump warning. */
    fun openTestMode() {
        _events.tryEmit(MedtronicOverviewEvent.ShowTestModeWarning)
    }

    fun armFault(fault: InjectableFault) {
        faultInjector.arm(fault)
        _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(fault.title))
    }

    fun disarmFault() {
        faultInjector.disarm()
    }

    private fun buildUiState(): PumpOverviewUiState {
        return PumpOverviewUiState(
            statusBanner = communicationStatus.statusBanner(),
            infoRows = buildInfoRows(),
            primaryActions = buildPrimaryActions(),
            managementActions = buildManagementActions(),
            queueStatus = communicationStatus.queueStatus()
        )
    }

    // region Info Rows

    private fun buildInfoRows(): List<PumpInfoRow> = buildList {
        // RileyLink status
        val rlState = rileyLinkServiceData.rileyLinkServiceState
        val rlError = rileyLinkServiceData.rileyLinkError
        val rlStatusText = when {
            rlState == RileyLinkServiceState.NotStarted -> rh.gs(rlState.resourceId)
            rlState.isError() && rlError != null        -> rh.gs(rlError.getResourceId(RileyLinkTargetDevice.MedtronicPump))
            else                                        -> rh.gs(rlState.resourceId)
        }
        val rlLevel = if (rlState.isError() || rlError != null) StatusLevel.CRITICAL else StatusLevel.NORMAL
        add(PumpInfoRow(label = rh.gs(RileyLinkR.string.rileylink_status), value = rlStatusText, level = rlLevel))

        // A blocked RileyLink, one row each. Shown at the top and marked critical on purpose: while
        // this is here nothing is managing the pump, and a block that is quietly forgotten is the
        // worst outcome this feature has. Reading it from the stored list rather than from a field
        // means the row is right even straight after a restart.
        blockableDevices().filter { it.isBlocked }.forEach { device ->
            add(
                PumpInfoRow(
                    label = rh.gs(RileyLinkR.string.rileylink_block_status),
                    value = describeDevice(device),
                    level = StatusLevel.CRITICAL
                )
            )
        }

        // RileyLink battery (conditional)
        if (rileyLinkServiceData.showBatteryLevel) {
            val batteryText = rileyLinkServiceData.batteryLevel?.let { "$it%" } ?: "?"
            add(PumpInfoRow(label = rh.gs(R.string.rl_battery_label), value = batteryText))
        }

        // Pump status
        val pumpStatusText = buildPumpStatusText()
        add(PumpInfoRow(label = rh.gs(RileyLinkR.string.medtronic_pump_status), value = pumpStatusText))

        // Last connection
        val (lastConnText, lastConnLevel) = buildLastConnection()
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.last_connection_label), value = lastConnText, level = lastConnLevel))

        // Last bolus
        buildLastBolus()?.let {
            add(PumpInfoRow(label = rh.gs(CoreUiR.string.last_bolus_label), value = it))
        }

        // Base basal rate
        val basalText = "${ch.basalRateString(medtronicPumpPlugin.baseBasalRate, true)} (${medtronicPumpStatus.activeProfileName})"
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.base_basal_rate_label), value = basalText))

        // Temp basal
        val tbrText = buildTempBasal()
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.tempbasal_label), value = tbrText, visible = tbrText.isNotEmpty()))

        // Battery
        val (batteryText, batteryLevel) = buildBattery()
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.battery_label), value = batteryText, level = batteryLevel))

        // Reservoir
        val (reservoirText, reservoirLevel) = buildReservoir()
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.reservoir_label), value = reservoirText, level = reservoirLevel))

        // Errors
        val errorsText = medtronicPumpStatus.errorInfo
        val errorsLevel = if (errorsText != PLACEHOLDER) StatusLevel.CRITICAL else StatusLevel.NORMAL
        add(PumpInfoRow(label = rh.gs(CoreUiR.string.errors), value = errorsText, level = errorsLevel))
    }

    private fun buildPumpStatusText(): String {
        return when (medtronicPumpStatus.pumpDeviceState) {
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.Sleeping             ->
                rh.gs(medtronicPumpStatus.pumpDeviceState.stringResource())

            app.aaps.core.interfaces.pump.defs.PumpDeviceState.NeverContacted,
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.WakingUp,
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.PumpUnreachable,
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.ErrorWhenCommunicating,
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.TimeoutWhenCommunicating,
            app.aaps.core.interfaces.pump.defs.PumpDeviceState.InvalidConfiguration ->
                rh.gs(medtronicPumpStatus.pumpDeviceState.stringResource())

            app.aaps.core.interfaces.pump.defs.PumpDeviceState.Active               -> {
                val cmd = medtronicUtil.getCurrentCommand()
                if (cmd == null) {
                    rh.gs(medtronicPumpStatus.pumpDeviceState.stringResource())
                } else {
                    val cmdResourceId = cmd.resourceId
                    if (cmd == MedtronicCommandType.GetHistoryData) {
                        medtronicUtil.frameNumber?.let {
                            rh.gs(cmdResourceId!!, medtronicUtil.pageNumber, medtronicUtil.frameNumber)
                        } ?: rh.gs(R.string.medtronic_cmd_desc_get_history_request, medtronicUtil.pageNumber)
                    } else {
                        cmdResourceId?.let { rh.gs(it) } ?: cmd.commandDescription
                    }
                }
            }
        }
    }

    private fun buildLastConnection(): Pair<String, StatusLevel> {
        val lastConnection = medtronicPumpStatus.lastConnection
        if (lastConnection == 0L) return PLACEHOLDER to StatusLevel.NORMAL

        val min = (System.currentTimeMillis() - lastConnection) / 1000 / 60
        return when {
            lastConnection + 60 * 1000 > System.currentTimeMillis()      ->
                rh.gs(R.string.medtronic_pump_connected_now) to StatusLevel.NORMAL

            lastConnection + 30 * 60 * 1000 < System.currentTimeMillis() -> {
                val text = when {
                    min < 60   -> rh.gs(app.aaps.core.interfaces.R.string.minago, min)

                    min < 1440 -> {
                        val h = (min / 60).toInt()
                        rh.gq(RileyLinkR.plurals.duration_hours, h, h) + " " + rh.gs(R.string.ago)
                    }

                    else       -> {
                        val d = (min / 60 / 24).toInt()
                        rh.gq(RileyLinkR.plurals.duration_days, d, d) + " " + rh.gs(R.string.ago)
                    }
                }
                text to StatusLevel.WARNING
            }

            else                                                         ->
                dateUtil.minAgo(rh, lastConnection) to StatusLevel.NORMAL
        }
    }

    private fun buildLastBolus(): String? {
        val bolus = medtronicPumpStatus.lastBolusAmount?.let { PumpInsulin(it) }
        val bolusTime = medtronicPumpStatus.lastBolusTime
        if (bolus == null || bolusTime == null)
            return null
        return ch.insulinAmountAgoString(bolus, bolusTime.time)
    }

    private fun buildTempBasal(): String {
        val tempBasalAmount = medtronicPumpStatus.tempBasalAmount?.let { PumpRate(it) } ?: return ""
        val startTime = medtronicPumpStatus.tempBasalStart ?: return ""
        val duration = medtronicPumpStatus.tempBasalDuration ?: return ""
        return ch.basalTbrString(rate = tempBasalAmount, startTime = startTime, durationInMin = duration)
    }

    private fun buildBattery(): Pair<String, StatusLevel> {
        val remaining = medtronicPumpStatus.batteryRemaining
        val text = if (medtronicPumpStatus.batteryType == BatteryType.None || medtronicPumpStatus.batteryVoltage == null) {
            remaining?.let { "$it%" } ?: rh.gs(CoreUiR.string.unknown)
        } else {
            (remaining?.let { "$it%  " } ?: "") +
                String.format(Locale.getDefault(), "(%.2f V)", medtronicPumpStatus.batteryVoltage)
        }
        val level = when {
            remaining == null -> StatusLevel.NORMAL
            remaining <= 10   -> StatusLevel.CRITICAL
            remaining <= 25   -> StatusLevel.WARNING
            else              -> StatusLevel.NORMAL
        }
        return text to level
    }

    private fun buildReservoir(): Pair<String, StatusLevel> {
        val remaining = PumpInsulin(medtronicPumpStatus.reservoirRemainingUnits)
        val text = ch.insulinAmountString(remaining) // "/ $full U" removed
        val level = when {
            ch.fromPump(remaining) <= 20.0 -> StatusLevel.CRITICAL
            ch.fromPump(remaining) <= 50.0 -> StatusLevel.WARNING
            else                           -> StatusLevel.NORMAL
        }
        return text to level
    }

    // endregion

    // region Actions

    private fun buildPrimaryActions(): List<PumpAction> {
        return listOf(
            PumpAction(
                label = rh.gs(CoreUiR.string.refresh),
                icon = Icons.Filled.Refresh,
                onClick = { onRefreshClicked() }
            )
        )
    }

    private fun buildManagementActions(): List<PumpAction> {
        val isConfigured = medtronicPumpPlugin.rileyLinkService?.verifyConfiguration() == true

        return listOf(
            PumpAction(
                label = rh.gs(RileyLinkR.string.rileylink_pair),
                icon = Icons.Filled.Bluetooth,
                category = ActionCategory.MANAGEMENT,
                onClick = { _events.tryEmit(MedtronicOverviewEvent.ShowRileyLinkPairWizard) }
            ),
            PumpAction(
                label = rh.gs(CoreUiR.string.pump_history),
                icon = Icons.Filled.History,
                category = ActionCategory.MANAGEMENT,
                onClick = { _events.tryEmit(MedtronicOverviewEvent.ShowHistory) }
            ),
            PumpAction(
                label = rh.gs(R.string.riley_statistics),
                icon = Icons.Filled.Timeline,
                category = ActionCategory.MANAGEMENT,
                onClick = {
                    if (isConfigured) {
                        _events.tryEmit(MedtronicOverviewEvent.ShowRileyLinkStats)
                    } else {
                        emitNotConfiguredDialog()
                    }
                }
            ),
            PumpAction(
                label = rh.gs(R.string.medtronic_custom_action_wake_and_tune),
                icon = Icons.Filled.SettingsInputAntenna,
                category = ActionCategory.MANAGEMENT,
                onClick = {
                    if (isConfigured) {
                        serviceTaskExecutor.startTask(wakeAndTuneTaskProvider())
                        _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(R.string.medtronic_custom_action_wake_and_tune)))
                    } else {
                        emitNotConfiguredDialog()
                    }
                }
            ),
            PumpAction(
                label = rh.gs(R.string.medtronic_custom_action_clear_bolus_block),
                icon = Icons.Filled.Block,
                category = ActionCategory.MANAGEMENT,
                visible = medtronicPumpPlugin.isBusyBlockingEnabled(),
                onClick = {
                    medtronicPumpPlugin.clearBusyTimestamps()
                    _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(R.string.medtronic_custom_action_clear_bolus_block)))
                }
            ),
            PumpAction(
                label = rh.gs(RileyLinkR.string.rileylink_diag_run_check),
                icon = Icons.Filled.MonitorHeart,
                category = ActionCategory.MANAGEMENT,
                onClick = { runDiagnosis() }
            ),
            PumpAction(
                label = rh.gs(RileyLinkR.string.rileylink_diag_test_mode),
                icon = Icons.Filled.Science,
                category = ActionCategory.MANAGEMENT,
                onClick = { openTestMode() }
            ),
            PumpAction(
                label = blockLabel(),
                icon = Icons.Filled.BluetoothDisabled,
                category = ActionCategory.MANAGEMENT,
                onClick = { onBlockClicked() }
            ),
            PumpAction(
                label = rh.gs(R.string.medtronic_custom_action_reset_rileylink),
                icon = Icons.Filled.RestartAlt,
                category = ActionCategory.MANAGEMENT,
                onClick = {
                    serviceTaskExecutor.startTask(resetRileyLinkConfigurationTaskProvider())
                    _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(RileyLinkR.string.rileylink_config_reset)))
                }
            )
        )
    }

    // endregion

    // region Action handlers

    /** The button counts the blocked RileyLinks, so a block cannot be left on without being seen. */
    private fun blockLabel(): String {
        val blocked = rileyLinkServiceData.blockList.blocked().size
        return if (blocked > 0) rh.gs(RileyLinkR.string.rileylink_block_button_active, blocked)
        else rh.gs(RileyLinkR.string.rileylink_block_button)
    }

    /**
     * Opens the picker that asks which RileyLink to block.
     *
     * Blocking hands one RileyLink to something else - a laptop running a bench test, a second
     * phone - and it takes one Bluetooth connection at a time, so nothing else can reach it until
     * this app lets go. The block is kept per device and stored on the phone, so it survives a
     * restart and never reaches a RileyLink other than the one named. The pump is not managed
     * while the RileyLink it uses is blocked.
     */
    private fun onBlockClicked() {
        if (medtronicPumpPlugin.rileyLinkService == null) {
            emitNotConfiguredDialog()
            return
        }
        _blockPicker.value = blockableDevices()
    }

    /**
     * The RileyLinks the picker offers.
     *
     * The one this app is set up to use, plus every address already blocked. A blocked address has
     * to stay on the list even after the configured RileyLink changes, or there would be no way
     * left to unblock it.
     */
    private fun blockableDevices(): List<BlockableRileyLink> {
        val blockList = rileyLinkServiceData.blockList
        val blocked = blockList.blocked()
        val configured = blockList.configuredAddress()
        val addresses = LinkedHashSet<String>()
        configured?.let { addresses.add(it) }
        addresses.addAll(blocked)
        return addresses.map { address ->
            BlockableRileyLink(
                address = address,
                name = nameFor(address, configured),
                isBlocked = blocked.contains(address),
                isInUse = address == configured
            )
        }
    }

    /**
     * A name for a row in the picker.
     *
     * Only the configured RileyLink has a stored name, so a previously blocked one falls back to
     * its address. Showing the address twice is better than showing another device's name.
     */
    private fun nameFor(address: String, configured: String?): String {
        if (address != configured) return address
        return rileyLinkServiceData.blockList.configuredName() ?: address
    }

    /** Name and address together, the one way a RileyLink is written for the user. */
    private fun describeDevice(device: BlockableRileyLink): String =
        rh.gs(RileyLinkR.string.rileylink_block_status_value, device.name, device.address)

    /**
     * The blocked RileyLink for the diagnostics banner, or null when none is blocked.
     *
     * Read live rather than from the diagnostics snapshot, because the snapshot only learns about
     * a block when a command is refused, and the card has to say "blocked" straight away rather
     * than after the next command happens to come along.
     */
    private fun blockedDeviceLabel(): String? {
        if (!rileyLinkServiceData.isCurrentDeviceBlocked) return null
        val blockList = rileyLinkServiceData.blockList
        val address = rileyLinkServiceData.rileyLinkAddress ?: blockList.configuredAddress() ?: return null
        val configured = blockList.configuredAddress()
        return describeDevice(
            BlockableRileyLink(
                address = address,
                name = nameFor(address, configured),
                isBlocked = true,
                isInUse = address == configured
            )
        )
    }

    /** Blocks the chosen RileyLink, or unblocks it when it is already blocked. */
    fun toggleBlock(address: String) {
        val service = medtronicPumpPlugin.rileyLinkService
        if (service == null) {
            emitNotConfiguredDialog()
            return
        }
        val devices = _blockPicker.value ?: return
        val device = devices.firstOrNull { it.address == address } ?: return
        if (device.isBlocked) {
            service.unblockRileyLink(address)
            _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(RileyLinkR.string.rileylink_unblocked_message, device.name)))
        } else {
            service.blockRileyLink(address)
            _events.tryEmit(MedtronicOverviewEvent.ShowSnackbar(rh.gs(RileyLinkR.string.rileylink_blocked_message, device.name)))
        }
        // Rebuilt from the stored list rather than flipped in place, so what the picker shows is
        // what was actually written.
        _blockPicker.value = blockableDevices()
        // Blocking the RileyLink in use changes the service state, and the overview is rebuilt on
        // that event. Blocking any other one changes nothing the overview listens to, so the
        // button that counts the blocked devices is redrawn here instead.
        medtronicRefresh.value = System.currentTimeMillis()
    }

    /** Closes the picker. The blocks it set stay as they are. */
    fun dismissBlockPicker() {
        _blockPicker.value = null
    }

    private fun onRefreshClicked() {
        if (medtronicPumpPlugin.rileyLinkService?.verifyConfiguration() != true) {
            emitNotConfiguredDialog()
            return
        }
        medtronicPumpPlugin.resetStatusState()
        viewModelScope.launch { commandQueue.readStatus(rh.gs(R.string.clicked_refresh)) }
    }

    private fun emitNotConfiguredDialog() {
        _events.tryEmit(
            MedtronicOverviewEvent.ShowDialog(
                rh.gs(CoreUiR.string.warning),
                rh.gs(R.string.medtronic_error_operation_not_possible_no_configuration)
            )
        )
    }

    // endregion

}
