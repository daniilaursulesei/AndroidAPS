package app.aaps.pump.common.hw.rileylink.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RadioSessionTest {

    @Test fun `a free radio is given out at once`() {
        val session = RadioSession()
        assertTrue(session.tryTake(0))
        session.release()
    }

    @Test fun `only one job can hold the radio`() {
        val session = RadioSession()
        assertTrue(session.tryTake(0))
        val secondJobGotIt = AtomicBoolean(true)
        val done = CountDownLatch(1)
        Thread {
            secondJobGotIt.set(session.tryTake(50))
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(secondJobGotIt.get(), "the second job must not get a radio that is already taken")
        session.release()
    }

    @Test fun `the radio can be taken again once it is given back`() {
        val session = RadioSession()
        assertTrue(session.tryTake(0))
        session.release()
        val secondJobGotIt = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            secondJobGotIt.set(session.tryTake(1000))
            if (secondJobGotIt.get()) session.release()
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(secondJobGotIt.get())
    }

    @Test fun `a job that waits gets the radio when the holder is done`() {
        val session = RadioSession()
        assertTrue(session.tryTake(0))
        val secondJobGotIt = AtomicBoolean(false)
        val done = CountDownLatch(1)
        Thread {
            secondJobGotIt.set(session.tryTake(5000))
            if (secondJobGotIt.get()) session.release()
            done.countDown()
        }.start()
        Thread.sleep(50)
        session.release()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(secondJobGotIt.get(), "the waiting job must be served as soon as the radio is free")
    }

    @Test fun `releasing from another thread does not take the radio away from its holder`() {
        val session = RadioSession()
        assertTrue(session.tryTake(0))
        val done = CountDownLatch(1)
        Thread {
            session.release()
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(session.busy, "the radio still belongs to the thread that took it")
        session.release()
        assertFalse(session.busy)
    }

    @Test fun `the link starts at a known generation`() {
        val session = RadioSession()
        assertEquals(0, session.generation)
        assertTrue(session.stillOnSameLink(0))
    }

    @Test fun `every link change makes the generation go up`() {
        val session = RadioSession()
        assertEquals(1, session.linkChanged())
        assertEquals(2, session.linkChanged())
        assertEquals(2, session.generation)
    }

    @Test fun `a job that started on an older link knows its link is gone`() {
        val session = RadioSession()
        val startedOn = session.generation
        assertTrue(session.stillOnSameLink(startedOn))
        session.linkChanged()
        assertFalse(session.stillOnSameLink(startedOn), "a disconnect alone already makes the result stale")
        session.linkChanged()
        assertFalse(session.stillOnSameLink(startedOn), "and a reconnect does not bring the old link back")
    }

    @Test fun `a job that started on the link that is up now carries on`() {
        val session = RadioSession()
        session.linkChanged()
        session.linkChanged()
        val startedOn = session.generation
        assertTrue(session.stillOnSameLink(startedOn))
    }

    @Test fun `taking and giving back the radio never changes the link`() {
        val session = RadioSession()
        val startedOn = session.generation
        assertTrue(session.tryTake(0))
        session.release()
        assertTrue(session.stillOnSameLink(startedOn))
    }
}
