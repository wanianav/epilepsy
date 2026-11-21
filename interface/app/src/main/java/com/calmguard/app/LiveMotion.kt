package com.calmguard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class MotionCheckIn(
    val id: String,
    val time: Long,
    val deadline: Long,
    val willText: Boolean
)

internal data class LiveMotionStatus(
    val running: Boolean = false,
    val receiving: Boolean = false,
    val checkIn: MotionCheckIn? = null,
    val message: String? = null
)

internal class LiveMotionController(private val context: Context) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val prefs = context.getSharedPreferences("live_motion", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var repeatAfterWallTime: Long
        get() = prefs.getLong("repeat_after", 0L)
        set(value) { prefs.edit().putLong("repeat_after", value).apply() }

    init {
        notifications.createNotificationChannels(listOf(
            NotificationChannel(LiveMotionService.STATUS_CHANNEL, "Phone motion session", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(LiveMotionService.CHECK_IN_CHANNEL, "Movement check-ins", NotificationManager.IMPORTANCE_HIGH)
        ))
    }

    fun accelerometer(): Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            listOf(LiveMotionService.STATUS_CHANNEL, LiveMotionService.CHECK_IN_CHANNEL).all {
                (notifications.getNotificationChannel(it)?.importance ?: NotificationManager.IMPORTANCE_NONE) !=
                    NotificationManager.IMPORTANCE_NONE
            }

    fun start(): String? {
        enabled = true
        return startIfReady()
    }

    fun startIfReady(): String? {
        if (!enabled || LiveMotionService.status.value.running ||
            accelerometer() == null || !notificationsAllowed()) return null
        return runCatching {
            context.startForegroundService(command(LiveMotionService.START))
        }.fold({ null }, { "Android could not start phone motion. Keep CalmGuard open and try again." })
    }

    fun stop() {
        enabled = false
        context.stopService(command(LiveMotionService.STOP))
    }

    fun acknowledge(id: String) {
        if (LiveMotionService.status.value.checkIn?.id == id) {
            context.startService(command(LiveMotionService.OK, id))
        }
    }

    fun command(action: String, id: String? = null) = Intent(context, LiveMotionService::class.java)
        .setAction(action).apply { if (id != null) data = Uri.fromParts("calmguard-check-in", id, null) }

    fun setPending(pending: Boolean) {
        prefs.edit().putBoolean("pending", pending).apply()
    }

    fun recoverInterrupted() {
        if (LiveMotionService.status.value.checkIn == null && prefs.getBoolean("pending", false)) {
            setPending(false)
            notifications.cancel(LiveMotionService.CHECK_NOTIFICATION)
            EventHistory(context).add("Movement check-in interrupted", "Android stopped the motion session before the check-in finished.", "alert")
        }
    }
}

class LiveMotionStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LiveMotionController(context).startIfReady()
        }
    }
}

class LiveMotionService : Service(), SensorEventListener {
    internal companion object {
        const val START = "com.calmguard.app.START_PHONE_MOTION"
        const val STOP = "com.calmguard.app.STOP_PHONE_MOTION"
        const val OK = "com.calmguard.app.PHONE_MOTION_OK"
        const val STATUS_CHANNEL = "phone_motion_session"
        const val CHECK_IN_CHANNEL = "phone_movement_check_in"
        const val STATUS_NOTIFICATION = 61
        const val CHECK_NOTIFICATION = 62
        const val CHECK_IN_MS = 20_000L
        private const val UNANSWERED_CHECK_IN_COOLDOWN_MS = 120_000L
        private const val SENSOR_TIMEOUT_MS = 3_000L
        private const val SAMPLE_MAX_AGE_NS = 500_000_000L
        private const val SENSOR_WAKE_TIMEOUT_MS = 15_000L
        private val mutableStatus = MutableStateFlow(LiveMotionStatus())
        val status = mutableStatus.asStateFlow()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val detector = PhoneShakeDetector()
    private lateinit var controller: LiveMotionController
    private lateinit var messages: CaregiverTexts
    private lateinit var history: EventHistory
    private lateinit var notifications: NotificationManager
    private lateinit var sensors: SensorManager
    private lateinit var power: PowerManager
    private var selectedSensor: Sensor? = null
    private var registered = false
    private var screenReceiverRegistered = false
    private var lastReading = 0L
    private var nextCheckAt = 0L
    private var textSettings: TextSettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorWakeLock: PowerManager.WakeLock? = null
    private val deadline = Runnable { finishCheckIn() }
    private val screenChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (status.value.running) updateListeningPower(screenOn = intent.action == Intent.ACTION_SCREEN_ON)
        }
    }
    private val healthCheck = object : Runnable {
        override fun run() {
            if (!status.value.running) return
            if (!controller.notificationsAllowed()) {
                stopSession("Phone motion stopped because its notifications were disabled.")
                return
            }
            updateListeningPower()
            if (status.value.receiving && SystemClock.elapsedRealtime() - lastReading > SENSOR_TIMEOUT_MS) {
                mutableStatus.value = status.value.copy(receiving = false)
                notifications.notify(STATUS_NOTIFICATION, sessionNotification())
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = LiveMotionController(this)
        messages = CaregiverTexts(this)
        history = EventHistory(this)
        notifications = getSystemService(NotificationManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        power = getSystemService(PowerManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START, null -> if (!status.value.running) startSession()
            STOP -> {
                controller.enabled = false
                stopSession()
            }
            OK -> {
                val id = intent.data?.schemeSpecificPart
                if (id != null && status.value.checkIn?.id == id) cancelCheckIn()
                if (!status.value.running) stopSelf()
            }
            else -> if (!status.value.running) stopSelf()
        }
        return if (status.value.running) START_STICKY else START_NOT_STICKY
    }

    private fun startSession() {
        if (!controller.enabled) {
            stopSession()
            return
        }
        controller.recoverInterrupted()
        val sensor = controller.accelerometer()
        if (sensor == null || !controller.notificationsAllowed()) {
            stopSession("Phone motion needs an accelerometer and permission to show notifications.")
            return
        }
        selectedSensor = sensor
        val cooldownLeft = (controller.repeatAfterWallTime - System.currentTimeMillis())
            .coerceIn(0L, UNANSWERED_CHECK_IN_COOLDOWN_MS)
        nextCheckAt = SystemClock.elapsedRealtime() + cooldownLeft
        mutableStatus.value = LiveMotionStatus(running = true)
        try {
            val notification = sessionNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(STATUS_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(STATUS_NOTIFICATION, notification)
            if (!sensor.isWakeUpSensor) {
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenChange, filter, RECEIVER_NOT_EXPORTED)
                else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenChange, filter)
                screenReceiverRegistered = true
            }
            registered = sensors.registerListener(this, sensor, 20_000, 0, handler)
            if (!registered) {
                stopSession("Android could not read this phone's accelerometer.")
                return
            }
            updateListeningPower()
            handler.postDelayed(healthCheck, 2_000L)
        } catch (_: RuntimeException) {
            stopSession("Android could not start phone motion. Reopen CalmGuard and try again.")
        }
    }

    private fun updateListeningPower(screenOn: Boolean = power.isInteractive) {
        if (selectedSensor?.isWakeUpSensor != false) return
        if (screenOn) {
            sensorWakeLock?.let { if (it.isHeld) it.release() }
            sensorWakeLock = null
            return
        }
        val lock = sensorWakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalmGuard:PhoneMotion")
            .also { it.setReferenceCounted(false); sensorWakeLock = it }
        // Renew while the screen is off and monitoring is active. If renewal stops, the lock expires.
        lock.acquire(SENSOR_WAKE_TIMEOUT_MS)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val age = SystemClock.elapsedRealtimeNanos() - event.timestamp
        if (!registered || !status.value.running || age !in 0..SAMPLE_MAX_AGE_NS) return
        lastReading = now
        if (!status.value.receiving) {
            mutableStatus.value = status.value.copy(receiving = true)
            notifications.notify(STATUS_NOTIFICATION, sessionNotification())
        }
        if (detector.add(event.timestamp, event.values[0], event.values[1], event.values[2]) &&
            now >= nextCheckAt && status.value.checkIn == null) {
            beginCheckIn(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beginCheckIn(now: Long) {
        if (!controller.notificationsAllowed()) {
            stopSession("Phone motion stopped because its notifications were disabled.")
            return
        }
        textSettings = messages.settings().takeIf { it.automatic && messages.automaticProblem(it) == null }
        val checkIn = MotionCheckIn(UUID.randomUUID().toString(), System.currentTimeMillis(),
            now + CHECK_IN_MS, textSettings != null)
        controller.setPending(true)
        mutableStatus.value = status.value.copy(checkIn = checkIn, message = null)
        history.add("Strong phone movement detected", "Started a 20-second movement check-in.", "alert", checkIn.time)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalmGuard:MotionCheckIn")
            .apply { acquire(CHECK_IN_MS + 5_000L) }
        val content = if (checkIn.willText) "Tap I'm OK within 20 seconds to cancel the caregiver text."
            else "Tap I'm OK within 20 seconds to close the check-in."
        notifications.notify(CHECK_NOTIFICATION, checkInNotification("Strong phone movement detected", content)
            .setWhen(checkIn.time + CHECK_IN_MS)
            .setShowWhen(true)
            .setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(CHECK_IN_MS + 5_000L)
            .addAction(action("I'm OK", OK, checkIn.id))
            .build())
        handler.postDelayed(deadline, (checkIn.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun finishCheckIn() {
        val checkIn = status.value.checkIn ?: return
        val original = textSettings
        val now = SystemClock.elapsedRealtime()
        val late = now - checkIn.deadline > 15_000L || !controller.notificationsAllowed()
        nextCheckAt = now + UNANSWERED_CHECK_IN_COOLDOWN_MS
        controller.repeatAfterWallTime = System.currentTimeMillis() + UNANSWERED_CHECK_IN_COOLDOWN_MS
        endCheckIn()
        val requested = if (!late && original != null) {
            runCatching { messages.sendIfEnabled(original, checkIn.id, checkIn.time) }.getOrDefault(0)
        } else 0
        val message = when {
            late -> "Android interrupted the check-in. Caregiver texts were skipped."
            requested > 0 -> "SMS requested for $requested caregiver ${if (requested == 1) "number" else "numbers"}. Check History for sending status."
            checkIn.willText -> "Caregiver texts could not be requested. Check SMS access and the default SIM."
            else -> "No response to the movement check-in. Automatic texting was off or unavailable."
        }
        mutableStatus.value = status.value.copy(message = message)
        history.add(if (late) "Movement check-in interrupted" else "Movement check-in expired", message, "alert")
        notifications.notify(CHECK_NOTIFICATION, checkInNotification("Movement check-in ended", message)
            .setOnlyAlertOnce(true).setAutoCancel(true).build())
    }

    private fun cancelCheckIn() {
        if (status.value.checkIn == null) return
        endCheckIn()
        mutableStatus.value = status.value.copy(message = "Check-in cancelled. Phone motion is still on.")
        notifications.cancel(CHECK_NOTIFICATION)
        history.add("Movement check-in cancelled", "You tapped I'm OK. No caregiver texts were requested.", "alert")
    }

    private fun endCheckIn() {
        handler.removeCallbacks(deadline)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        textSettings = null
        controller.setPending(false)
        mutableStatus.value = status.value.copy(checkIn = null)
    }

    private fun stopSession(message: String? = null) {
        mutableStatus.value = status.value.copy(running = false, receiving = false, message = message)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
        .setAction(MainActivity.OPEN_MOTION)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(label: String, command: String, id: String? = null): Notification.Action {
        val flags = if (id == null) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_ONE_SHOT
        val pending = PendingIntent.getService(this, 0, controller.command(command, id), PendingIntent.FLAG_IMMUTABLE or flags)
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun sessionNotification(): Notification {
        val detail = if (status.value.receiving) "Listening for repeated strong movement."
            else "Waiting for accelerometer readings."
        return Notification.Builder(this, STATUS_CHANNEL).setSmallIcon(R.drawable.ic_display)
            .setContentTitle("CalmGuard phone motion")
            .setContentText(detail)
            .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(action("Stop", STOP)).build()
    }

    private fun checkInNotification(title: String, text: String) =
        Notification.Builder(this, CHECK_IN_CHANNEL).setSmallIcon(R.drawable.ic_display)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PUBLIC)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (status.value.checkIn != null) {
            endCheckIn()
            notifications.cancel(CHECK_NOTIFICATION)
            history.add("Movement check-in interrupted", "The motion session stopped during the countdown.", "alert")
        }
        if (registered) sensors.unregisterListener(this)
        updateListeningPower(screenOn = true)
        if (screenReceiverRegistered) unregisterReceiver(screenChange)
        mutableStatus.value = LiveMotionStatus(message = status.value.message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
