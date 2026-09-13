package app.aaps.pump.common.hw.rileylink.ble.defs

/**
 * Whether the RileyLink should be talked to with the version 2 command format.
 *
 * Version 2 made the `SendAndListen` delay field two bytes instead of one and added a two byte
 * preamble field. If we send the older, shorter layout to a version 2 radio, every field after the
 * delay is read one byte too early. A 25 second listen is then read as a listen of more than an
 * hour, the radio stays busy for that whole time and answers nothing. That looks exactly like
 * broken hardware, and only removing power ends it.
 *
 * So [RileyLinkFirmwareVersionBase.UnknownVersion] has to mean "assume version 2":
 *  - Guessing version 2 on a real version 1 radio gives one command the radio rejects at once.
 *  - Guessing version 1 on a version 2 radio takes the radio off the air for hours.
 *
 * The two mistakes are not equal, so the unknown case must fall to the safe side. Every RileyLink
 * built in the last several years runs version 2 or newer, so the safe guess is also the likely one.
 *
 * Use this in every place that has to pick a wire format, so the choice cannot drift between the
 * command we build, the reply we parse and the encoding we ask the radio for.
 */
fun RileyLinkFirmwareVersionBase?.usesV2Protocol(): Boolean =
    this == null ||
        this == RileyLinkFirmwareVersionBase.UnknownVersion ||
        isSameVersion(RileyLinkFirmwareVersion.Version2AndHigher)
