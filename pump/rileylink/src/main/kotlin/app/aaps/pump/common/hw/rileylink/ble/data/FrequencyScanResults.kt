package app.aaps.pump.common.hw.rileylink.ble.data

class FrequencyScanResults {

    var trials: MutableList<FrequencyTrial> = ArrayList()
    var bestFrequencyMHz = 0.0
    var dateTime: Long = 0

    /**
     * Worst first, so the best frequency is the last one.
     *
     * The tie break was `(f1 - f2).toInt()`, which is zero for every pair the scan actually
     * compares: the steps are 0.05 MHz apart, so the difference truncates away and two
     * frequencies with the same score never got ordered at all.
     */
    fun sort() {
        trials.sortWith(
            compareBy<FrequencyTrial> { it.averageRSSI }.thenBy { it.frequencyMHz }
        )
    }
}
