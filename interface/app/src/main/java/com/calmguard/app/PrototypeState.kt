package com.calmguard.app

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SafePreferences(val enabled: Boolean = true, val manualMedia: Boolean = true,
    val reducedMotion: Boolean = true, val holdUpdates: Boolean = true)
enum class AlertStage { IDLE, COUNTDOWN, COMPLETE }

class PrototypeState(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("calmguard", 0)
    private val history = EventHistory(application)
    val deviceDisplay = DeviceDisplayController(application)
    internal val liveMotion = LiveMotionController(application)
    internal val caregiverTexts = CaregiverTexts(application)
    val model = MotionModel(application)
    val clips = loadClips(application)
    var page by mutableIntStateOf(0)
    var research by mutableStateOf(false)
    var safe by mutableStateOf(SafePreferences(prefs.getBoolean("safe", true),
        prefs.getBoolean("media", true), prefs.getBoolean("motion", true), prefs.getBoolean("updates", true)))
        private set
    var selected by mutableIntStateOf(0)
        private set
    var windowIndex by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var score by mutableStateOf<Double?>(null)
        private set
    var alert by mutableStateOf(AlertStage.IDLE)
        private set
    var remaining by mutableIntStateOf(20)
        private set
    var autoAlert by mutableStateOf(false)
    var caregiver by mutableStateOf(prefs.getString("caregiver", "Demo caregiver") ?: "Demo caregiver")
        private set
    var notice by mutableStateOf<String?>(null)
    val events = mutableStateListOf<EventEntry>()
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == EventHistory.KEY || key == null) readHistory()
    }
    private var replay: Job? = null
    private var countdown: Job? = null
    private var consecutive = 0
    val clip get() = clips[selected]
    val chartPoints get() = clip.windows.getOrNull(windowIndex) ?: emptyList()

    init {
        readHistory()
        prefs.registerOnSharedPreferenceChangeListener(historyListener)
        liveMotion.recoverInterrupted()
        // A process restart cancels a local test countdown; it never escalates an alert.
        if (prefs.getBoolean("pendingTest", false)) {
            log("Test interrupted", "The app closed during a local countdown. No messages were sent.", "alert")
            prefs.edit().putBoolean("pendingTest", false).apply()
        }
        if(!DeviceDisplayService.status.value.running && deviceDisplay.brightness.hasSnapshot) {
            notice = runCatching { deviceDisplay.brightness.restore() }.getOrElse { "Review the previous brightness setting in Android settings." }
        }
    }
    fun updateSafe(next: SafePreferences) {
        safe = next
        prefs.edit().putBoolean("safe", next.enabled).putBoolean("media", next.manualMedia)
            .putBoolean("motion", next.reducedMotion).putBoolean("updates", next.holdUpdates).apply()
        if(next.enabled) deviceDisplay.resumeIfLinked()?.let { notice = it } else deviceDisplay.pause()
        log("Safe Mode updated", if (next.enabled) "Content controls are active in this app." else "Normal demo mode selected. Demo content stays non-flashing.", "safe")
    }
    fun saveCaregiver(value: String) {
        caregiver = value.trim().take(50).ifBlank { "Demo caregiver" }
        prefs.edit().putString("caregiver", caregiver).apply()
    }
    fun tryEvent(type: String) {
        val blocked = safe.enabled && when(type) {
            "Autoplay" -> safe.manualMedia
            "Motion" -> safe.reducedMotion
            else -> safe.holdUpdates
        }
        val detail = when(type) {
            "Autoplay" -> if (blocked) "Autoplay held. A static preview is shown." else "Media request allowed in the demo. A static preview is shown."
            "Motion" -> if (blocked) "Reduced-motion transition selected." else "Standard transition requested. This demo uses a static replacement."
            else -> if (blocked) "Feed update held for your approval." else "Scripted feed update allowed."
        }
        log(if (blocked) "$type controlled" else "$type requested", detail, "safe")
        notice = detail
    }
    fun choose(index: Int) {
        require(index in clips.indices)
        pause(); selected = index; windowIndex = 0; score = null; consecutive = 0
    }
    fun pause() { replay?.cancel(); playing = false }
    fun reset() { pause(); windowIndex = 0; score = null; consecutive = 0 }
    fun togglePlayback() {
        if (playing) { pause(); return }
        if (windowIndex >= clip.windows.size - 1) { windowIndex = 0; consecutive = 0 }
        playing = true
        log("Sample playback started", "${clip.title} · ${clip.source}. Recorded sample data only.", "sample")
        replay = viewModelScope.launch {
            while (windowIndex < clip.windows.size && playing) {
                score = model.predict(clip.windows[windowIndex])
                consecutive = if ((score ?: 0.0) >= model.threshold) consecutive + 1 else 0
                if (autoAlert && consecutive >= 2 && alert == AlertStage.IDLE) {
                    startAlert("Two consecutive replay windows exceeded the demo threshold.")
                    break
                }
                delay(2000)
                if (windowIndex == clip.windows.lastIndex) { playing = false; break }
                windowIndex++
            }
        }
    }
    fun analyseCurrent() {
        score = model.predict(chartPoints)
        log("Sample window analysed", "${clip.title} · window ${windowIndex + 1} · model score ${"%.3f".format(Locale.US, score)}. This is not a clinical probability.", "sample")
    }
    fun startAlert(reason: String = "Manually started a local alert demonstration.") {
        if (alert != AlertStage.IDLE) return
        pause(); remaining = 20; alert = AlertStage.COUNTDOWN
        prefs.edit().putBoolean("pendingTest", true).apply()
        log("Test countdown started", reason, "alert")
        countdown = viewModelScope.launch {
            repeat(20) { delay(1000); remaining-- }
            finishAlert()
        }
    }
    fun cancelAlert() {
        countdown?.cancel(); alert = AlertStage.IDLE; consecutive = 0; autoAlert = false
        prefs.edit().putBoolean("pendingTest", false).apply()
        log("Test alert cancelled", "Cancelled before the countdown completed. No messages were sent.", "alert")
    }
    fun finishAlert() {
        if (alert != AlertStage.COUNTDOWN) return
        countdown?.cancel(); remaining = 0; alert = AlertStage.COMPLETE; autoAlert = false
        prefs.edit().putBoolean("pendingTest", false).apply()
        log("Test alert completed", "Local demonstration for $caregiver. No SMS, location or notifications were sent.", "alert")
    }
    fun dismissAlert() { alert = AlertStage.IDLE; consecutive = 0 }
    private fun readHistory() {
        val updated = history.read()
        events.clear()
        events.addAll(updated)
    }
    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(historyListener)
        super.onCleared()
    }
    fun log(title: String, detail: String, kind: String) = history.add(title, detail, kind)
    fun clearHistory() = history.clear()
    fun exportCsv(): String {
        fun field(s: String) = "\"${s.replace("\"", "\"\"")}\""
        return "timestamp,title,detail,category\r\n" + events.joinToString("\r\n") {
            listOf(java.time.Instant.ofEpochMilli(it.time).toString(), it.title, it.detail, it.kind).joinToString(",", transform = ::field)
        }
    }
}

fun displayTime(time: Long): String = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(time))
