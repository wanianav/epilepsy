package com.calmguard.app

import kotlin.math.sqrt

/** Detects repeated strong reversals of the phone's motion using accelerometer readings in m/s². */
internal class PhoneShakeDetector {
    private companion object {
        const val GRAVITY_FILTER_NS = 250_000_000L
        const val MAX_SAMPLE_GAP_NS = 200_000_000L
        const val MIN_REVERSAL_GAP_NS = 80_000_000L
        const val MAX_REVERSAL_GAP_NS = 550_000_000L
        const val MIN_SHAKING_NS = 1_800_000_000L
        const val MIN_REVERSALS = 6
        const val MIN_ACCELERATION = 8f
    }

    private var lastSample = 0L
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var directionX = 0f
    private var directionY = 0f
    private var directionZ = 0f
    private var firstExcursion = 0L
    private var lastExcursion = 0L
    private var reversals = 0
    private var triggered = false

    private fun resetSequence() {
        lastExcursion = 0L
        reversals = 0
        triggered = false
    }

    fun add(time: Long, x: Float, y: Float, z: Float): Boolean {
        if (time <= lastSample || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        val gap = time - lastSample
        if (lastSample == 0L || gap > MAX_SAMPLE_GAP_NS) {
            gravityX = x
            gravityY = y
            gravityZ = z
            lastSample = time
            resetSequence()
            return false
        }
        lastSample = time
        val weight = gap.toFloat() / (GRAVITY_FILTER_NS + gap)
        gravityX += weight * (x - gravityX)
        gravityY += weight * (y - gravityY)
        gravityZ += weight * (z - gravityZ)

        val dx = x - gravityX
        val dy = y - gravityY
        val dz = z - gravityZ
        val squared = dx * dx + dy * dy + dz * dz
        if (time - lastExcursion > MAX_REVERSAL_GAP_NS) resetSequence()
        if (squared < MIN_ACCELERATION * MIN_ACCELERATION) return false

        val magnitude = sqrt(squared)
        if (lastExcursion == 0L) {
            firstExcursion = time
        } else {
            val projection = dx * directionX + dy * directionY + dz * directionZ
            if (time - lastExcursion < MIN_REVERSAL_GAP_NS || projection > -0.5f * magnitude) return false
            reversals++
        }
        directionX = dx / magnitude
        directionY = dy / magnitude
        directionZ = dz / magnitude
        lastExcursion = time
        if (triggered || reversals < MIN_REVERSALS || time - firstExcursion < MIN_SHAKING_NS) return false
        triggered = true
        return true
    }
}
