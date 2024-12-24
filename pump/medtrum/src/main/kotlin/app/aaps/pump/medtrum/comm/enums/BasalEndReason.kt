package app.aaps.pump.medtrum.comm.enums

enum class BasalEndReason {
    SUCCESS,
    SUSPEND_LOW_GLUCOSE,
    SUSPEND_PREDICT_LOW_GLUCOSE,
    SUSPEND_AUTO,
    SUSPEND_MORE_THAN_MAX_PER_HOUR,
    SUSPEND_MORE_THAN_MAX_PER_DAY,
    SUSPEND_MANUAL,
    STOP_OCCLUSION,
    STOP_EXPIRED,
    STOP_EMPTY,
    STOP_PATCH_FAULT,
    STOP_PATCH_FAULT2,
    STOP_BASE_FAULT,
    STOP_PATCH_BATTERY_EMPTY,
    STOP_MAG_SENSOR_NO_CALIBRATION,
    STOP,
    STOP_LOW_BATTERY,
    STOP_AUTO_EXIT,
    STOP_CANCEL,
    STOP_LOW_SUPER_CAPACITOR,
    STOP_DISCARD,
    PAUSE_INTERRUPT,
    AUTO_MODE_EXIT,
    AUTO_MODE_EXIT_MIN_DELIVERY_TOO_LONG,
    AUTO_MODE_EXIT_NO_GLUCOSE_3_HOUR,
    AUTO_MODE_EXIT_MAX_DELIVERY_TOO_LONG;

    fun isSuspendedByPump(): Boolean {
        return this in SUSPEND_LOW_GLUCOSE..STOP_DISCARD
    }
}