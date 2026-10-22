package space.einundzwanzig.meetup.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDebouncerTest {

    @Test
    fun `first value passes`() {
        assertTrue(ScanDebouncer().accept("a", 0))
    }

    @Test
    fun `same value is suppressed inside the window`() {
        val d = ScanDebouncer(sameValueWindowMillis = 1500, minIntervalMillis = 250)
        assertTrue(d.accept("a", 1_000))
        assertFalse(d.accept("a", 1_300))
        assertFalse(d.accept("a", 2_499))
        assertTrue(d.accept("a", 2_500))
    }

    @Test
    fun `different value passes after the minimum interval`() {
        val d = ScanDebouncer(sameValueWindowMillis = 1500, minIntervalMillis = 250)
        assertTrue(d.accept("a", 0))
        assertFalse(d.accept("b", 100))
        assertTrue(d.accept("b", 250))
    }

    @Test
    fun `stop blocks everything until reset`() {
        val d = ScanDebouncer(0, 0)
        assertTrue(d.accept("a", 0))
        d.stop()
        assertTrue(d.stopped)
        assertFalse(d.accept("b", 10_000))
        d.reset()
        assertFalse(d.stopped)
        assertTrue(d.accept("a", 10_001))
    }

    @Test
    fun `reset forgets the last value`() {
        val d = ScanDebouncer(sameValueWindowMillis = 10_000, minIntervalMillis = 0)
        assertTrue(d.accept("a", 0))
        d.reset()
        assertTrue(d.accept("a", 1))
    }

    @Test
    fun `scanner state stops after the consumer accepts and resumes on demand`() {
        val state = QrScannerState(ScanDebouncer(0, 0))
        val seen = mutableListOf<String>()
        val consumer: (String) -> Boolean = { seen += it; it.startsWith("21v3:") }

        assertFalse(state.deliver(listOf("hello"), 0, consumer))
        assertTrue(state.deliver(listOf("noise", "21v3:abc", "21v3:later"), 10, consumer))
        assertEquals(listOf("hello", "noise", "21v3:abc"), seen)
        assertTrue(state.isStopped)

        assertFalse(state.deliver(listOf("21v3:again"), 20, consumer))
        assertEquals(3, seen.size)

        state.resume()
        assertFalse(state.isStopped)
        assertTrue(state.deliver(listOf("21v3:again"), 30, consumer))
    }

    @Test
    fun `scanner state debounces repeated frames`() {
        val state = QrScannerState(ScanDebouncer(sameValueWindowMillis = 1500, minIntervalMillis = 0))
        var calls = 0
        repeat(30) { frame -> state.deliver(listOf("npub1x"), frame * 33L, { calls++; false }) }
        assertEquals(1, calls)
    }
}
