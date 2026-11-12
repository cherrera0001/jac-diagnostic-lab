package app.jac.diagnosticlab

import app.jac.diagnosticlab.telemetry.PollBand
import app.jac.diagnosticlab.telemetry.VehicleTelemetryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryEngineTest {

    @Test
    fun testTel001_nextDueReturnsAtMostOnePid() {
        val engine = VehicleTelemetryEngine { 1_000L }
        engine.plan(setOf(0x0C, 0x05, 0x0B), 500L)
        val first = engine.nextDue(1_000L)
        assertNotNull(first)
        // Sin registrar éxito, el mismo sigue "due", pero la API sigue devolviendo un solo Int
        val second = engine.nextDue(1_000L)
        assertEquals(first, second)
        assertTrue(second is Int)
    }

    @Test
    fun testTel002_fastBeatsSlowWhenBothDue() {
        val engine = VehicleTelemetryEngine { 0L }
        engine.plan(setOf(0x05, 0x0C), 500L) // 0x0C FAST, 0x05 SLOW
        val due = engine.nextDue(0L)
        assertEquals(0x0C, due)
        assertEquals(PollBand.FAST, VehicleTelemetryEngine.bandFor(0x0C))
        assertEquals(PollBand.SLOW, VehicleTelemetryEngine.bandFor(0x05))
    }

    @Test
    fun testTel003_effectiveHzFromRealGaps() {
        var now = 0L
        val engine = VehicleTelemetryEngine { now }
        engine.plan(setOf(0x0C), 200L)
        engine.recordSuccess(0x0C, 40, 0)
        engine.recordSuccess(0x0C, 40, 500)
        engine.recordSuccess(0x0C, 40, 1000)
        now = 1000L
        val stats = engine.stats(now)[0x0C]!!
        assertEquals(3, stats.sampleCount)
        assertEquals(2.0, stats.effectiveHz, 1e-9) // 2 gaps / 1000 ms
        assertEquals(500L, stats.minGapMs)
        assertEquals(500L, stats.maxGapMs)
    }

    @Test
    fun testTel004_pauseYieldsNull() {
        val engine = VehicleTelemetryEngine { 10L }
        engine.plan(setOf(0x0C), 500L)
        engine.pause()
        assertNull(engine.nextDue(10L))
        engine.resume()
        assertEquals(0x0C, engine.nextDue(10L))
    }

    @Test
    fun testTel005_mediumNotStarvedWhenDue() {
        var now = 0L
        val engine = VehicleTelemetryEngine { now }
        engine.plan(setOf(0x0C, 0x0B), 100L) // FAST 100, MEDIUM 200
        // Consume FAST several times
        repeat(3) {
            val pid = engine.nextDue(now)!!
            assertEquals(0x0C, pid)
            engine.recordSuccess(pid, 10, now)
            now += 100L
        }
        // At now=300, FAST last at 200 → due again at 300; MEDIUM never polled → due from start
        // Both due: FAST wins. Advance so FAST is fresh and MEDIUM still never polled... 
        // After last record at 200, now=300: FAST due (300-200>=100), MEDIUM due (never). FAST wins.
        engine.recordSuccess(0x0C, 10, now) // poll FAST at 300
        now = 301L
        // FAST last 300, not due until 400. MEDIUM never → due. Must be 0x0B.
        assertEquals(0x0B, engine.nextDue(now))
    }
}
