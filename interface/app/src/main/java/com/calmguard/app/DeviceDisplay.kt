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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayOptions(val dimPercent: Int = 20, val useBrightness: Boolean = false, val brightnessPercent: Int = 40)
data class DeviceDisplayStatus(val running: Boolean = false, val error: String? = null)
data class BrightnessValues(val level: Int, val mode: Int)

interface BrightnessAccess {
    fun canWrite(): Boolean
    fun read(): BrightnessValues
    fun write(values: BrightnessValues)
}

class AndroidBrightnessAccess(private val context: Context) : BrightnessAccess {
    override fun canWrite() = Settings.System.canWrite(context)
    override fun read() = BrightnessValues(
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128),
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
    override fun write(values: BrightnessValues) {
        check(canWrite()) { "Brightness access is not enabled." }
        check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, values.level))
        check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, values.mode))
    }
}

/** Retains the original setting across updates and process death. Never replaces a user's later change. */
class BrightnessSession(private val prefs: SharedPreferences, private val access: BrightnessAccess) {
    val hasSnapshot get() = prefs.contains("original_level")
    fun apply(percent: Int) {
        check(access.canWrite()) { "Allow brightness settings access first." }
        if (!hasSnapshot) {
            val original = access.read()
            check(prefs.edit().putInt("original_level", original.level).putInt("original_mode", original.mode).commit())
        }
        val desired = BrightnessValues((percent.coerceIn(15, 75) * 255 / 100).coerceAtLeast(1), Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val before = access.read()
        // Persist ownership before the write so a process restart can recover it.
        check(prefs.edit().putInt("applied_level", desired.level).commit())
        try { access.write(desired) } catch (error: Exception) {
            runCatching { access.write(before) }
            throw error
        }
    }
    fun restore(): String {
        if (!hasSnapshot) return "No brightness change to restore."
        if (!access.canWrite()) return "Brightness access is off. Restore the previous setting after granting access, or adjust it in Android settings."
        val current = access.read()
        val owned = current.level == prefs.getInt("applied_level", -1) && current.mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        if (owned) access.write(BrightnessValues(prefs.getInt("original_level", 128), prefs.getInt("original_mode", 0)))
        prefs.edit().remove("original_level").remove("original_mode").remove("applied_level").apply()
        return if(owned) "Previous brightness restored." else "Your newer brightness setting was kept."
    }
}

class DeviceDisplayController(private val context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("device_display", Context.MODE_PRIVATE)
    val brightness = BrightnessSession(prefs, AndroidBrightnessAccess(context))
    val linked get() = prefs.getBoolean("linked", false)
    fun options() = DisplayOptions(prefs.getInt("dim", 20), prefs.getBoolean("use_brightness", false), prefs.getInt("brightness", 40))
    fun overlayAllowed() = Settings.canDrawOverlays(context)
    fun brightnessAllowed() = Settings.System.canWrite(context)
    fun notificationsAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(DeviceDisplayService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun save(options: DisplayOptions) {
        prefs.edit().putInt("dim", options.dimPercent.coerceIn(5, 40)).putBoolean("use_brightness", options.useBrightness)
            .putInt("brightness", options.brightnessPercent.coerceIn(15, 75)).apply()
        if(DeviceDisplayService.status.value.running) resumeIfLinked()
    }
    fun enable(): String? {
        if(!overlayAllowed()) return "Allow display over other apps first."
        if(!notificationsAllowed()) return "Enable notifications first so the display mode has a visible Stop control."
        if(options().useBrightness && !brightnessAllowed()) return "Allow brightness settings access, or turn off the optional brightness preset."
        if(!context.getSharedPreferences("calmguard", 0).getBoolean("safe", true)) return "Turn on Safe Mode first."
        return try {
            prefs.edit().putBoolean("linked", true).apply()
            context.startForegroundService(Intent(context, DeviceDisplayService::class.java))
            null
        } catch(error: Exception) {
            prefs.edit().putBoolean("linked", false).apply()
            "Android could not start display mode. Open CalmGuard and try again."
        }
    }
    fun resumeIfLinked(): String? = if(linked) enable() else null
    fun pause() {
        context.stopService(Intent(context, DeviceDisplayService::class.java))
        runCatching { brightness.restore() }
    }
    fun disable(): String {
        prefs.edit().putBoolean("linked", false).apply()
        pause()
        return if(brightness.hasSnapshot) "Display mode stopped. Previous brightness still needs restoring in Android settings." else "Display mode stopped."
    }
}

/** One bounded-opacity, non-interactive overlay. It never reads the screen or intercepts input. */
class DeviceDisplayService : Service() {
    companion object {
        const val STOP = "com.calmguard.app.STOP_DISPLAY"
        const val CHANNEL = "device_display"
        const val NOTIFICATION = 42
        private val mutableStatus = MutableStateFlow(DeviceDisplayStatus())
        val status = mutableStatus.asStateFlow()
        fun overlayParameters(dimPercent: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                // Keep a single window well below Android's 0.8 obscuring-opacity limit.
                alpha = dimPercent.coerceIn(5, 40) / 100f
                title = "CalmGuard display dimming"
            }
    }
    private lateinit var controller: DeviceDisplayController
    private lateinit var windows: WindowManager
    private var overlay: View? = null
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action == Intent.ACTION_SCREEN_OFF) {
                controller.prefs.edit().putBoolean("linked", false).apply()
                stopSelf()
            }
        }
    }
    private val permissionCheck = object : Runnable {
        override fun run() {
            if(!controller.overlayAllowed() || !controller.notificationsAllowed()) {
                fail("Display access was removed. The dimming layer has stopped.")
            } else if(controller.options().useBrightness && !controller.brightnessAllowed()) {
                fail("Brightness access was removed. Review brightness in Android settings.")
            } else handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        controller = DeviceDisplayController(this)
        windows = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Device display mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A visible control for user-enabled display dimming."
            })
        if(Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registered = true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) {
            controller.prefs.edit().putBoolean("linked", false).apply()
            stopSelf(); return START_NOT_STICKY
        }
        if(!controller.overlayAllowed() || !controller.notificationsAllowed() ||
            !getSharedPreferences("calmguard", 0).getBoolean("safe", true)) {
            fail("Display mode requires Safe Mode, overlay access and notifications.")
            return START_NOT_STICKY
        }
        try {
            val notification = notification()
            if(Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION, notification)
            val options = controller.options()
            val parameters = overlayParameters(options.dimPercent)
            if(overlay == null) {
                overlay = View(this).apply {
                    setBackgroundColor(Color.BLACK)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    isClickable = false
                }.also { windows.addView(it, parameters) }
            } else windows.updateViewLayout(overlay, parameters)
            if(options.useBrightness) controller.brightness.apply(options.brightnessPercent) else controller.brightness.restore()
            mutableStatus.value = DeviceDisplayStatus(running = true)
            handler.removeCallbacks(permissionCheck); handler.postDelayed(permissionCheck, 1000)
        } catch(error: Exception) {
            fail("Android could not apply display mode. Review permissions and brightness settings.")
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DeviceDisplayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_display)
            .setContentTitle("CalmGuard display mode is on")
            .setContentText("Dimming other apps. Tap Stop to restore the display.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun fail(message: String) {
        controller.prefs.edit().putBoolean("linked", false).apply()
        mutableStatus.value = DeviceDisplayStatus(error = message)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { windows.removeViewImmediate(it) } }; overlay = null
        if(registered) unregisterReceiver(screenOff)
        runCatching { controller.brightness.restore() }
        mutableStatus.value = mutableStatus.value.copy(running = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
