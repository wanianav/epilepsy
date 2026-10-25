package com.calmguard.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrototypeStateTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clear() { app.getSharedPreferences("calmguard", 0).edit().clear().commit() }

    @Test fun safeControlsRespectPreferencesAndPersist() {
        val state = PrototypeState(app)
        state.tryEvent("Autoplay")
        assertEquals("Autoplay controlled", state.events.first().title)
        state.updateSafe(state.safe.copy(enabled = false))
        state.tryEvent("Autoplay")
        assertEquals("Autoplay requested", state.events.first().title)
        val restored = PrototypeState(app)
        assertFalse(restored.safe.enabled)
        assertEquals(state.events.size, restored.events.size)
    }

    @Test fun cancellationPreventsCountdownCompletion() {
        val state = PrototypeState(app)
        state.startAlert()
        assertEquals(AlertStage.COUNTDOWN, state.alert)
        state.cancelAlert()
        shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.SECONDS)
        assertEquals(AlertStage.IDLE, state.alert)
        assertFalse(state.events.any { it.title == "Test alert completed" })
    }

    @Test fun completionIsLocalAndInterruptedTestsDoNotEscalate() {
        val state = PrototypeState(app)
        state.startAlert()
        state.finishAlert()
        assertEquals(AlertStage.COMPLETE, state.alert)
        assertTrue(state.events.first().detail.contains("No SMS"))
        state.dismissAlert()
        state.startAlert()
        val restored = PrototypeState(app)
        assertEquals(AlertStage.IDLE, restored.alert)
        assertTrue(restored.events.any { it.title == "Test interrupted" })
        state.cancelAlert()
    }

    @Test fun countdownExpiresToLocalPreview() {
        val state = PrototypeState(app)
        state.startAlert()
        shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertEquals(AlertStage.COMPLETE, state.alert)
        assertEquals(0, state.remaining)
        assertTrue(state.events.first().detail.contains("No SMS"))
    }

    @Test fun choosingNewSampleClearsOldScoreAndCsvEscapesQuotes() {
        val state = PrototypeState(app)
        state.analyseCurrent()
        assertNotNull(state.score)
        state.choose(1)
        assertNull(state.score)
        assertEquals(0, state.windowIndex)
        state.log("Quoted \"title\"", "line\none", "sample")
        assertTrue(state.exportCsv().contains("\"Quoted \"\"title\"\"\""))
    }
}
