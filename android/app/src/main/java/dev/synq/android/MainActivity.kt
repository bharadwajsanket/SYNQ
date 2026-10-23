package dev.synq.android

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.synq.core.discovery.DiscoveredPeer
import dev.synq.core.discovery.NsdDiscoveryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Japanese stationery-inspired palette: warm ivory, sage green, charcoal ink, washi accents
private val SYNQSage = Color(0xFF385E43)
private val SYNQSageContainer = Color(0xFFD5E7DA)
private val SYNQSageDark = Color(0xFFA1CBAE)
private val SYNQSageDarkContainer = Color(0xFF20452C)
private val SYNQTerracotta = Color(0xFFA84E38)

private val SYNQLightScheme = lightColorScheme(
    primary = SYNQSage,
    onPrimary = Color.White,
    primaryContainer = SYNQSageContainer,
    onPrimaryContainer = Color(0xFF04210F),
    secondary = Color(0xFF516355),
    secondaryContainer = Color(0xFFD4E8D6),
    tertiary = SYNQTerracotta,
    surface = Color(0xFFFAF8F5), // warm ivory
    surfaceVariant = Color(0xFFE2E7E0), // subtle washi grey
    background = Color(0xFFFAF8F5),
    outline = Color(0xFF727971),
)

private val SYNQDarkScheme = darkColorScheme(
    primary = SYNQSageDark,
    onPrimary = Color(0xFF063819),
    primaryContainer = SYNQSageDarkContainer,
    onPrimaryContainer = Color(0xFFBCEECE),
    secondary = Color(0xFFB8CCBA),
    secondaryContainer = Color(0xFF3A4B3D),
    tertiary = Color(0xFFFFB4A2),
    surface = Color(0xFF141715), // deep charcoal washi
    surfaceVariant = Color(0xFF2A312B),
    background = Color(0xFF141715),
    outline = Color(0xFF8C938B),
)

private enum class NavDestination(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    DEVICES("Devices", R.drawable.ic_nav_devices),
    NOTIFICATIONS("Notifications", R.drawable.ic_nav_notifications),
    SETTINGS("Settings", R.drawable.ic_nav_settings),
}

private enum class PermissionPrompt {
    SYNQNotifications,
    NotificationForwarding,
    DirectCalls,
    Contacts,
}

private data class SharedContent(
    val text: String? = null,
    val files: List<Uri> = emptyList(),
)

class MainActivity : ComponentActivity() {
    private lateinit var discovery: NsdDiscoveryService
    private lateinit var pairing: PairingCoordinator
    private lateinit var synqSettings: SYNQSettings
    private var notificationAccessEnabled by mutableStateOf(false)
    private var appNotificationsEnabled by mutableStateOf(false)
    private var contactsPermissionGranted by mutableStateOf(false)
    private var sharedContent by mutableStateOf<SharedContent?>(null)
    private var showOnboarding by mutableStateOf(false)
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionState()
    }
    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        synqSettings.setDirectCallsEnabled(hasCallIntegrationPermissions())
        SYNQNotificationListenerService.callPermissionsChanged()
    }
    private val contactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        synqSettings.setContactsSyncEnabled(granted)
        contactsPermissionGranted = granted
        if (granted) {
            pairing.sendContactsResponse()
        }
    }
    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            try {
                // Pick a phone row, not just a person. This lets the user
                // choose the exact number when a contact has several.
                contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                        val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                        if (name.isNotBlank() && number.isNotBlank()) {
                            if (synqSettings.addFavouriteContact(name, number)) {
                                val synq = application as SYNQApplication
                                synq.pairing.sendFavouritesUpdate(synqSettings.state.value.favouriteContacts)
                            } else {
                                android.widget.Toast.makeText(
                                    this,
                                    "You can keep up to $MAX_FAVOURITE_CONTACTS favourites.",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SYNQ", "Failed to resolve picked contact", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val synq = application as SYNQApplication
        if (!synq.isPrimaryUser) {
            setContent { SYNQTheme { UnsupportedProfile() } }
            return
        }
        synq.enableSYNQ()
        showOnboarding = !getSharedPreferences("bridgey_onboarding", MODE_PRIVATE)
            .getBoolean("completed", false)
        pairing = synq.pairing
        discovery = synq.discovery
        synqSettings = synq.settings
        sharedContent = intent.toSharedContent()
        startForegroundService(Intent(this, SYNQConnectionService::class.java))
        setContent {
            val settingsState by synq.settings.state.collectAsStateWithLifecycle()
            SYNQTheme(themeMode = settingsState.themeMode) {
                SYNQApp(
                    discovery = discovery,
                    pairing = pairing,
                    settings = synq.settings,
                    onDeviceNameChanged = synq::updateDeviceName,
                    appNotificationsEnabled = appNotificationsEnabled,
                    notificationAccessEnabled = notificationAccessEnabled,
                    contactsPermissionGranted = contactsPermissionGranted,
                    onTurnOff = {
                        startService(Intent(this, SYNQConnectionService::class.java).setAction(SYNQConnectionService.ACTION_TURN_OFF))
                        finishAndRemoveTask()
                    },
                    onRequestAppNotifications = ::requestAppNotifications,
                    onOpenAppNotificationSettings = ::openAppNotificationSettings,
                    onOpenNotificationAccessSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestDirectCalls = ::requestDirectCalls,
                    onRequestContacts = ::requestContacts,
                    onPickContact = {
                        pickContactLauncher.launch(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        )
                    },
                    sharedContent = sharedContent,
                    onSharedContentHandled = ::clearSharedContent,
                    showOnboarding = showOnboarding,
                    onOnboardingComplete = {
                        getSharedPreferences("bridgey_onboarding", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
                        showOnboarding = false
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedContent = intent.toSharedContent()
    }

    private fun Intent.toSharedContent(): SharedContent? {
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

        val streams = when (action) {
            Intent.ACTION_SEND_MULTIPLE -> streamUris()
            else -> listOfNotNull(streamUri())
        }.distinct()
        val sharedText = getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            ?.takeIf { it.isNotBlank() && streams.isEmpty() }
        return SharedContent(text = sharedText, files = streams)
            .takeIf { it.text != null || it.files.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun refreshPermissionState() {
        appNotificationsEnabled = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        notificationAccessEnabled = NotificationAccess.isEnabled(this)
        contactsPermissionGranted = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (
            ::synqSettings.isInitialized && synqSettings.state.value.directCallsEnabled &&
            !hasCallIntegrationPermissions()
        ) {
            synqSettings.setDirectCallsEnabled(false)
            SYNQNotificationListenerService.callPermissionsChanged()
        }
    }

    private fun clearSharedContent() {
        sharedContent = null
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun requestAppNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun requestDirectCalls() {
        if (hasCallIntegrationPermissions()) {
            synqSettings.setDirectCallsEnabled(true)
            SYNQNotificationListenerService.callPermissionsChanged()
        } else {
            callPermissionLauncher.launch(CALL_INTEGRATION_PERMISSIONS)
        }
    }

    private fun requestContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            synqSettings.setContactsSyncEnabled(true)
            pairing.sendContactsResponse()
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun hasCallIntegrationPermissions(): Boolean =
        CALL_INTEGRATION_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
private fun SYNQTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SYNQDarkScheme
        else -> SYNQLightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@Composable
private fun UnsupportedProfile() = Surface(Modifier.fillMaxSize()) {
    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SYNQ", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Already active in the main phone profile", style = MaterialTheme.typography.titleLarge)
        Text("Open SYNQ from the main profile to avoid showing this phone twice.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SYNQApp(
    discovery: NsdDiscoveryService,
    pairing: PairingCoordinator,
    settings: SYNQSettings,
    onDeviceNameChanged: (String) -> Unit,
    appNotificationsEnabled: Boolean,
    notificationAccessEnabled: Boolean,
    contactsPermissionGranted: Boolean,
    onTurnOff: () -> Unit,
    onRequestAppNotifications: () -> Unit,
    onOpenAppNotificationSettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onRequestDirectCalls: () -> Unit,
    onRequestContacts: () -> Unit,
    onPickContact: () -> Unit,
    sharedContent: SharedContent?,
    onSharedContentHandled: () -> Unit,
    showOnboarding: Boolean,
    onOnboardingComplete: () -> Unit,
) {
    val peers by discovery.peers.collectAsStateWithLifecycle()
    val pairingState by pairing.state.collectAsStateWithLifecycle()
    val trustedIds by pairing.trustedDeviceIds.collectAsStateWithLifecycle()
    val clipboardStatus by pairing.clipboardStatus.collectAsStateWithLifecycle()
    val fileTransfers by pairing.fileTransfers.collectAsStateWithLifecycle()
    val phoneRinging by pairing.phoneRinging.collectAsStateWithLifecycle()
    val macRinging by pairing.macRinging.collectAsStateWithLifecycle()
    val remoteBattery by pairing.remoteBattery.collectAsStateWithLifecycle()
    val pingStatus by pairing.pingStatus.collectAsStateWithLifecycle()
    val trustedDevices by pairing.trustedDevices.collectAsStateWithLifecycle()
    val remoteFeatures by pairing.remoteFeatures.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val receivedLink by pairing.quickActions.receivedLink.collectAsStateWithLifecycle()
    val quickActionStatus by pairing.quickActions.status.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var currentDestination by rememberSaveable { mutableStateOf(NavDestination.HOME) }
    var permissionPrompt by remember { mutableStateOf<PermissionPrompt?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (currentDestination) {
                                NavDestination.HOME -> "SYNQ"
                                NavDestination.DEVICES -> "Devices"
                                NavDestination.NOTIFICATIONS -> "Notifications"
                                NavDestination.SETTINGS -> "Settings"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when (currentDestination) {
                                NavDestination.HOME -> "Dashboard"
                                NavDestination.DEVICES -> "Paired & nearby computers"
                                NavDestination.NOTIFICATIONS -> "Notification forwarding"
                                NavDestination.SETTINGS -> "Preferences & privacy"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    val isConnected = pairingState is PairingState.Connected
                    Surface(
                        shape = CircleShape,
                        color = if (isConnected) Color(0xFF2EAD69).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.size(8.dp).background(
                                    if (isConnected) Color(0xFF2EAD69) else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                            )
                            Text(
                                if (isConnected) "Connected" else "Idle",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isConnected) Color(0xFF2EAD69) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                NavDestination.entries.forEach { dest ->
                    val selected = currentDestination == dest
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentDestination != dest) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentDestination = dest
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(dest.iconRes),
                                contentDescription = dest.label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        }
    ) { padding ->
        when (currentDestination) {
            NavDestination.HOME -> HomeScreen(
                pairingState = pairingState,
                trustedDevices = trustedDevices,
                receivedLink = receivedLink,
                quickActionStatus = quickActionStatus,
                clipboardStatus = clipboardStatus,
                fileTransfers = fileTransfers,
                phoneRinging = phoneRinging,
                macRinging = macRinging,
                remoteBattery = remoteBattery,
                pingStatus = pingStatus,
                enabledFeatures = SYNQFeature.entries.associateWith { feature ->
                    settings.isEnabled(feature, (pairingState as? PairingState.Connected)?.deviceId) &&
                        remoteFeatures[feature] != false
                },
                pairing = pairing,
                appNotificationsEnabled = appNotificationsEnabled,
                notificationAccessEnabled = notificationAccessEnabled,
                onOpenAppNotifications = {
                    if (appNotificationsEnabled) onOpenAppNotificationSettings()
                    else permissionPrompt = PermissionPrompt.SYNQNotifications
                },
                onOpenNotificationAccess = {
                    if (notificationAccessEnabled) onOpenNotificationAccessSettings()
                    else permissionPrompt = PermissionPrompt.NotificationForwarding
                },
                onNavigateToDevices = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentDestination = NavDestination.DEVICES
                },
                modifier = Modifier.padding(padding),
            )
            NavDestination.DEVICES -> DevicesScreen(
                peers = peers,
                trustedDevices = trustedDevices,
                trustedIds = trustedIds,
                pairingState = pairingState,
                onPair = { peer ->
                    val host = peer.host
                    val port = peer.port
                    if (host != null && port != null) {
                        pairing.pair(host, port, peer.deviceNameHint)
                    }
                },
                onReconnect = { device ->
                    val match = peers.firstOrNull { it.deviceIdHint == device.id }
                    val h = match?.host
                    val p = match?.port
                    if (h != null && p != null) {
                        pairing.pair(h, p, match.deviceNameHint)
                    } else {
                        android.widget.Toast.makeText(context, "Looking for ${device.name} on local network…", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onForget = pairing::forget,
                onDisconnect = pairing::cancel,
                modifier = Modifier.padding(padding),
            )
            NavDestination.NOTIFICATIONS -> NotificationsScreen(
                state = settingsState,
                notificationAccessEnabled = notificationAccessEnabled,
                onOpenNotificationAccess = onOpenNotificationAccessSettings,
                onMasterToggled = { enabled ->
                    settings.setGlobal(SYNQFeature.NOTIFICATIONS, enabled)
                    SYNQNotificationListenerService.masterFilterChanged(enabled)
                },
                onApplicationToggled = { packageName, enabled ->
                    settings.setNotificationApplicationEnabled(packageName, enabled)
                    SYNQNotificationListenerService.filterChanged(packageName, enabled)
                },
                onPruneUninstalled = { installedPackages ->
                    settings.pruneRemovedPackages(installedPackages)
                },
                onQuietModeToggled = { enabled ->
                    settings.setQuietModeEnabled(enabled)
                },
                modifier = Modifier.padding(padding),
            )
            NavDestination.SETTINGS -> SettingsScreen(
                state = settingsState,
                contactsPermissionGranted = contactsPermissionGranted,
                trustedDevices = trustedDevices,
                connectedDeviceId = (pairingState as? PairingState.Connected)?.deviceId,
                remoteFeatures = remoteFeatures,
                onDeviceNameChanged = onDeviceNameChanged,
                onThemeModeChanged = settings::setThemeMode,
                onOtpAssistanceChanged = settings::setOtpAssistanceEnabled,
                onQuietModeChanged = { enabled ->
                    settings.setQuietModeEnabled(enabled)
                },
                onFileBrowsingChanged = settings::setFileBrowsingEnabled,
                onCustomBrowsingPathChanged = settings::setCustomBrowsingPath,
                onNavigateToDevices = { currentDestination = NavDestination.DEVICES },
                onGlobalFeatureChanged = { feature, enabled ->
                    settings.setGlobal(feature, enabled)
                    if (feature == SYNQFeature.FIND_DEVICE && !enabled) pairing.stopFinding()
                    if (feature == SYNQFeature.CALLS && !enabled) {
                        settings.setDirectCallsEnabled(false)
                        SYNQNotificationListenerService.callPermissionsChanged()
                    }
                    if (feature == SYNQFeature.NOTIFICATIONS) {
                        SYNQNotificationListenerService.masterFilterChanged(enabled)
                    }
                },
                onDirectCallsChanged = { enabled ->
                    if (enabled) permissionPrompt = PermissionPrompt.DirectCalls
                    else {
                        settings.setDirectCallsEnabled(false)
                        SYNQNotificationListenerService.callPermissionsChanged()
                    }
                },
                onContactsSyncChanged = { enabled ->
                    if (enabled) {
                        if (contactsPermissionGranted) {
                            settings.setContactsSyncEnabled(true)
                            pairing.sendContactsResponse()
                        } else {
                            permissionPrompt = PermissionPrompt.Contacts
                        }
                    } else {
                        settings.setContactsSyncEnabled(false)
                    }
                },
                onAddFavouriteContact = { name, number ->
                    val added = settings.addFavouriteContact(name, number)
                    if (added) pairing.sendFavouritesUpdate(settings.state.value.favouriteContacts)
                    added
                },
                onRemoveFavouriteContact = { id ->
                    settings.removeFavouriteContact(id)
                    pairing.sendFavouritesUpdate(settings.state.value.favouriteContacts)
                },
                onPickContact = onPickContact,
                onTurnOff = onTurnOff,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.welcome_title)) },
            text = { Text(stringResource(R.string.welcome_body)) },
            confirmButton = {
                Button(onClick = onOnboardingComplete) {
                    Text(stringResource(R.string.welcome_continue))
                }
            },
        )
    }

    when (val state = pairingState) {
        is PairingState.Verification -> AlertDialog(
            onDismissRequest = pairing::cancel,
            title = { Text("Verify this device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Check that this code also appears on ${state.peerName}.")
                    Text(state.code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { Button(onClick = pairing::confirm) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = pairing::cancel) { Text("Cancel") } },
        )
        is PairingState.Failed -> AlertDialog(
            onDismissRequest = pairing::cancel,
            title = { Text("Couldn’t connect") },
            text = { Text(state.message) },
            confirmButton = { Button(onClick = pairing::cancel) { Text("OK") } },
        )
        else -> Unit
    }

    permissionPrompt?.let { prompt ->
        val isForwarding = prompt == PermissionPrompt.NotificationForwarding
        val isDirectCalls = prompt == PermissionPrompt.DirectCalls
        val isContacts = prompt == PermissionPrompt.Contacts
        AlertDialog(
            onDismissRequest = { permissionPrompt = null },
            title = {
                Text(
                    when {
                        isForwarding -> "Forward Android notifications?"
                        isDirectCalls -> "Allow call status and controls?"
                        isContacts -> "Allow contacts sync?"
                        else -> "Allow SYNQ notifications?"
                    },
                )
            },
            text = {
                Text(
                    when {
                        isForwarding -> "This optional access lets SYNQ read notification titles and text and send them only to your paired Mac over the encrypted local connection."
                        isDirectCalls -> "These optional Phone permissions let an authenticated paired Mac start calls, distinguish ringing from active calls, and answer, decline, or hang up. SYNQ does not read contacts or call history."
                        isContacts -> "This optional permission lets SYNQ sync your contacts and favourites to your paired Mac for quick dialing. Contacts are encrypted locally and never uploaded to any external server."
                        else -> "SYNQ uses notifications to keep the connection visible and show file transfers, received files, and Find Device. It does not use notifications for advertising."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    permissionPrompt = null
                    when {
                        isForwarding -> onOpenNotificationAccessSettings()
                        isDirectCalls -> onRequestDirectCalls()
                        isContacts -> onRequestContacts()
                        else -> onRequestAppNotifications()
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { permissionPrompt = null }) { Text("Not now") } },
        )
    }

    sharedContent?.let { content ->
        val isConnected = pairingState is PairingState.Connected
        val isWebLink = content.files.isEmpty() && content.text?.let(::validatedWebLink) != null
        val requiredFeature = if (content.files.isNotEmpty()) SYNQFeature.FILES else (if (isWebLink) SYNQFeature.LINKS else SYNQFeature.CLIPBOARD)
        val featureAvailable = settings.isEnabled(
            requiredFeature,
            (pairingState as? PairingState.Connected)?.deviceId,
        ) && remoteFeatures[requiredFeature] != false
        val summary = when {
            isWebLink -> "Open this webpage on your Mac?"
            content.files.size == 1 -> "Send the selected file to your Mac?"
            content.files.isNotEmpty() -> "Send ${content.files.size} selected files to your Mac?"
            else -> "Send the shared text to your Mac?"
        }
        AlertDialog(
            onDismissRequest = onSharedContentHandled,
            title = { Text(if (isWebLink) "Continue Webpage on Mac" else "Share with SYNQ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary)
                    if (isWebLink) {
                        Text(content.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    when {
                        !isConnected -> Text(
                            "Waiting for your paired Mac to reconnect.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        !featureAvailable -> Text(
                            "This feature is turned off on one of your devices.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = isConnected && featureAvailable,
                    onClick = {
                        if (isWebLink) {
                            pairing.quickActions.sendLink(content.text.orEmpty())
                        } else {
                            content.text?.let { pairing.sendText(it) }
                            content.files.forEach(pairing::sendFile)
                        }
                        onSharedContentHandled()
                    },
                ) { Text(if (isWebLink) "Open on Mac" else "Send") }
            },
            dismissButton = {
                TextButton(onClick = onSharedContentHandled) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HomeScreen(
    pairingState: PairingState,
    trustedDevices: List<TrustedDevice>,
    receivedLink: String?,
    quickActionStatus: String?,
    clipboardStatus: String?,
    fileTransfers: Map<String, FileTransferState>,
    phoneRinging: Boolean,
    macRinging: Boolean,
    remoteBattery: RemoteBatteryStatus?,
    pingStatus: String?,
    enabledFeatures: Map<SYNQFeature, Boolean>,
    pairing: PairingCoordinator,
    appNotificationsEnabled: Boolean,
    notificationAccessEnabled: Boolean,
    onOpenAppNotifications: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onNavigateToDevices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(pairing::sendFile)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Webpage Continuation Banner
        if (receivedLink != null) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Webpage from Mac", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(receivedLink, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pairing.quickActions.openLink()
                            }) {
                                Text("Open in browser")
                            }
                            OutlinedButton(onClick = pairing.quickActions::dismissLink) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }

        // 2. Primary Connection Card
        item {
            when (val connected = pairingState as? PairingState.Connected) {
                null -> {
                    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (trustedDevices.isEmpty()) "Connect your Mac" else "Ready to connect",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (trustedDevices.isEmpty())
                                    "Pair with your Mac over local Wi-Fi to sync clipboard, files, and links."
                                else
                                    "Keep your phone and Mac on the same network to connect automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onNavigateToDevices) {
                                Text(if (trustedDevices.isEmpty()) "Pair a computer" else "View paired devices")
                            }
                        }
                    }
                }
                else -> {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_nav_devices),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(connected.peerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("Connected · local network", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2EAD69))
                                }
                                if (remoteBattery != null) {
                                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                        Text(
                                            "${remoteBattery.level}%${if (remoteBattery.isCharging) " ⚡" else ""}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            if (phoneRinging || macRinging) {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        pairing.stopFinding()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Stop Ringing")
                                }
                            } else {
                                val findAvailable = enabledFeatures[SYNQFeature.FIND_DEVICE] != false
                                val pingAvailable = enabledFeatures[SYNQFeature.PING] != false
                                if (findAvailable && pingAvailable) {
                                    var showFindMenu by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { showFindMenu = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Notifications,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text("Find Mac", fontWeight = FontWeight.Medium)
                                            Spacer(Modifier.weight(1f))
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(
                                            expanded = showFindMenu,
                                            onDismissRequest = { showFindMenu = false },
                                            shape = RoundedCornerShape(16.dp),
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        ) {
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Notifications,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                text = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text("Ring Mac", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            "Continuous alarm on Mac",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showFindMenu = false
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    pairing.findMac()
                                                },
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                },
                                                text = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text("Ping Mac", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                        Text(
                                                            "Quick audio alert",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showFindMenu = false
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    pairing.sendPing()
                                                },
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                            )
                                        }
                                    }
                                } else if (findAvailable) {
                                    OutlinedButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pairing.findMac()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Ring Mac")
                                    }
                                } else if (pingAvailable) {
                                    OutlinedButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            pairing.sendPing()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Ping Mac")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Quick Action Grid
        item { SectionTitle("Quick actions") }
        item {
            val isConnected = pairingState is PairingState.Connected
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DashboardActionTile(
                    title = "Send Clipboard",
                    subtitle = clipboardStatus ?: "Copy to Mac",
                    enabled = isConnected && enabledFeatures[SYNQFeature.CLIPBOARD] != false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        pairing.sendClipboard()
                    },
                    modifier = Modifier.weight(1f)
                )
                DashboardActionTile(
                    title = "Send Files",
                    subtitle = "Pick files for Mac",
                    enabled = isConnected && enabledFeatures[SYNQFeature.FILES] != false,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        filePicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (quickActionStatus != null) {
            item {
                Text(quickActionStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        if (pingStatus != null) {
            item {
                Text(pingStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }

        // 4. Active File Transfers
        if (fileTransfers.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("Transfers")
                    val hasCompleted = fileTransfers.values.any { !it.active }
                    if (hasCompleted) {
                        TextButton(onClick = { pairing.clearCompletedFileTransfers() }) {
                            Text("Clear completed", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            items(fileTransfers.values.toList(), key = { it.id }) { transfer ->
                TransferCard(
                    transfer = transfer,
                    cancel = { pairing.cancelFileTransfer(transfer.id) },
                    retry = { pairing.retryFileTransfer(transfer.id) },
                    onClear = { pairing.clearCompletedFileTransfer(transfer.id) },
                )
            }
        }

        // 5. Permission Helpers
        if (!appNotificationsEnabled || !notificationAccessEnabled) {
            item { SectionTitle("Recommended setup") }
            if (!appNotificationsEnabled) {
                item {
                    ServiceCard(
                        enabled = false,
                        title = "App notifications",
                        detail = "Keep connection and transfer progress visible in status bar",
                        action = "Enable",
                        onClick = onOpenAppNotifications
                    )
                }
            }
            if (!notificationAccessEnabled) {
                item {
                    ServiceCard(
                        enabled = false,
                        title = "Notification forwarding",
                        detail = "Forward selected phone alerts to your Mac",
                        action = "Enable",
                        onClick = onOpenNotificationAccess
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DashboardActionTile(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun DevicesScreen(
    peers: List<DiscoveredPeer>,
    trustedDevices: List<TrustedDevice>,
    trustedIds: Set<String>,
    pairingState: PairingState,
    onPair: (DiscoveredPeer) -> Unit,
    onReconnect: (TrustedDevice) -> Unit,
    onForget: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var deviceToForget by remember { mutableStateOf<TrustedDevice?>(null) }

    val connected = pairingState as? PairingState.Connected
    val trustedIdsSet = remember(trustedDevices) { trustedDevices.map { it.id.lowercase() }.toSet() }
    val trustedNamesSet = remember(trustedDevices) { trustedDevices.map { it.name.trim().lowercase() }.toSet() }

    val nearbyPeers = remember(peers, trustedIdsSet, trustedNamesSet, connected) {
        peers.filter { peer ->
            val peerId = peer.deviceIdHint?.lowercase()
            val peerName = peer.deviceNameHint.trim().lowercase()
            if (peerId != null && (trustedIdsSet.contains(peerId) || (connected != null && peerId == connected.deviceId.lowercase()))) {
                return@filter false
            }
            if (trustedNamesSet.contains(peerName) || (connected != null && peerName == connected.peerName.trim().lowercase())) {
                return@filter false
            }
            true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        if (connected != null) {
            item { SectionTitle("Current connection") }
            item {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(connected.peerName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Active encryption · local Wi-Fi", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2EAD69))
                        }
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        item { SectionTitle("Paired computers") }
        if (trustedDevices.isEmpty()) {
            item {
                Text("No computers paired yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(trustedDevices, key = { it.id }) { device ->
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text("ID: ${device.id.take(8)}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (connected?.deviceId != device.id) {
                            Button(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReconnect(device)
                            }) {
                                Text("Reconnect")
                            }
                        }
                        IconButton(onClick = { deviceToForget = device }) {
                            Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item { SectionTitle("Nearby computers") }
        if (nearbyPeers.isEmpty()) {
            item {
                if (peers.isNotEmpty()) {
                    Text(
                        "All discovered computers are already paired.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Searching on local Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(nearbyPeers, key = { it.serviceName }) { peer ->
                PeerRow(peer = peer, isPaired = false) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPair(peer)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    deviceToForget?.let { device ->
        AlertDialog(
            onDismissRequest = { deviceToForget = null },
            title = { Text("Forget ${device.name}?") },
            text = { Text("You will need to pair again to communicate with this Mac.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onForget(device.id)
                        deviceToForget = null
                    }
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { deviceToForget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NotificationsScreen(
    state: SYNQSettingsState,
    notificationAccessEnabled: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onMasterToggled: (Boolean) -> Unit,
    onApplicationToggled: (String, Boolean) -> Unit,
    onPruneUninstalled: (Set<String>) -> Unit,
    onQuietModeToggled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<NotificationAppItem>?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val isMasterEnabled = state.globalFeatures[SYNQFeature.NOTIFICATIONS] != false

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val ownPackage = context.packageName
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            val appMap = mutableMapOf<String, String>()
            val installedPackages = mutableSetOf<String>()
            resolved.forEach { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@forEach
                if (pkg == ownPackage) return@forEach
                installedPackages.add(pkg)
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: pkg
                appMap[pkg] = label
            }
            state.notificationApplications.forEach { (pkg, label) ->
                if (pkg != ownPackage) {
                    if (installedPackages.contains(pkg) || runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) {
                        installedPackages.add(pkg)
                        appMap.putIfAbsent(pkg, label)
                    }
                }
            }
            if (installedPackages.isNotEmpty()) {
                onPruneUninstalled(installedPackages)
            }
            val items = appMap.map { (pkg, label) ->
                val icon = runCatching {
                    val drawable = pm.getApplicationIcon(pkg)
                    loadAppIconBitmap(drawable)
                }.getOrNull()
                NotificationAppItem(packageName = pkg, label = label, icon = icon)
            }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            withContext(Dispatchers.Main) {
                installedApps = items
            }
        }
    }

    val allApps = installedApps.orEmpty()
    val cleanQuery = searchQuery.trim()
    val matchingApps = remember(allApps, cleanQuery) {
        if (cleanQuery.isEmpty()) emptyList()
        else allApps.filter {
            it.label.contains(cleanQuery, ignoreCase = true) || it.packageName.contains(cleanQuery, ignoreCase = true)
        }
    }
    val disabledApps = remember(allApps, state.disabledNotificationPackages) {
        allApps.filter { state.disabledNotificationPackages.contains(it.packageName) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!notificationAccessEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Notification access required", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Grant permission so SYNQ can forward alerts to your Mac.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = onOpenNotificationAccess) {
                            Text("Open settings")
                        }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Master sync toggle", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("Forward notifications to Mac", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = isMasterEnabled, onCheckedChange = onMasterToggled)
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Quiet Mode", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("Silence notification mirroring to Mac without disconnecting. Calls still alert normally.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.quietModeEnabled, onCheckedChange = onQuietModeToggled)
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search installed applications") },
                placeholder = { Text("e.g. WhatsApp, Slack, Messages…") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Text("✕", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
        }

        if (cleanQuery.isNotEmpty()) {
            if (matchingApps.isEmpty()) {
                item {
                    Text(
                        "No applications matching \"$cleanQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(matchingApps, key = { it.packageName }) { app ->
                    val isEnabled = isMasterEnabled && !state.disabledNotificationPackages.contains(app.packageName)
                    NotificationAppRow(
                        app = app,
                        enabled = isEnabled,
                        masterEnabled = isMasterEnabled,
                        onToggled = { onApplicationToggled(app.packageName, it) }
                    )
                }
            }
        } else {
            if (disabledApps.isNotEmpty()) {
                item { SectionTitle("Muted applications (${disabledApps.size})") }
                items(disabledApps, key = { it.packageName }) { app ->
                    NotificationAppRow(
                        app = app,
                        enabled = false,
                        masterEnabled = isMasterEnabled,
                        onToggled = { onApplicationToggled(app.packageName, it) }
                    )
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("All applications enabled", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Type in the search field above to find and mute notifications for specific apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun NotificationAppRow(
    app: NotificationAppItem,
    enabled: Boolean,
    masterEnabled: Boolean,
    onToggled: (Boolean) -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (app.icon != null) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)))
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Switch(
                checked = enabled,
                enabled = masterEnabled,
                onCheckedChange = onToggled,
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    state: SYNQSettingsState,
    contactsPermissionGranted: Boolean,
    trustedDevices: List<TrustedDevice>,
    connectedDeviceId: String?,
    remoteFeatures: Map<SYNQFeature, Boolean>,
    onDeviceNameChanged: (String) -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onOtpAssistanceChanged: (Boolean) -> Unit,
    onGlobalFeatureChanged: (SYNQFeature, Boolean) -> Unit,
    onDirectCallsChanged: (Boolean) -> Unit,
    onContactsSyncChanged: (Boolean) -> Unit,
    onAddFavouriteContact: (String, String) -> Boolean,
    onRemoveFavouriteContact: (String) -> Unit,
    onPickContact: () -> Unit,
    onTurnOff: () -> Unit,
    onQuietModeChanged: (Boolean) -> Unit = {},
    onFileBrowsingChanged: (Boolean) -> Unit = {},
    onCustomBrowsingPathChanged: (String?) -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val resolved = resolveTreeUriToPath(uri)
            if (resolved != null) {
                onCustomBrowsingPathChanged(resolved)
            }
        }
    }
    var editedName by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var showAddFavouriteDialog by remember { mutableStateOf(false) }
    var newFavouriteName by remember { mutableStateOf("") }
    var newFavouriteNumber by remember { mutableStateOf("") }
    var showPermissionsPage by rememberSaveable { mutableStateOf(false) }

    if (showPermissionsPage) {
        PermissionsScreen(
            onBack = { showPermissionsPage = false },
            modifier = modifier
        )
        return
    }

    if (showAddFavouriteDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddFavouriteDialog = false
                newFavouriteName = ""
                newFavouriteNumber = ""
            },
            title = { Text("Add favourite contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newFavouriteName,
                        onValueChange = { newFavouriteName = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g. Madamji") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newFavouriteNumber,
                        onValueChange = { newFavouriteNumber = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("e.g. +91 9876543210") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.favouriteContacts.size >= MAX_FAVOURITE_CONTACTS) {
                        Text(
                            "You already have $MAX_FAVOURITE_CONTACTS favourites. Remove one before adding another.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFavouriteName.trim()
                        val number = newFavouriteNumber.trim()
                        if (name.isNotEmpty() && number.isNotEmpty()) {
                            if (onAddFavouriteContact(name, number)) {
                                showAddFavouriteDialog = false
                                newFavouriteName = ""
                                newFavouriteNumber = ""
                            }
                        }
                    },
                    enabled = newFavouriteName.isNotBlank() && newFavouriteNumber.isNotBlank() &&
                        state.favouriteContacts.size < MAX_FAVOURITE_CONTACTS,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddFavouriteDialog = false
                        newFavouriteName = ""
                        newFavouriteNumber = ""
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Appearance
        item { SectionTitle("Appearance") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Theme mode", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onThemeModeChanged(mode) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.themeMode == mode,
                                onClick = { onThemeModeChanged(mode) }
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text("Device name", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it.take(64) },
                        label = { Text("Phone display name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (editedName.trim().isNotEmpty() && editedName.trim() != state.deviceName) {
                        Button(
                            onClick = { onDeviceNameChanged(editedName) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Save name") }
                    }
                }
            }
        }

        // 2. Connection & Devices
        item { SectionTitle("Connection & Devices") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (trustedDevices.isEmpty()) "No paired computers" else "${trustedDevices.size} paired computer${if (trustedDevices.size > 1) "s" else ""}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Manage pairings, connect, or forget computers in the Devices tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onNavigateToDevices, shape = RoundedCornerShape(12.dp)) {
                        Text("View")
                    }
                }
            }
        }

        // 3. Notifications
        item { SectionTitle("Notifications") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingToggleRow(
                        title = "Quiet Mode",
                        subtitle = "Silence forwarded SYNQ notifications on this device",
                        checked = state.quietModeEnabled,
                        onCheckedChange = onQuietModeChanged,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Notification forwarding",
                        subtitle = "Forward incoming phone notifications securely to your Mac",
                        checked = state.globalFeatures[SYNQFeature.NOTIFICATIONS] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.NOTIFICATIONS, it) },
                    )
                }
            }
        }

        // 4. Features
        item { SectionTitle("Features") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingToggleRow(
                        title = "Clipboard sync",
                        subtitle = "Keep copied text synchronized between devices",
                        checked = state.globalFeatures[SYNQFeature.CLIPBOARD] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.CLIPBOARD, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "File transfer",
                        subtitle = "Send and receive files directly with your Mac",
                        checked = state.globalFeatures[SYNQFeature.FILES] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.FILES, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Web link handoff",
                        subtitle = "Share open links between your phone and Mac",
                        checked = state.globalFeatures[SYNQFeature.LINKS] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.LINKS, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Mac media controls",
                        subtitle = "See now playing and control playback from phone",
                        checked = state.globalFeatures[SYNQFeature.MEDIA] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.MEDIA, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "OTP Assistance",
                        subtitle = "Forward incoming 2FA verification codes to your Mac",
                        checked = state.otpAssistanceEnabled,
                        onCheckedChange = onOtpAssistanceChanged,
                    )
                }
            }
        }

        // 5. Privacy & Storage
        item { SectionTitle("Privacy & Storage") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Remote file browsing", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Allow paired Mac to browse and download files from authorized storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.fileBrowsingEnabled,
                            onCheckedChange = onFileBrowsingChanged,
                        )
                    }

                    if (state.fileBrowsingEnabled) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Authorized directory", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    state.customBrowsingPath ?: "Default: Downloads folder (${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { folderPicker.launch(null) },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Choose folder…")
                            }
                            if (state.customBrowsingPath != null) {
                                OutlinedButton(
                                    onClick = { onCustomBrowsingPathChanged(null) },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Reset to Downloads")
                                }
                            }
                        }
                    } else {
                        Text(
                            "Browsing is disabled. Remote list and download requests from Mac are rejected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // 6. Contacts & Calling
        item { SectionTitle("Contacts & Calling") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingToggleRow(
                        title = "Direct phone calls",
                        subtitle = "Control and answer phone calls from your Mac",
                        checked = state.directCallsEnabled,
                        onCheckedChange = onDirectCallsChanged,
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Contacts sync",
                        subtitle = "Sync contacts with your Mac for quick dialing",
                        checked = state.contactsSyncEnabled,
                        onCheckedChange = onContactsSyncChanged,
                    )
                }
            }
        }
        item {
            ContactsSyncStatusCard(
                state = state,
                permissionGranted = contactsPermissionGranted,
                isConnected = connectedDeviceId != null,
                onEnableSync = { onContactsSyncChanged(true) },
            )
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Favourite contacts (${state.favouriteContacts.size}/$MAX_FAVOURITE_CONTACTS)", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Quick-dial contacts synced with your Mac",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onPickContact,
                                enabled = state.favouriteContacts.size < MAX_FAVOURITE_CONTACTS,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Pick")
                            }
                            Button(
                                onClick = { showAddFavouriteDialog = true },
                                enabled = state.favouriteContacts.size < MAX_FAVOURITE_CONTACTS,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Manual")
                            }
                        }
                    }
                    if (state.favouriteContacts.size >= MAX_FAVOURITE_CONTACTS) {
                        Text(
                            "You can keep up to $MAX_FAVOURITE_CONTACTS favourites. Remove one to add another.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.favouriteContacts.isEmpty()) {
                        Text(
                            "No favourite contacts added yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.favouriteContacts.forEach { contact ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(contact.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        contact.number,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { onRemoveFavouriteContact(contact.id) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. About & Service
        item { SectionTitle("About & Service") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SYNQ",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "v2.5.4",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "SYNQ communicates locally and directly with your Mac. No accounts, external servers, analytics, or third-party relays are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { showPermissionsPage = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Permissions & Privacy Overview")
                    }
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = onTurnOff,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Turn off SYNQ")
                    }
                }
            }
        }

        item {
            val uriHandler = LocalUriHandler.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Made with ♥ by Sanket",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            uriHandler.openUri("https://github.com/bharadwajsanket")
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PermissionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissions = remember {
        listOf(
            SynqPermissionInfo(
                name = "Local Network & Multicast",
                technical = "INTERNET, CHANGE_WIFI_MULTICAST_STATE",
                isRequired = true,
                feature = "Device Discovery & Secure Link",
                description = "Discovers your Mac on the local Wi-Fi using Bonjour/mDNS and establishes direct TLS-encrypted peer-to-peer communication. SYNQ never contacts external cloud servers or relays."
            ),
            SynqPermissionInfo(
                name = "Notifications",
                technical = "POST_NOTIFICATIONS",
                isRequired = true,
                feature = "Foreground Service & Alerts",
                description = "Required on Android 13+ to maintain the background connection service, show active transfer status, and play the Find Device alert."
            ),
            SynqPermissionInfo(
                name = "Notification Listener",
                technical = "BIND_NOTIFICATION_LISTENER_SERVICE",
                isRequired = false,
                feature = "Notification Forwarding & OTP",
                description = "Mirrors selected phone notifications to your Mac and detects temporary one-time verification codes for easy copying. Handled entirely on your local devices."
            ),
            SynqPermissionInfo(
                name = "Phone Calls",
                technical = "CALL_PHONE, READ_PHONE_STATE, ANSWER_PHONE_CALLS",
                isRequired = false,
                feature = "Call from Mac",
                description = "Places phone calls dialed from your Mac, manages call audio, and provides call-in-progress controls from your Mac's menu bar."
            ),
            SynqPermissionInfo(
                name = "Contacts",
                technical = "READ_CONTACTS",
                isRequired = false,
                feature = "Contact Sync & Quick Dial",
                description = "Synchronizes your favourite phone contacts with your Mac so you can dial friends and family with a single tap. Contacts remain private to your paired devices."
            ),
            SynqPermissionInfo(
                name = "SMS Messaging",
                technical = "SEND_SMS",
                isRequired = false,
                feature = "Quick SMS from Mac",
                description = "Enables composing and sending SMS messages from your Mac through your phone's cellular connection without third-party services."
            ),
            SynqPermissionInfo(
                name = "Storage & Documents",
                technical = "Storage Access Framework",
                isRequired = false,
                feature = "File Transfer & Browsing",
                description = "Allows browsing designated phone folders from your Mac and saving files transferred wirelessly between your devices."
            )
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "How SYNQ respects and protects your privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Privacy & Security Commitment", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "SYNQ operates strictly peer-to-peer over your local encrypted network. There are no user accounts, no cloud servers, and zero data collection. Optional permissions are only used when you enable the corresponding feature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(permissions) { info ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            info.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (info.isRequired) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = if (info.isRequired) "Required" else "Optional",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (info.isRequired) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Feature: ${info.feature}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = info.technical,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Manage System Permissions")
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private data class SynqPermissionInfo(
    val name: String,
    val technical: String,
    val isRequired: Boolean,
    val feature: String,
    val description: String,
)

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ContactsSyncStatusCard(
    state: SYNQSettingsState,
    permissionGranted: Boolean,
    isConnected: Boolean,
    onEnableSync: () -> Unit,
) {
    val status = when {
        !state.contactsSyncEnabled -> "Contact sync is off. Turn it on to share phone contacts with your Mac."
        !permissionGranted -> "Contacts permission is missing. Allow access so SYNQ can read your phone contacts."
        !isConnected -> "Permission is ready. Connect to your Mac; contacts sync when Mac requests them."
        state.lastContactsSyncCount == null -> "Ready. Open Call from Mac and choose Sync from Phone to send contacts."
        state.lastContactsSyncCount == 0 -> "Last sync found no contacts with phone numbers."
        else -> "Last sync sent ${state.lastContactsSyncCount} contacts."
    }
    val syncedAt = state.lastContactsSyncAtMillis?.let { timestamp ->
        android.text.format.DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Contact sync status", fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (syncedAt != null) {
                Text("Last successful sync: $syncedAt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.contactsSyncEnabled || !permissionGranted) {
                OutlinedButton(onClick = onEnableSync, shape = RoundedCornerShape(12.dp)) {
                    Text(if (!state.contactsSyncEnabled) "Enable contact sync" else "Grant contacts permission")
                }
            }
        }
    }
}

private val CALL_INTEGRATION_PERMISSIONS = arrayOf(
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.ANSWER_PHONE_CALLS,
)

private data class NotificationAppItem(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null,
)

private fun loadAppIconBitmap(drawable: Drawable, sizePx: Int = 96): ImageBitmap? {
    return runCatching {
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceIn(48, 128) else sizePx
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceIn(48, 128) else sizePx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

@Composable
private fun FeatureToggle(title: String, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = enabled, onCheckedChange = onChanged)
        }
    }
}

@Composable
private fun PeerRow(peer: DiscoveredPeer, isPaired: Boolean, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_nav_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(peer.deviceNameHint, fontWeight = FontWeight.SemiBold)
                Text(if (isPaired) "Paired · click to reconnect" else "Click to pair", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TransferCard(
    transfer: FileTransferState,
    cancel: () -> Unit,
    retry: () -> Unit,
    onClear: () -> Unit = {},
) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(transfer.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        compactTransferStatus(transfer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                when {
                    transfer.active -> TextButton(onClick = cancel) { Text("Cancel") }
                    transfer.retryable -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = retry) { Text("Retry") }
                            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                                Text("✕", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    else -> {
                        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                            Text("✕", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val percent = transfer.progressPercent
            if (percent != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.weight(1f).height(6.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    Text("$percent%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            } else if (transfer.active) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
            }
        }
    }
}

private fun compactTransferStatus(transfer: FileTransferState): String = transfer.status
    .substringAfter(": ", transfer.status)
    .replace(Regex("^(Sending|Receiving)\\s+${Regex.escape(transfer.name)}:?\\s*"), "")
    .ifBlank { if (transfer.active) "Preparing…" else transfer.status }

@Composable
private fun ServiceCard(enabled: Boolean, title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(if (enabled) Color(0xFF2EAD69) else MaterialTheme.colorScheme.outline, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun SectionTitle(value: String) = Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

private fun resolveTreeUriToPath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary:")) {
            val relPath = docId.removePrefix("primary:")
            File(Environment.getExternalStorageDirectory(), relPath).canonicalPath
        } else {
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val volumeId = parts[0]
                val relPath = parts[1]
                val storageDir = File("/storage/$volumeId")
                if (storageDir.exists()) {
                    File(storageDir, relPath).canonicalPath
                } else {
                    File(Environment.getExternalStorageDirectory(), relPath).canonicalPath
                }
            } else {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}

