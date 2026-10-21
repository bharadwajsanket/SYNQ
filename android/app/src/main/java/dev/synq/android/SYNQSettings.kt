package dev.synq.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SYNQFeature(val key: String, val title: String) {
    CLIPBOARD("clipboard", "Clipboard"),
    FILES("files", "File transfer"),
    NOTIFICATIONS("notifications", "Notification forwarding"),
    BATTERY("battery", "Battery status"),
    FIND_DEVICE("find_device", "Find Device"),
    PING("ping", "Ping"),
    LINKS("links", "Web links"),
    MEDIA("media", "Mac media controls"),
    CALLS("calls", "Calls from Mac"),
}

internal fun featureEnabledByLegacyPeer(feature: SYNQFeature): Boolean =
    feature !in setOf(SYNQFeature.CALLS, SYNQFeature.PING, SYNQFeature.LINKS, SYNQFeature.MEDIA)

data class ContactItem(
    val id: String = java.util.UUID.randomUUID().toString().lowercase(),
    val name: String,
    val number: String,
)

data class SYNQSettingsState(
    val deviceName: String,
    val globalFeatures: Map<SYNQFeature, Boolean>,
    val deviceFeatures: Map<String, Map<SYNQFeature, Boolean>>,
    val notificationApplications: Map<String, String>,
    val disabledNotificationPackages: Set<String>,
    val directCallsEnabled: Boolean,
    val themeMode: String = "system",
    val otpAssistanceEnabled: Boolean = false,
    val contactsSyncEnabled: Boolean = false,
    val favouriteContacts: List<ContactItem> = emptyList(),
)

internal fun effectiveFeatureEnabled(
    globalEnabled: Boolean,
    deviceEnabled: Boolean?,
): Boolean = globalEnabled && deviceEnabled != false

internal fun effectiveFeatureAvailable(localEnabled: Boolean, remoteEnabled: Boolean): Boolean =
    localEnabled && remoteEnabled

class SYNQSettings(context: Context, defaultDeviceName: String) {
    private val preferences = context.getSharedPreferences("synq.settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(load(defaultDeviceName))
    val state: StateFlow<SYNQSettingsState> = mutableState.asStateFlow()

    fun setDeviceName(value: String) {
        val name = value.trim().take(64).ifBlank { "Android device" }
        preferences.edit().putString(KEY_DEVICE_NAME, name).apply()
        mutableState.value = mutableState.value.copy(deviceName = name)
    }

    fun setGlobal(feature: SYNQFeature, enabled: Boolean) {
        preferences.edit().putBoolean("global.${feature.key}", enabled).apply()
        mutableState.value = mutableState.value.copy(
            globalFeatures = mutableState.value.globalFeatures + (feature to enabled),
        )
    }

    fun setForDevice(deviceId: String, feature: SYNQFeature, enabled: Boolean) {
        preferences.edit().putBoolean("device.$deviceId.${feature.key}", enabled).apply()
        val current = mutableState.value.deviceFeatures[deviceId].orEmpty() + (feature to enabled)
        mutableState.value = mutableState.value.copy(
            deviceFeatures = mutableState.value.deviceFeatures + (deviceId to current),
        )
    }

    fun isEnabled(feature: SYNQFeature, deviceId: String?): Boolean {
        val snapshot = mutableState.value
        return effectiveFeatureEnabled(
            globalEnabled = snapshot.globalFeatures[feature] != false,
            deviceEnabled = deviceId?.let { snapshot.deviceFeatures[it]?.get(feature) },
        )
    }

    @Synchronized
    fun observeNotificationApplication(packageName: String, applicationName: String) {
        val safePackage = packageName.take(256)
        val safeName = applicationName.take(128)
        if (safePackage.isBlank() || safeName.isBlank() || mutableState.value.notificationApplications[safePackage] == safeName) return
        preferences.edit().putString("$KEY_NOTIFICATION_APPLICATION_PREFIX$safePackage", safeName).apply()
        mutableState.value = mutableState.value.copy(
            notificationApplications = mutableState.value.notificationApplications + (safePackage to safeName),
        )
    }

    @Synchronized
    fun setNotificationApplicationEnabled(packageName: String, enabled: Boolean, applicationName: String? = null) {
        val safePackage = packageName.trim().take(256)
        if (safePackage.isBlank()) return
        if (!applicationName.isNullOrBlank()) {
            observeNotificationApplication(safePackage, applicationName)
        }
        val disabled = if (enabled) {
            mutableState.value.disabledNotificationPackages - safePackage
        } else {
            mutableState.value.disabledNotificationPackages + safePackage
        }
        preferences.edit().putStringSet(KEY_DISABLED_NOTIFICATION_PACKAGES, disabled).apply()
        mutableState.value = mutableState.value.copy(disabledNotificationPackages = disabled)
    }

    @Synchronized
    fun isNotificationApplicationEnabled(packageName: String): Boolean {
        val safePackage = packageName.trim()
        return safePackage.isNotBlank() && safePackage !in mutableState.value.disabledNotificationPackages
    }

    @Synchronized
    fun pruneRemovedPackages(installedPackages: Set<String>) {
        val removed = mutableState.value.notificationApplications.keys - installedPackages
        if (removed.isEmpty()) return
        val editor = preferences.edit()
        removed.forEach { pkg ->
            editor.remove("$KEY_NOTIFICATION_APPLICATION_PREFIX$pkg")
        }
        editor.apply()
        mutableState.value = mutableState.value.copy(
            notificationApplications = mutableState.value.notificationApplications - removed,
        )
    }

    fun setDirectCallsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DIRECT_CALLS_ENABLED, enabled).apply()
        mutableState.value = mutableState.value.copy(directCallsEnabled = enabled)
    }

    fun setThemeMode(mode: String) {
        val safeMode = if (mode in setOf("system", "light", "dark")) mode else "system"
        preferences.edit().putString(KEY_THEME_MODE, safeMode).apply()
        mutableState.value = mutableState.value.copy(themeMode = safeMode)
    }

    fun setOtpAssistanceEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OTP_ASSISTANCE_ENABLED, enabled).apply()
        mutableState.value = mutableState.value.copy(otpAssistanceEnabled = enabled)
    }

    fun removeDevice(deviceId: String) {
        val editor = preferences.edit()
        SYNQFeature.entries.forEach { editor.remove("device.$deviceId.${it.key}") }
        editor.apply()
        mutableState.value = mutableState.value.copy(deviceFeatures = mutableState.value.deviceFeatures - deviceId)
    }

    fun setContactsSyncEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CONTACTS_SYNC_ENABLED, enabled).apply()
        mutableState.value = mutableState.value.copy(contactsSyncEnabled = enabled)
    }

    fun addFavouriteContact(name: String, number: String) {
        val trimmedName = name.trim()
        val trimmedNumber = number.trim()
        if (trimmedName.isBlank() || trimmedNumber.isBlank() || mutableState.value.favouriteContacts.size >= 30) return
        val item = ContactItem(name = trimmedName, number = trimmedNumber)
        val updated = mutableState.value.favouriteContacts.filterNot { it.number == trimmedNumber } + item
        saveFavouriteContacts(updated)
    }

    fun removeFavouriteContact(id: String) {
        val updated = mutableState.value.favouriteContacts.filterNot { it.id == id }
        saveFavouriteContacts(updated)
    }

    fun setFavouriteContacts(list: List<ContactItem>) {
        saveFavouriteContacts(list)
    }

    private fun saveFavouriteContacts(list: List<ContactItem>) {
        val array = org.json.JSONArray()
        list.forEach {
            array.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("number", it.number))
        }
        preferences.edit().putString(KEY_FAVOURITE_CONTACTS, array.toString()).apply()
        mutableState.value = mutableState.value.copy(favouriteContacts = list)
    }

    private fun load(defaultDeviceName: String): SYNQSettingsState {
        val globals = SYNQFeature.entries.associateWith {
            preferences.getBoolean("global.${it.key}", true)
        }
        val perDevice = mutableMapOf<String, MutableMap<SYNQFeature, Boolean>>()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith("device.") || value !is Boolean) return@forEach
            SYNQFeature.entries.firstOrNull { key.endsWith(".${it.key}") }?.let { feature ->
                val deviceId = key.removePrefix("device.").removeSuffix(".${feature.key}")
                if (deviceId.isNotBlank()) perDevice.getOrPut(deviceId, ::mutableMapOf)[feature] = value
            }
        }
        val notificationApplications = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_NOTIFICATION_APPLICATION_PREFIX) || value !is String) return@mapNotNull null
            val packageName = key.removePrefix(KEY_NOTIFICATION_APPLICATION_PREFIX)
            if (packageName.isBlank() || value.isBlank()) null else packageName to value
        }.toMap()

        val favouritesJson = preferences.getString(KEY_FAVOURITE_CONTACTS, null)
        val favouriteContacts = mutableListOf<ContactItem>()
        if (!favouritesJson.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(favouritesJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    favouriteContacts.add(
                        ContactItem(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.getString("name"),
                            number = obj.getString("number"),
                        )
                    )
                }
            }
        }

        return SYNQSettingsState(
            deviceName = preferences.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName,
            globalFeatures = globals,
            deviceFeatures = perDevice,
            notificationApplications = notificationApplications,
            disabledNotificationPackages = preferences.getStringSet(KEY_DISABLED_NOTIFICATION_PACKAGES, emptySet()).orEmpty(),
            directCallsEnabled = preferences.getBoolean(KEY_DIRECT_CALLS_ENABLED, false),
            themeMode = preferences.getString(KEY_THEME_MODE, "system") ?: "system",
            otpAssistanceEnabled = preferences.getBoolean(KEY_OTP_ASSISTANCE_ENABLED, false),
            contactsSyncEnabled = preferences.getBoolean(KEY_CONTACTS_SYNC_ENABLED, false),
            favouriteContacts = favouriteContacts,
        )
    }

    companion object {
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_NOTIFICATION_APPLICATION_PREFIX = "notification.application."
        private const val KEY_DISABLED_NOTIFICATION_PACKAGES = "notification.disabled_packages"
        private const val KEY_DIRECT_CALLS_ENABLED = "calls.direct_enabled"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_OTP_ASSISTANCE_ENABLED = "otp_assistance_enabled"
        private const val KEY_CONTACTS_SYNC_ENABLED = "contacts_sync_enabled"
        private const val KEY_FAVOURITE_CONTACTS = "favourite_contacts"
    }
}
