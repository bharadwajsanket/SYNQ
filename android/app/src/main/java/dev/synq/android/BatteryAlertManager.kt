package dev.synq.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BatteryAlertManager(
    private val context: Context,
    private val settings: SYNQSettings,
    private val sendLowAlertToMac: (type: String, level: Int, isCritical: Boolean) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var hasAlertedPhoneWarning = false
    private var hasAlertedPhoneCritical = false
    private var lastPhoneLevel: Int? = null

    private var hasAlertedMacWarning = false
    private var hasAlertedMacCritical = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            if (level >= 0 && scale > 0) {
                val percentage = (level * 100) / scale
                handleLocalBatteryUpdate(percentage, isCharging)
            }
        }
    }

    init {
        createNotificationChannel()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(batteryReceiver, filter)
        sticky?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val percentage = (level * 100) / scale
                handleLocalBatteryUpdate(percentage, isCharging)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Low battery warnings for your connected devices"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun handleLocalBatteryUpdate(level: Int, isCharging: Boolean) {
        val warningThreshold = settings.state.value.batteryWarningThreshold
        val criticalThreshold = settings.state.value.batteryCriticalThreshold

        if (isCharging) {
            hasAlertedPhoneWarning = false
            hasAlertedPhoneCritical = false
        } else if (lastPhoneLevel != null && level >= (lastPhoneLevel ?: 0) + 5) {
            hasAlertedPhoneWarning = false
            hasAlertedPhoneCritical = false
        }

        lastPhoneLevel = level

        if (!isCharging) {
            if (level <= criticalThreshold && !hasAlertedPhoneCritical) {
                hasAlertedPhoneCritical = true
                hasAlertedPhoneWarning = true
                sendLowAlertToMac("android_low", level, true)
            } else if (level <= warningThreshold && !hasAlertedPhoneWarning) {
                hasAlertedPhoneWarning = true
                sendLowAlertToMac("android_low", level, false)
            }
        }
    }

    fun handleMacBatteryUpdate(level: Int, isCharging: Boolean) {
        if (isCharging || level > settings.state.value.batteryWarningThreshold + 5) {
            hasAlertedMacWarning = false
            hasAlertedMacCritical = false
        }
    }

    fun handleMacBatteryLowAlert(level: Int, isCritical: Boolean, macName: String) {
        if (isCritical) {
            if (hasAlertedMacCritical) return
            hasAlertedMacCritical = true
            hasAlertedMacWarning = true
        } else {
            if (hasAlertedMacWarning) return
            hasAlertedMacWarning = true
        }

        if (settings.state.value.quietModeEnabled && !isCritical) {
            return
        }

        val title = if (isCritical) "$macName Battery Critical ($level%)" else "$macName Battery Low ($level%)"
        val message = if (isCritical) {
            "$macName battery is critically low at $level%. Please plug it in soon."
        } else {
            "$macName battery is down to $level%."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (isCritical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cleanup() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    companion object {
        private const val CHANNEL_ID = "synq_battery_alerts"
        private const val NOTIFICATION_ID = 55401
    }
}

