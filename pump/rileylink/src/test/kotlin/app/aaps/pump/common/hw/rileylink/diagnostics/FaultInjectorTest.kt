package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.core.interfaces.logging.AAPSLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * These tests are the safety argument for shipping fault injection at all, so they check the three
 * properties that keep it from hurting anyone: it fires once, it fires only for what was asked,
 * and it gives up on its own.
 */
class FaultInjectorTest {

    private lateinit var injector: FaultInjector

    @BeforeEach fun setup() {
        injector = FaultInjector(mock<AAPSLogger>())
    }

    @Test fun `nothing fires unless it was armed`() {
        InjectableFault.entries.forEach { assertFalse(injector.consume(it), "fired unarmed: $it") }
        assertNull(injector.armed.value)
    }

    @Test fun `an armed fault fires exactly once`() {
        injector.arm(InjectableFault.VERSION_READ_FAILS)
        assertTrue(injector.consume(InjectableFault.VERSION_READ_FAILS))
        assertFalse(injector.consume(InjectableFault.VERSION_READ_FAILS), "fired twice")
        assertNull(injector.armed.value)
    }

    @Test fun `arming one fault does not fire another`() {
        injector.arm(InjectableFault.VERSION_READ_FAILS)
        assertFalse(injector.consume(InjectableFault.LINK_DROPS))
        assertTrue(injector.consume(InjectableFault.VERSION_READ_FAILS), "the armed one was lost")
    }

    @Test fun `only one fault can wait at a time`() {
        injector.arm(InjectableFault.VERSION_READ_FAILS)
        injector.arm(InjectableFault.LINK_DROPS)
        assertFalse(injector.consume(InjectableFault.VERSION_READ_FAILS))
        assertTrue(injector.consume(InjectableFault.LINK_DROPS))
    }

    @Test fun `disarming clears it`() {
        injector.arm(InjectableFault.LINK_DROPS)
        injector.disarm()
        assertFalse(injector.consume(InjectableFault.LINK_DROPS))
        assertNull(injector.armed.value)
    }

    @Test fun `arming is visible so the screen can warn about it`() {
        injector.arm(InjectableFault.VERSION_READ_BIT_SLIP)
        assertNotNull(injector.armed.value)
        injector.consume(InjectableFault.VERSION_READ_BIT_SLIP)
        assertNull(injector.armed.value)
    }

    /**
     * The bytes injected for a slip must be bytes the detector actually recognises, or the test
     * proves nothing about the real fault.
     */
    @Test fun `the injected slipped reply is one the detector finds`() {
        val slip = VersionSlip.detect(FaultInjector.BIT_SLIPPED_VERSION_REPLY)
        assertNotNull(slip)
        assertTrue(slip!!.recovered.contains("bg_rfspy"), "was: ${slip.recovered}")
    }
}
