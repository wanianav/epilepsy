package com.calmguard.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PhoneShakeDetectorTest {
    @Test fun strongRepeatedMotionTriggersOnceUntilItSettles() {
        val detector = PhoneShakeDetector()
        assertEquals(1, feed(detector, 0, 300) { sway(it, 15.0) })
        assertEquals(0, feed(detector, 300, 400) { 0f })
        assertEquals(1, feed(detector, 400, 600) { sway(it, 15.0) })
    }

    @Test fun ordinaryLowAmplitudeMotionAndIsolatedImpulseStayQuiet() {
        val detector = PhoneShakeDetector()
        assertEquals(0, feed(detector, 0, 200) { sway(it, 4.0) })
        assertEquals(0, feed(detector, 200, 300) {
            when (it) {
                225 -> 33f
                226 -> -22f
                else -> 0f
            }
        })
    }

    @Test fun briefShakesSeparatedBySamplingGapDoNotCombine() {
        val detector = PhoneShakeDetector()
        assertEquals(0, feed(detector, 0, 70) { sway(it, 15.0) })
        assertEquals(0, feed(detector, 175, 245) { sway(it, 15.0) })
    }

    private fun feed(detector: PhoneShakeDetector, start: Int, end: Int, x: (Int) -> Float): Int =
        (start until end).count {
            detector.add((it + 1) * 20_000_000L, x(it), 0f, 9.81f)
        }

    private fun sway(index: Int, amplitude: Double) = (amplitude * sin(index * 0.02 * 3.0 * 2 * PI)).toFloat()
}
