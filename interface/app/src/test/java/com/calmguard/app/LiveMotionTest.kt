package com.calmguard.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.shadows.ShadowSmsManager
import org.robolectric.shadows.ShadowSubscriptionManager
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveMotionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private val status get() = LiveMotionService.status.value

    @Test fun missingNotificationsPreventStart() {
        val controller = LiveMotionController(app)
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        controller.startIfReady()
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun bootAndAppUpdateStartEnabledMonitoring() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val receiver = LiveMotionStartupReceiver()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            receiver.onReceive(app, Intent(action))
            assertEquals(LiveMotionService.START, shadowOf(app).nextStartedService?.action)
        }
    }

    @Test fun notificationStopDisablesAutomaticStartUntilStartedAgain() = runningService { service ->
        val controller = LiveMotionController(service)
        service.onStartCommand(controller.command(LiveMotionService.STOP), 0, 2)
        assertFalse(status.running)
        assertFalse(LiveMotionController(app).enabled)
        controller.startIfReady()
        LiveMotionStartupReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)

        controller.start()
        assertTrue(LiveMotionController(app).enabled)
        assertEquals(LiveMotionService.START, shadowOf(app).nextStartedService?.action)
    }

    @Test fun sensorCheckInCanBeCancelledFromNotificationAndAppearsInHistory() = runningService { service ->
        val state = PrototypeState(app)
        motion(service)
        val checkIn = requireNotNull(status.checkIn)
        assertFalse(checkIn.willText)
        assertTrue(status.receiving)
        assertEquals("Strong phone movement detected", state.events.first().title)
        val manager = shadowOf(service.getSystemService(NotificationManager::class.java))
        val notification = requireNotNull(manager.getNotification(LiveMotionService.CHECK_NOTIFICATION))
        assertEquals("I'm OK", notification.actions.single().title.toString())
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        service.onStartCommand(LiveMotionController(service).command(LiveMotionService.OK, "stale-id"), 0, 2)
        assertEquals(checkIn, status.checkIn)
        service.onStartCommand(shadowOf(notification.actions.single().actionIntent).savedIntent, 0, 3)
        assertNull(status.checkIn)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        looper.idleFor(23, TimeUnit.SECONDS)
        assertEquals("Movement check-in cancelled", state.events.first().title)
        assertNull(manager.getNotification(LiveMotionService.CHECK_NOTIFICATION))
    }

    @Test fun acknowledgementKeepsListeningForTheNextShake() = runningService { service ->
        motion(service)
        val first = requireNotNull(status.checkIn)
        val controller = LiveMotionController(service)
        service.onStartCommand(controller.command(LiveMotionService.OK, first.id), 0, 2)

        assertTrue(status.running)
        assertTrue(status.receiving)
        assertTrue(shadowOf(service.getSystemService(SensorManager::class.java)).hasListener(service))
        motion(service)
        assertNull("Continuous shaking should stay part of the cancelled check-in.", status.checkIn)

        motion(service, amplitude = 0.0, samples = 50)
        motion(service)
        assertNotEquals(first.id, requireNotNull(status.checkIn).id)
    }

    @Test fun unansweredCheckInStillLimitsRepeatAlerts() = runningService { service ->
        motion(service)
        requireNotNull(status.checkIn)
        looper.idleFor(21, TimeUnit.SECONDS)

        motion(service)
        assertNull(status.checkIn)
        looper.idleFor(120, TimeUnit.SECONDS)
        motion(service)
        assertNotNull(status.checkIn)
    }

    @Test fun expiryUsesTheSavedNumberWhenOptedIn() = runningService { service ->
        val sms = enableTexts(service)
        motion(service)
        assertTrue(requireNotNull(status.checkIn).willText)
        looper.idleFor(21, TimeUnit.SECONDS)
        val request = requireNotNull(sms.lastSentTextMessageParams)
        assertEquals("+12025550101", request.destinationAddress)
        assertTrue(request.text.contains("Please call me to check in."))
        assertFalse(request.text.contains("seizure", ignoreCase = true))
        val manager = shadowOf(service.getSystemService(NotificationManager::class.java))
        val result = requireNotNull(manager.getNotification(LiveMotionService.CHECK_NOTIFICATION))
        assertNotEquals(0, result.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals("Movement check-in expired", EventHistory(app).read().first().title)
        request.sentIntent.send(app, SmsManager.RESULT_ERROR_NO_SERVICE, null)
        looper.idle()
        assertEquals("Caregiver text failed", EventHistory(app).read().first().title)
    }

    @Test fun changingRecipientDuringCountdownTurnsOffSms() = runningService { service ->
        val sms = enableTexts(service)
        motion(service)
        assertTrue(requireNotNull(status.checkIn).willText)
        val texts = CaregiverTexts(app)
        assertNull(texts.saveNumbers(listOf("+1 (202) 555-0199")))
        assertFalse(texts.settings().automatic)
        looper.idleFor(21, TimeUnit.SECONDS)
        assertNull(sms.lastSentTextMessageParams)
    }

    @Test fun stoppingSessionCancelsPendingSmsAndUnregistersSensor() {
        val controller = startService()
        val service = controller.get()
        val sms = enableTexts(service)
        motion(service)
        assertTrue(requireNotNull(status.checkIn).willText)
        controller.destroy()
        looper.idleFor(21, TimeUnit.SECONDS)
        assertFalse(status.running)
        assertFalse(shadowOf(service.getSystemService(SensorManager::class.java)).hasListener(service))
        assertNull(sms.lastSentTextMessageParams)
        assertEquals("Movement check-in interrupted", EventHistory(app).read().first().title)
    }

    @Test fun screenOffKeepsListeningWithANonWakeUpAccelerometer() = runningService { service ->
        val power = shadowOf(service.getSystemService(PowerManager::class.java))
        val sensors = shadowOf(service.getSystemService(SensorManager::class.java))
        power.setIsInteractive(false)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        looper.idle()
        val sensorLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(sensorLock.isHeld)
        looper.idleFor(16, TimeUnit.SECONDS)
        assertTrue(sensorLock.isHeld)
        assertTrue(sensors.hasListener(service))

        motion(service)
        assertNotNull(status.checkIn)
        power.setIsInteractive(true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        looper.idle()
        assertFalse(sensorLock.isHeld)
        assertTrue(sensors.hasListener(service))
    }

    @Test fun stickyRestartWaitsForANewDetectionAfterAnInterruptedCheckIn() {
        LiveMotionController(app).setPending(true)
        val controller = startService(stickyRestart = true)
        try {
            assertNull(status.checkIn)
            assertEquals("Movement check-in interrupted", EventHistory(app).read().first().title)
            motion(controller.get())
            assertNotNull(status.checkIn)
        } finally {
            controller.destroy()
        }
    }

    @Test fun restartLogsInterruptedCheckInWithoutStartingMotionOrSms() {
        val controller = LiveMotionController(app)
        controller.setPending(true)
        controller.recoverInterrupted()
        controller.recoverInterrupted()
        assertEquals(listOf("Movement check-in interrupted"), EventHistory(app).read().map { it.title })
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun startService(stickyRestart: Boolean = false): ServiceController<LiveMotionService> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(LiveMotionService::class.java).create()
        val service = controller.get()
        shadowOf(service.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(service.getSystemService(SensorManager::class.java)).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        val command = if (stickyRestart) null else LiveMotionController(service).command(LiveMotionService.START)
        assertEquals(Service.START_STICKY, service.onStartCommand(command, 0, 1))
        assertTrue(status.toString(), status.running)
        return controller
    }

    private fun runningService(test: (LiveMotionService) -> Unit) {
        val controller = startService()
        try { test(controller.get()) } finally { controller.destroy() }
    }

    private fun enableTexts(context: Context): ShadowSmsManager {
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        val texts = CaregiverTexts(context)
        assertNull(texts.saveNumbers(listOf("+1 (202) 555-0101")))
        assertFalse(texts.settings().automatic)
        assertNull(texts.setAutomatic(true))
        return shadowOf(context.getSystemService(SmsManager::class.java).createForSubscriptionId(1))
    }

    private fun motion(service: LiveMotionService, amplitude: Double = 15.0, samples: Int = 135) {
        val sensors = shadowOf(service.getSystemService(SensorManager::class.java))
        repeat(samples) { index ->
            looper.idleFor(20, TimeUnit.MILLISECONDS)
            val event = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER)
            event.timestamp = SystemClock.elapsedRealtimeNanos()
            event.values[0] = (amplitude * sin(index * 0.02 * 3.0 * 2 * PI)).toFloat()
            event.values[2] = 9.81f
            sensors.sendSensorEventToListeners(event)
        }
    }
}
