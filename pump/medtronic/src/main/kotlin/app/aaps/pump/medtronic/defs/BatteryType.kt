package app.aaps.pump.medtronic.defs

import androidx.annotation.StringRes
import app.aaps.pump.medtronic.R

/**
 * Created by andy on 6/4/18.
 */
@Suppress("SpellCheckingInspection")
enum class BatteryType(val key: String, @StringRes val friendlyName: Int, val lowVoltage: Double, val highVoltage: Double) {

    None("medtronic_pump_battery_no", R.string.medtronic_pump_battery_no, 0.0, 0.0),
    Alkaline("medtronic_pump_battery_alkaline", R.string.medtronic_pump_battery_alkaline, 1.20, 1.47),
    Lithium("medtronic_pump_battery_lithium", R.string.medtronic_pump_battery_lithium, 1.22, 1.64),
    NiZn("medtronic_pump_battery_nizn", R.string.medtronic_pump_battery_nizn, 1.40, 1.70),
    NiMH("medtronic_pump_battery_nimh", R.string.medtronic_pump_battery_nimh, 1.10, 1.40);

    /**
     * How much of this cell's usable range is left at [volts], from 0.0 to 1.0, or null for [None].
     *
     * A single voltage threshold cannot work across chemistries. NiZn is empty at 1.40 V while
     * NiMH still has charge at 1.15 V, so one number would either cry wolf on the second or stay
     * silent on the first. The fraction of the cell's own range is the comparable quantity.
     *
     * Why this matters beyond a battery gauge: measured on a 522 on the bench, an alkaline cell at
     * 1.25 V (18 % of its range) left the pump's radio 13 dB down, and a frequency scan found the
     * pump once in twenty four tries. The pump's own display still said half full. Low volts here
     * are a radio problem before they are a power problem.
     */
    fun fractionRemaining(volts: Double): Double? {
        val span = highVoltage - lowVoltage
        if (span <= 0.0) return null
        return ((volts - lowVoltage) / span).coerceIn(0.0, 1.0)
    }

    companion object {

        fun getByKey(someKey: String) = BatteryType.entries.firstOrNull { someKey == it.key } ?: None
    }
}