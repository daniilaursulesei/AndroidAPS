package app.aaps.core.interfaces.logging

enum class LTag(val tag: String, val defaultValue: Boolean = true, val requiresRestart: Boolean = false) {
    CORE("CORE"),
    APS("APS"),
    AUTOSENS("AUTOSENS", defaultValue = false),
    AUTOMATION("AUTOMATION"),
    AUTOTUNE("AUTOTUNE", defaultValue = false),
    BGSOURCE("BGSOURCE"),
    CONFIGBUILDER("CONFIGBUILDER"),
    CONSTRAINTS("CONSTRAINTS"),
    DATABASE("DATABASE"),
    EVENTS("EVENTS", defaultValue = false, requiresRestart = true),
    GARMIN("GARMIN"),
    GLUCOSE("GLUCOSE", defaultValue = false),
    HTTP("HTTP"),
    LOCATION("LOCATION"),
    NOTIFICATION("NOTIFICATION"),
    NSCLIENT("NSCLIENT"),
    NSCLIENT_SYNC("NSCLIENT_SYNC", defaultValue = false),
    OHUPLOADER("OHUPLOADER"),
    PUMP("PUMP"),
    PUMPBTCOMM("PUMPBTCOMM", defaultValue = true),
    PUMPCOMM("PUMPCOMM"),
    PUMPEMULATOR("PUMPEMULATOR", defaultValue = false),
    PUMPQUEUE("PUMPQUEUE"),
    PROFILE("PROFILE"),
    // RileyLink diagnostics. Its own tag rather than part of PUMPBTCOMM so the short summary can be
    // kept while the very large raw Bluetooth trace is turned off.
    RLDIAG("RLDIAG"),
    SMS("SMS"),
    TIDEPOOL("TIDEPOOL"),
    UI("UI", defaultValue = false),
    WEAR("WEAR"),
    WIDGET("WIDGET"),
    WORKER("WORKER"),
    XDRIP("XDRIP"),
    XDRIP_SYNC("XDRIP_SYNC", defaultValue = false)
}