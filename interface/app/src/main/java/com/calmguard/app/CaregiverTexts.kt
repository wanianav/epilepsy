package com.calmguard.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class TextSettings(val numbers: List<String>, val automatic: Boolean)

internal class CaregiverTexts(private val context: Context) {
    private val prefs = context.getSharedPreferences("caregiver_texts", Context.MODE_PRIVATE)
    private val history = EventHistory(context)

    fun settings() = TextSettings(
        prefs.getString("numbers", "").orEmpty().split(',').filter { NUMBER.matches(it) }.distinct().take(MAX_CONTACTS),
        prefs.getBoolean("automatic", false)
    )

    fun saveNumbers(inputs: List<String>): String? {
        val numbers = inputs.map(String::trim).filter(String::isNotEmpty).map {
            if (!PHONE_INPUT.matches(it)) return INVALID_NUMBER
            it.filter { ch -> ch == '+' || ch in '0'..'9' }
                .takeIf(NUMBER::matches) ?: return INVALID_NUMBER
        }.distinct().take(MAX_CONTACTS)
        if (numbers != settings().numbers) {
            prefs.edit().putString("numbers", numbers.joinToString(",")).putBoolean("automatic", false).apply()
        }
        return null
    }

    fun permissionGranted() =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun automaticProblem(config: TextSettings = settings()): String? {
        if (config.numbers.isEmpty()) return "Save at least one caregiver number first."
        val smsCapable = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
        } else context.getSystemService(TelephonyManager::class.java)?.isSmsCapable == true
        if (!smsCapable) return "This phone cannot send SMS automatically."
        if (!permissionGranted()) return "Allow SMS in Android settings to enable automatic texts."
        if (SubscriptionManager.getDefaultSmsSubscriptionId() < 0) {
            return "Choose a default SIM for SMS in Android settings."
        }
        return null
    }

    fun setAutomatic(enabled: Boolean): String? {
        if (enabled) automaticProblem()?.let { return it }
        prefs.edit().putBoolean("automatic", enabled).apply()
        return null
    }

    fun compose(number: String): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
        .putExtra("sms_body", "Please call me for a check-in.")

    @Suppress("DEPRECATION")
    fun sendIfEnabled(original: TextSettings, id: String, time: Long): Int {
        if (!original.automatic || settings() != original || automaticProblem(original) != null) return 0
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (subscription < 0) return 0
        val sms = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(subscription)
        } else SmsManager.getSmsManagerForSubscriptionId(subscription)
        if (sms == null) return 0
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.US).format(Date(time))
        val body = "CalmGuard: My phone detected shaking at $timeText and I did not respond. Please call me to check in."
        var requested = 0
        original.numbers.forEachIndexed { index, number ->
            val receipt = Intent(context, CaregiverTextResult::class.java).setAction(CaregiverTextResult.ACTION)
                .setData(Uri.parse("calmguard-sms://result/$id/${index + 1}"))
            val sent = PendingIntent.getBroadcast(context, 0, receipt,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            runCatching { sms.sendTextMessage(number, null, body, sent, null) }
                .onSuccess { requested++ }
                .onFailure {
                    sent.cancel()
                    history.add("Caregiver text failed", "Android rejected the SMS request for contact ${index + 1}.", "alert")
                }
        }
        return requested
    }

    companion object {
        const val MAX_CONTACTS = 3
        private val PHONE_INPUT = Regex("[+0-9() .-]{1,32}")
        private val NUMBER = Regex("\\+?[0-9]{8,15}")
        private const val INVALID_NUMBER = "Enter a full phone number with 8 to 15 digits, including the country code when needed."
    }
}

class CaregiverTextResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val number = intent.data?.lastPathSegment?.toIntOrNull() ?: return
        if (number !in 1..CaregiverTexts.MAX_CONTACTS) return
        val history = EventHistory(context)
        if (resultCode == Activity.RESULT_OK) {
            history.add("Caregiver text sent", "Android reported an SMS sent to contact $number. Check-in with the caregiver is still unconfirmed.", "alert")
        } else {
            val reason = when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobile radio was off"
                else -> "Android reported an SMS error"
            }
            history.add("Caregiver text failed", "$reason for contact $number.", "alert")
        }
    }

    internal companion object {
        const val ACTION = "com.calmguard.app.CAREGIVER_TEXT_RESULT"
    }
}
