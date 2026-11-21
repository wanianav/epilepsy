package com.calmguard.app

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSensor
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            // Draw the native view tree. PixelCopy requires a physical display and
            // cannot complete its forced-redraw callback under Robolectric.
            val view = WindowInspector.getGlobalWindowViews().last()
            check(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/screenshots/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun openingTheAppStartsEnabledMotionMonitoring() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(app.getSystemService(SensorManager::class.java))
                .addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(LiveMotionService.START, shadowOf(app).nextStartedService?.action)
    }

    @Test fun navigationSampleAnalysisAndLocalAlertWork() {
        compose.onNodeWithText("A calmer\ndigital day.").assertIsDisplayed()
        shot("01-overview")
        compose.onNodeWithText("Safe Mode", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your view.\nYour controls.").assertIsDisplayed()
        shot("02-safe-mode")
        compose.onNodeWithText("Device display controls").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start device display mode").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Dimming does not remove flashing or establish seizure safety. Some apps and system screens hide overlays.").performScrollTo().assertIsDisplayed()
        shot("07-device-display")
        compose.onNodeWithText("Samples", useUnmergedTree = true).performClick()
        shot("03-samples")
        compose.onNodeWithText("Understand\nthe pattern.").assertIsDisplayed()
        compose.onNodeWithText("Analyse this window").performScrollTo().performClick()
        compose.onNodeWithText("History", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Sample window analysed").assertIsDisplayed()
        shot("04-history")
        compose.onNodeWithText("Overview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Start test alert").performScrollTo().performClick()
        compose.onNodeWithText("LOCAL DEMONSTRATION").assertIsDisplayed()
        shot("05-test-alert")
        compose.onNodeWithText("Cancel test alert").performClick()
        compose.onNodeWithText("LOCAL DEMONSTRATION").assertDoesNotExist()
        compose.onNodeWithContentDescription("Research details").performClick()
        compose.onNodeWithText("Know the data.\nKnow the limits.").assertIsDisplayed()
        shot("06-research")
    }

    @Test fun caregiverSetupSavesANumberWithoutTurningOnAutomaticTexts() {
        compose.onNodeWithText("Phone motion").assertIsDisplayed()
        compose.onNodeWithText("Caregiver settings").performScrollTo().performClick()
        val firstNumber = compose.onNodeWithText("Caregiver 1 phone number")
        firstNumber.performScrollTo().performTextInput("+1 202 555 0101")
        compose.onNodeWithText("Save caregiver numbers").performScrollTo().performClick()
        compose.onNodeWithText("+12025550101").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Automatic texting off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Text first caregiver now").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Caregiver numbers").performScrollTo()
        shot("08-caregiver-settings")
    }
}
