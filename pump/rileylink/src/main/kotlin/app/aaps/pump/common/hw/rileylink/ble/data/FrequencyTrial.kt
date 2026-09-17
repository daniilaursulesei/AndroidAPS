package app.aaps.pump.common.hw.rileylink.ble.data

import kotlin.math.abs

/**
 * What one frequency scored during a tune up.
 *
 * Every try must put a value in [rssiList], including the ones that heard nothing. The average is
 * what picks the frequency the pump will be driven on, so a try left out of it is a miss that
 * costs nothing, and a frequency that answered two times out of three could beat one that
 * answered every time.
 */
class FrequencyTrial {

    var tries = 0
    var successes = 0
    var averageRSSI = 0.0
    var frequencyMHz = 0.0

    /** One entry per try, in order. A try that heard nothing scores [NO_ANSWER_RSSI]. */
    var rssiList: ArrayList<Int> = ArrayList()

    fun calculateAverage() {
        var sum = 0
        var count = 0
        for (rssi in rssiList) {
            sum += abs(rssi)
            count++
        }
        averageRSSI =
            if (count != 0) -sum / count.toDouble()
            else NO_ANSWER_RSSI.toDouble()
    }

    companion object {

        /**
         * The score for a try that brought back no usable answer.
         *
         * Far below any real reading - the weakest the radio can report is about -84 dBm - so one
         * miss outweighs a small difference in signal strength between two frequencies.
         */
        const val NO_ANSWER_RSSI = -99
    }
}
