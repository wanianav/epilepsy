package com.calmguard.app

import android.app.Application
import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceDisplayTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("device_display", Context.MODE_PRIVATE)
    private class FakeBrightness : BrightnessAccess {
        var allowed = true
        var values = BrightnessValues(200, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        override fun canWrite() = allowed
        override fun read() = values
        override fun write(values: BrightnessValues) { check(allowed); this.values = values }
    }
    @Before fun reset() {
        prefs.edit().clear().commit()
        app.getSharedPreferences("calmguard", 0).edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test fun repeatedPresetUpdatesRestoreOriginalAcrossProcessRestart() {
        val access = FakeBrightness()
        val session = BrightnessSession(prefs, access)
        session.apply(40)
        assertEquals(BrightnessValues(102, 0), access.values)
        session.apply(50)
        val restartedSession = BrightnessSession(prefs, access)
        assertTrue(restartedSession.hasSnapshot)
        assertEquals("Previous brightness restored.", restartedSession.restore())
        assertEquals(BrightnessValues(200, 1), access.values)
        assertFalse(session.hasSnapshot)
    }

    @Test fun restorationPreservesLaterUserBrightnessChanges() {
        val access = FakeBrightness()
        val session = BrightnessSession(prefs, access)
        session.apply(40)
        access.values = BrightnessValues(77, 0)
        assertEquals("Your newer brightness setting was kept.", session.restore())
        assertEquals(BrightnessValues(77, 0), access.values)
    }

    @Test fun revokedBrightnessPermissionRetainsRecoverySnapshot() {
        val access = FakeBrightness()
        val session = BrightnessSession(prefs, access)
        session.apply(40); access.allowed = false
        assertTrue(session.restore().contains("access is off"))
        assertTrue(session.hasSnapshot)
        access.allowed = true; session.restore()
        assertEquals(BrightnessValues(200, 1), access.values)
    }

    @Test fun permissionGateDoesNotStartAnUnapprovedOverlay() {
        val controller = DeviceDisplayController(app)
        assertEquals("Allow display over other apps first.", controller.enable())
        assertFalse(controller.linked)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun overlayCannotCaptureFocusOrTouchAndOpacityIsBounded() {
        val params = DeviceDisplayService.overlayParameters(100)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0.40f, params.alpha, 0.001f)
        assertEquals(0.05f, DeviceDisplayService.overlayParameters(-1).alpha, 0.001f)
    }

    @Test fun safeModeOffPausesLinkedDeviceModeWithoutRemovingPreference() {
        prefs.edit().putBoolean("linked", true).commit()
        val state = PrototypeState(app)
        state.updateSafe(state.safe.copy(enabled = false))
        assertTrue(state.deviceDisplay.linked)
        assertFalse(state.safe.enabled)
        assertFalse(DeviceDisplayService.status.value.running)
    }

    @Test fun approvedServiceStartsAndScreenLockRemovesLink() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().putBoolean("linked", true).commit()
        val service = Robolectric.buildService(DeviceDisplayService::class.java).create()
        try {
            service.startCommand(0, 1)
            assertTrue(DeviceDisplayService.status.value.toString(), DeviceDisplayService.status.value.running)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(prefs.getBoolean("linked", true))
        } finally { service.destroy() }
        assertFalse(DeviceDisplayService.status.value.running)
    }

    @Test fun stopNotificationActionDisablesTheLink() {
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        prefs.edit().putBoolean("linked", true).commit()
        val service = Robolectric.buildService(DeviceDisplayService::class.java).create()
        try {
            service.startCommand(0, 1)
            assertTrue(DeviceDisplayService.status.value.running)
            service.get().onStartCommand(Intent(app, DeviceDisplayService::class.java).setAction(DeviceDisplayService.STOP), 0, 2)
            assertFalse(prefs.getBoolean("linked", true))
        } finally { service.destroy() }
        assertFalse(DeviceDisplayService.status.value.running)
    }
}
