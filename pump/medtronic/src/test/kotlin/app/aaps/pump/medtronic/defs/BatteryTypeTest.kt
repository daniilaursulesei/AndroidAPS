package app.aaps.pump.medtronic.defs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * How much of a cell is left, judged against that cell's own range.
 *
 * The bench case this exists for: an alkaline AAA at 1.25 V. The pump reported its battery status
 * as normal and its display said half full, while the radio was 13 dB down and a frequency scan
 * found the pump once in twenty four tries. 1.25 V has to come out low here, or the screen repeats
 * the pump's own optimism.
 */
class BatteryTypeTest {

    private fun percent(type: BatteryType, volts: Double): Int? =
        type.fractionRemaining(volts)?.let { (it * 100).toInt() }

    @Test fun `the bench cell at 1_25 V reads low, not half full`() {
        assertThat(percent(BatteryType.Alkaline, 1.25)).isEqualTo(18)
    }

    @Test fun `a fresh alkaline cell reads high`() {
        // The replacement cell measured 1.46 V and the radio came back to -40 dBm.
        assertThat(percent(BatteryType.Alkaline, 1.46)).isEqualTo(96)
    }

    @Test fun `the middle of the alkaline range is half`() {
        assertThat(percent(BatteryType.Alkaline, 1.335)).isEqualTo(50)
    }

    @Test fun `each chemistry is judged against its own range`() {
        // 1.27 V is the case that proves a single threshold cannot work: flat for NiZn, low but
        // alive for alkaline, and still most of the way up for NiMH.
        assertThat(percent(BatteryType.NiZn, 1.27)).isEqualTo(0)
        assertThat(percent(BatteryType.Alkaline, 1.27)).isEqualTo(25)
        assertThat(percent(BatteryType.NiMH, 1.27)).isEqualTo(56)
    }

    @Test fun `below the low voltage reads empty rather than negative`() {
        assertThat(percent(BatteryType.Alkaline, 0.5)).isEqualTo(0)
    }

    @Test fun `above the high voltage reads full rather than over full`() {
        assertThat(percent(BatteryType.Alkaline, 2.0)).isEqualTo(100)
    }

    @Test fun `no battery type has no range to scale against`() {
        assertNull(BatteryType.None.fractionRemaining(1.25))
    }

    @Test fun `every chemistry except None has a usable range`() {
        BatteryType.entries.filter { it != BatteryType.None }.forEach { type ->
            assertThat(type.highVoltage).isGreaterThan(type.lowVoltage)
            assertThat(type.fractionRemaining(type.lowVoltage)).isEqualTo(0.0)
            assertThat(type.fractionRemaining(type.highVoltage)).isEqualTo(1.0)
        }
    }
}
