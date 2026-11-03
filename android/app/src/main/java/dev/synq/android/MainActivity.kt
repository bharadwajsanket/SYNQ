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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
                    shareManager = synq.shareManager,
                    updateManager = synq.updateManager,
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
    shareManager: AndroidShareManager,
    updateManager: SYNQUpdateManager,
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
    val shareState by shareManager.state.collectAsStateWithLifecycle()
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
    val updateStatus by updateManager.status.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    var currentDestination by rememberSaveable { mutableStateOf(NavDestination.HOME) }
    var permissionPrompt by remember { mutableStateOf<PermissionPrompt?>(null) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }

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
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showQrScanner = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR Code",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
                shareState = shareState,
                onClearShareState = shareManager::clearTransferState,
                onCancelShareTransfer = shareManager::cancelTransfer,
                nearbyAndroidPeers = peers.filter { it.platformHint.equals("android", ignoreCase = true) },
                onSendFilesToAndroid = { peer, uris -> shareManager.sendFiles(peer, uris, context.contentResolver) },
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
                pairing = pairing,
                onRefreshDiscovery = { (context.applicationContext as SYNQApplication).refreshDiscovery() },
                onPair = { peer ->
                    val host = peer.host
                    val port = peer.port
                    if (host != null && port != null) {
                        pairing.pair(host, port, peer.deviceNameHint)
                    }
                },
                onSendFilesToAndroid = { peer, uris ->
                    shareManager.sendFiles(peer, uris, context.contentResolver)
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
                onScanQR = { showQrScanner = true },
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
                updateManager = updateManager,
                updateStatus = updateStatus,
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
        AnimatedWelcomeDialog(
            onDismiss = onOnboardingComplete,
            onScanQR = {
                onOnboardingComplete()
                showQrScanner = true
            }
        )
    }

    if (showQrScanner) {
        QRScannerDialog(
            onDismiss = { showQrScanner = false },
            onPayloadScanned = { payload ->
                showQrScanner = false
                pairing.pair(
                    host = payload.host,
                    port = payload.port,
                    peerName = payload.deviceName,
                    userInitiated = true,
                    qrToken = payload.token
                )
            }
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

    if (shareState is AndroidShareState.IncomingOffer) {
        val offer = shareState as AndroidShareState.IncomingOffer
        AlertDialog(
            onDismissRequest = shareManager::rejectIncomingTransfer,
            title = { Text("Incoming File") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${offer.senderDeviceName} wants to send you a file:")
                    Text(offer.fileName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatFileSize(offer.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = shareManager::acceptIncomingTransfer) {
                    Text("Accept")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = shareManager::rejectIncomingTransfer) {
                    Text("Decline")
                }
            }
        )
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
        val isAndroidPeer = pairing.isAndroidPeerConnected
        val nearbyAndroidPeers = peers.filter { it.platformHint.equals("android", ignoreCase = true) }
        val targetAndroidPeer = nearbyAndroidPeers.firstOrNull()
        val isWebLink = content.files.isEmpty() && content.text?.let(::validatedWebLink) != null
        val requiredFeature = if (content.files.isNotEmpty()) SYNQFeature.FILES else (if (isWebLink) SYNQFeature.LINKS else SYNQFeature.CLIPBOARD)
        val featureAvailable = settings.isEnabled(
            requiredFeature,
            (pairingState as? PairingState.Connected)?.deviceId,
        ) && remoteFeatures[requiredFeature] != false

        val sendToAndroidDirect = !isConnected && content.files.isNotEmpty() && targetAndroidPeer != null
        val currentPairing = pairingState
        val targetName = if (sendToAndroidDirect && targetAndroidPeer != null) {
            targetAndroidPeer.deviceNameHint
        } else if (currentPairing is PairingState.Connected) {
            currentPairing.peerName
        } else {
            "peer"
        }

        val summary = when {
            isWebLink -> if (isAndroidPeer) "Web links are not supported for Android peers." else "Open this webpage on your Mac?"
            content.files.size == 1 -> "Send the selected file to $targetName?"
            content.files.isNotEmpty() -> "Send ${content.files.size} selected files to $targetName?"
            else -> "Send the shared text to $targetName?"
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
                        !isConnected && !sendToAndroidDirect -> Text(
                            "Waiting for your paired device to reconnect or a nearby device.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        isAndroidPeer && isWebLink -> Text(
                            "Web link continuation is only available with Mac.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        !sendToAndroidDirect && !featureAvailable -> Text(
                            "This feature is turned off on one of your devices.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = (isConnected && featureAvailable && !(isAndroidPeer && isWebLink)) || sendToAndroidDirect,
                    onClick = {
                        if (sendToAndroidDirect && targetAndroidPeer != null) {
                            shareManager.sendFiles(targetAndroidPeer, content.files, context.contentResolver)
                        } else if (isWebLink) {
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
    shareState: AndroidShareState,
    onClearShareState: () -> Unit,
    onCancelShareTransfer: () -> Unit,
    nearbyAndroidPeers: List<DiscoveredPeer>,
    onSendFilesToAndroid: (DiscoveredPeer, List<Uri>) -> Unit,
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
    val isAndroidPeer = pairing.isAndroidPeerConnected
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(pairing::sendFile)
    }

    var pendingAndroidRecipient by remember { mutableStateOf<DiscoveredPeer?>(null) }
    val androidSharePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val recipient = pendingAndroidRecipient
        if (recipient != null && uris.isNotEmpty()) {
            onSendFilesToAndroid(recipient, uris)
        }
        pendingAndroidRecipient = null
    }

    val isMacConnected = pairingState is PairingState.Connected && !isAndroidPeer
    val connectedDeviceName = (pairingState as? PairingState.Connected)?.peerName ?: "Mac"

    val mediaManager = (context.applicationContext as SYNQApplication).mediaManager
    val mediaState by mediaManager.state.collectAsStateWithLifecycle()
    val macMedia by pairing.quickActions.media.collectAsStateWithLifecycle()

    val macArtworkBitmap = remember(macMedia.artwork) {
        macMedia.artwork?.let { base64 ->
            runCatching {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                if (bytes.size <= 32768) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            }.getOrNull()
        }
    }

    var currentMacPosition by remember(macMedia.title, macMedia.position, macMedia.playing) {
        mutableIntStateOf(macMedia.position)
    }

    LaunchedEffect(macMedia.title, macMedia.playing, macMedia.position) {
        currentMacPosition = macMedia.position
        if (macMedia.playing && macMedia.duration > 0) {
            while (isActive) {
                delay(1000)
                if (currentMacPosition < macMedia.duration) {
                    currentMacPosition += 1
                }
            }
        }
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
                                if (trustedDevices.isEmpty()) "Connect your devices" else "Ready to connect",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (trustedDevices.isEmpty())
                                    "Pair with your Mac or another Android phone over local Wi-Fi to share files."
                                else
                                    "Keep your devices on the same network to connect automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onNavigateToDevices) {
                                Text(if (trustedDevices.isEmpty()) "Pair a device" else "View paired devices")
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
                                        painter = painterResource(if (isAndroidPeer) R.drawable.ic_device_phone else R.drawable.ic_nav_devices),
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
                if (!isAndroidPeer) {
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
                }
                val hasAndroidRecipients = nearbyAndroidPeers.isNotEmpty()
                DashboardActionTile(
                    title = "Send Files",
                    subtitle = if (pairingState is PairingState.Connected) "Send to ${pairingState.peerName}"
                    else if (hasAndroidRecipients) "Send to nearby phone"
                    else "Pick files",
                    enabled = (isConnected && enabledFeatures[SYNQFeature.FILES] != false) || hasAndroidRecipients,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isConnected && enabledFeatures[SYNQFeature.FILES] != false) {
                            filePicker.launch(arrayOf("*/*"))
                        } else if (nearbyAndroidPeers.size == 1) {
                            pendingAndroidRecipient = nearbyAndroidPeers.first()
                            androidSharePicker.launch(arrayOf("*/*"))
                        } else {
                            onNavigateToDevices()
                        }
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

        val nowPlaying = resolveNowPlayingDisplay(
            isMacConnected = isMacConnected,
            macMedia = macMedia,
            phoneMedia = mediaState,
            macName = connectedDeviceName
        )

        when (nowPlaying) {
            is NowPlayingDisplay.Active -> {
                item { SectionTitle("Now playing") }
                item {
                    NowPlayingCard(
                        title = nowPlaying.title,
                        subtitle = nowPlaying.subtitle,
                        artwork = if (nowPlaying.isMacSource) macArtworkBitmap else mediaState.artwork,
                        playing = nowPlaying.playing,
                        positionSeconds = if (nowPlaying.isMacSource) currentMacPosition.toLong() else nowPlaying.positionSeconds,
                        durationSeconds = nowPlaying.durationSeconds,
                        volume = nowPlaying.volume,
                        maxVolume = if (nowPlaying.isMacSource) 100 else mediaState.maxVolume,
                        volumeSupported = if (nowPlaying.isMacSource) true else mediaState.volumeSupported,
                        canPlayPause = if (nowPlaying.isMacSource) true else mediaState.canPlayPause,
                        canSkipNext = if (nowPlaying.isMacSource) true else mediaState.canSkipNext,
                        canSkipPrevious = if (nowPlaying.isMacSource) true else mediaState.canSkipPrevious,
                        canSeek = if (nowPlaying.isMacSource) nowPlaying.durationSeconds > 0 else mediaState.canSeek,
                        sources = if (nowPlaying.isMacSource) nowPlaying.sources else emptyList(),
                        selectedSourceId = if (nowPlaying.isMacSource) nowPlaying.selectedSourceId else "",
                        onSelectSource = { sourceId -> pairing.quickActions.selectSource(sourceId) },
                        onToggle = if (nowPlaying.isMacSource) { { pairing.quickActions.request(SYNQFeature.MEDIA, "toggle") } } else mediaManager::togglePlayPause,
                        onNext = if (nowPlaying.isMacSource) { { pairing.quickActions.request(SYNQFeature.MEDIA, "next") } } else mediaManager::skipNext,
                        onPrev = if (nowPlaying.isMacSource) { { pairing.quickActions.request(SYNQFeature.MEDIA, "previous") } } else mediaManager::skipPrevious,
                        onSeek = if (nowPlaying.isMacSource) { { pos -> pairing.quickActions.request(SYNQFeature.MEDIA, "seek", pos.toString()) } } else mediaManager::seekTo,
                        onVolume = if (nowPlaying.isMacSource) { { vol -> pairing.quickActions.request(SYNQFeature.MEDIA, "volume", vol.toString()) } } else mediaManager::setVolume,
                    )
                }
            }
            is NowPlayingDisplay.Empty -> {
                item { SectionTitle("Now playing") }
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(54.dp).background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    nowPlaying.title,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    nowPlaying.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            is NowPlayingDisplay.Hidden -> {}
        }

        // SYNQ Link Nearby Sharing
        item {
            SynqLinkSection(
                nearbyAndroidPeers = nearbyAndroidPeers,
                activeTransfer = shareState,
                onSendFiles = { peer ->
                    pendingAndroidRecipient = peer
                    androidSharePicker.launch(arrayOf("*/*"))
                },
                onCancelTransfer = onCancelShareTransfer,
                onClearTransfer = onClearShareState,
                onNavigateToDevices = onNavigateToDevices
            )
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
    pairing: PairingCoordinator,
    onRefreshDiscovery: () -> Unit,
    onPair: (DiscoveredPeer) -> Unit,
    onSendFilesToAndroid: (DiscoveredPeer, List<Uri>) -> Unit,
    onReconnect: (TrustedDevice) -> Unit,
    onForget: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScanQR: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var deviceToForget by remember { mutableStateOf<TrustedDevice?>(null) }
    var androidRecipientForPicker by remember { mutableStateOf<DiscoveredPeer?>(null) }
    val androidFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = androidRecipientForPicker
        if (target != null && uris.isNotEmpty()) {
            onSendFilesToAndroid(target, uris)
        }
        androidRecipientForPicker = null
    }

    val queuedConnection by pairing.queuedConnectionRequest.collectAsStateWithLifecycle()
    val connected = pairingState as? PairingState.Connected
    val trustedIdsSet = remember(trustedDevices) { trustedDevices.map { it.id.lowercase() }.toSet() }
    val trustedNamesSet = remember(trustedDevices) { trustedDevices.map { it.name.trim().lowercase() }.toSet() }

    val nearbyPeers = remember(peers, trustedIdsSet, trustedNamesSet, connected) {
        val seen = mutableSetOf<String>()
        peers.filter { peer ->
            val peerId = peer.deviceIdHint?.lowercase()
            val peerName = peer.deviceNameHint.trim().lowercase()
            if (peerId != null && (trustedIdsSet.contains(peerId) || (connected != null && peerId == connected.deviceId.lowercase()))) {
                return@filter false
            }
            if (connected != null && peerName == connected.peerName.trim().lowercase()) {
                return@filter false
            }
            val dedupeKey = peerId ?: peer.serviceName
            seen.add(dedupeKey)
        }
    }

    val nearbyMacs = remember(nearbyPeers) {
        nearbyPeers.filter { !it.platformHint.equals("android", ignoreCase = true) }
    }
    val nearbyAndroidPeers = remember(nearbyPeers) {
        nearbyPeers.filter { it.platformHint.equals("android", ignoreCase = true) }
    }

    val uncoupledTrusted = remember(trustedDevices, connected) {
        trustedDevices.filter { connected == null || !it.id.equals(connected.deviceId, ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Device management", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRefreshDiscovery) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan again")
                }
            }
        }

        // Pending Connection Requests
        val pending = queuedConnection
        if (pending != null) {
            item { SectionTitle("Pending connection request") }
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pending.deviceName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Connection request waiting for current session to finish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { pairing.processQueuedConnection() }) {
                                Text("Accept")
                            }
                            OutlinedButton(onClick = { pairing.dismissQueuedConnection() }) {
                                Text("Decline")
                            }
                        }
                    }
                }
            }
        }
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
        } else {
            item {
                Card(
                    onClick = onScanQR,
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Scan QR Code to Pair",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Instantly connect by scanning the QR code on your Mac",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        item { SectionTitle("Paired devices") }
        if (uncoupledTrusted.isEmpty()) {
            item {
                Text(
                    if (connected != null && trustedDevices.isNotEmpty()) "Connected Mac is displayed above." else "No devices paired yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(uncoupledTrusted, key = { it.id }) { device ->
                val isOnline = peers.any { peer ->
                    peer.deviceIdHint?.equals(device.id, ignoreCase = true) == true ||
                    peer.deviceNameHint.trim().equals(device.name.trim(), ignoreCase = true)
                }
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).background(if (isOnline) Color(0xFF2EAD69) else MaterialTheme.colorScheme.outline, CircleShape))
                                Text(
                                    if (isOnline) "Available on local Wi-Fi" else "Offline / Searching",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isOnline) Color(0xFF2EAD69) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (connected?.deviceId != device.id) {
                            Button(
                                enabled = isOnline,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReconnect(device)
                                }
                            ) {
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

        if (nearbyAndroidPeers.isNotEmpty()) {
            item { SectionTitle("Nearby Android devices (Direct share)") }
            items(nearbyAndroidPeers, key = { it.serviceName }) { peer ->
                PeerRow(peer = peer, isPaired = false) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    androidRecipientForPicker = peer
                    androidFilePicker.launch(arrayOf("*/*"))
                }
            }
        }

        item { SectionTitle("Nearby Mac devices") }
        if (nearbyMacs.isEmpty()) {
            item {
                if (peers.any { !it.platformHint.equals("android", ignoreCase = true) }) {
                    Text(
                        "All discovered Mac devices are already paired.",
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
            items(nearbyMacs, key = { it.serviceName }) { peer ->
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

private fun maskPhoneNumber(number: String): String {
    val clean = number.trim()
    val digits = clean.filter { it.isDigit() }
    if (digits.length <= 4) return clean
    val keepLast = 4
    val totalDigits = digits.length
    val maskStart = if (totalDigits > 7) 3 else 1
    val maskEnd = totalDigits - keepLast
    var dIdx = 0
    val sb = StringBuilder()
    for (ch in clean) {
        if (ch.isDigit()) {
            if (dIdx >= maskStart && dIdx < maskEnd) {
                sb.append('•')
            } else {
                sb.append(ch)
            }
            dIdx++
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceBottomSheet(
    currentThemeMode: String,
    onThemeModeSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Choose how SYNQ appears on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val options = listOf(
                Triple("system", "System default", "Follow your Android system dark or light theme"),
                Triple("light", "Light", "Clean warm ivory aesthetic"),
                Triple("dark", "Dark", "Deep charcoal washi aesthetic"),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                options.forEachIndexed { index, (mode, label, desc) ->
                    val isSelected = currentThemeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeModeSelected(mode) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { onThemeModeSelected(mode) }
                        )
                    }
                    if (index < options.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
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
    updateManager: SYNQUpdateManager,
    updateStatus: UpdateStatus,
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
    var showAppearanceSheet by remember { mutableStateOf(false) }
    var showAddFavouriteDialog by remember { mutableStateOf(false) }
    var newFavouriteName by remember { mutableStateOf("") }
    var newFavouriteNumber by remember { mutableStateOf("") }
    var showPermissionsPage by rememberSaveable { mutableStateOf(false) }

    if (showAppearanceSheet) {
        AppearanceBottomSheet(
            currentThemeMode = state.themeMode,
            onThemeModeSelected = { mode ->
                onThemeModeChanged(mode)
                showAppearanceSheet = false
            },
            onDismissRequest = { showAppearanceSheet = false },
        )
    }

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
                        placeholder = { Text("e.g. Mom") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newFavouriteNumber,
                        onValueChange = { newFavouriteNumber = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("e.g. +1 555 123 4567") },
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
        // Section 1: Sync
        item { SectionTitle("Sync") }
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
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Notification forwarding",
                        subtitle = "Forward incoming phone notifications securely to your Mac",
                        checked = state.globalFeatures[SYNQFeature.NOTIFICATIONS] != false,
                        onCheckedChange = { onGlobalFeatureChanged(SYNQFeature.NOTIFICATIONS, it) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    SettingToggleRow(
                        title = "Quiet Mode",
                        subtitle = "Silence forwarded SYNQ notifications on this device",
                        checked = state.quietModeEnabled,
                        onCheckedChange = onQuietModeChanged,
                    )
                }
            }
        }

        // Section 2: Calls & Contacts
        item { SectionTitle("Calls & Contacts") }
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
                                        maskPhoneNumber(contact.number),
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

        // Section 3: Privacy
        item { SectionTitle("Privacy") }
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

        // Section 4: General
        item { SectionTitle("General") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppearanceSheet = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Appearance", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (state.themeMode) {
                                    "light" -> "Light"
                                    "dark" -> "Dark"
                                    else -> "System default"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showAppearanceSheet = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Change")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
        }

        // Section 5: About
        item { SectionTitle("About") }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "v${updateManager.currentVersionName}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        "SYNQ communicates locally and directly with your Mac. No accounts, external servers, analytics, or third-party relays are used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // OTA Updates Section
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Software updates",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )

                        when (val s = updateStatus) {
                            is UpdateStatus.Idle -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "SYNQ ${updateManager.currentVersionName} is installed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { updateManager.checkForUpdates() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Check for updates")
                                    }
                                }
                            }
                            is UpdateStatus.Checking -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Text("Checking for updates…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            is UpdateStatus.UpToDate -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "SYNQ is up to date (v${s.version})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF2EAD69)
                                    )
                                    OutlinedButton(
                                        onClick = { updateManager.checkForUpdates() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Check again")
                                    }
                                }
                            }
                            is UpdateStatus.UpdateAvailable -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "New version available: v${s.manifest.version}",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (s.manifest.releaseNotes.isNotEmpty()) {
                                        Text(
                                            s.manifest.releaseNotes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = { updateManager.downloadUpdate(s.manifest) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Download update (${s.manifest.size / 1024 / 1024} MB)")
                                    }
                                }
                            }
                            is UpdateStatus.Downloading -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Downloading update…", style = MaterialTheme.typography.bodySmall)
                                        Text("${s.progressPercent}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { s.progressPercent / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = { updateManager.cancelDownload() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Cancel")
                                    }
                                }
                            }
                            is UpdateStatus.ReadyToInstall -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Update v${s.manifest.version} ready to install",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2EAD69)
                                    )
                                    Button(
                                        onClick = { updateManager.installUpdate(s.apkFile) },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Install update")
                                    }
                                }
                            }
                            is UpdateStatus.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        s.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Button(
                                        onClick = { updateManager.checkForUpdates() },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
    val isPhone = peer.platformHint.equals("android", ignoreCase = true)
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (isPhone) R.drawable.ic_device_phone else R.drawable.ic_nav_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(peer.deviceNameHint, fontWeight = FontWeight.SemiBold)
                val subtitle = if (isPhone) "Android · Tap to send files"
                else if (isPaired) "Mac · Paired · tap to reconnect"
                else "Mac · Tap to pair"
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AndroidTransferCard(
    transfer: AndroidShareState.Transferring,
    onCancel: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (transfer.isError) MaterialTheme.colorScheme.errorContainer
            else if (transfer.isCompleted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(transfer.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${if (transfer.isSending) "To" else "From"} ${transfer.peerName} · ${transfer.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (transfer.isCompleted || transfer.isError) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Dismiss")
                    }
                } else {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
            if (!transfer.isCompleted && !transfer.isError) {
                val progressFraction = if (transfer.totalBytes > 0) {
                    (transfer.bytesTransferred.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    title: String,
    subtitle: String,
    artwork: Bitmap?,
    playing: Boolean,
    positionSeconds: Long,
    durationSeconds: Long,
    volume: Int,
    maxVolume: Int,
    volumeSupported: Boolean,
    canPlayPause: Boolean,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
    canSeek: Boolean,
    sources: List<MediaSourceItem> = emptyList(),
    selectedSourceId: String = "",
    onSelectSource: (String) -> Unit = {},
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sources.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Audio source",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { expanded = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(6.dp).background(
                                        if (playing) Color(0xFF2EAD69) else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                                )
                                Text(
                                    sources.firstOrNull { it.id == selectedSourceId }?.name
                                        ?: if (selectedSourceId.isEmpty() || selectedSourceId == "auto") "Auto" else selectedSourceId,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Source",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Auto", fontWeight = FontWeight.SemiBold)
                                        Text("(Active playback)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    onSelectSource("auto")
                                },
                                leadingIcon = {
                                    if (selectedSourceId.isEmpty() || selectedSourceId == "auto") {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            )
                            HorizontalDivider()
                            sources.forEach { src ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(src.name, fontWeight = FontWeight.SemiBold)
                                                if (src.playing) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = Color(0xFF2EAD69).copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            "Playing",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFF2EAD69),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            if (src.title.isNotEmpty()) {
                                                Text(
                                                    src.title,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        expanded = false
                                        onSelectSource(src.id)
                                    },
                                    leadingIcon = {
                                        if (src.id == selectedSourceId) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork.asImageBitmap(),
                        contentDescription = "Artwork",
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.size(54.dp).background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp)
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifEmpty { "Playing" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (durationSeconds > 0) {
                var seekPos by remember(positionSeconds) { mutableFloatStateOf(positionSeconds.toFloat()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${seekPos.toInt() / 60}:${(seekPos.toInt() % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${durationSeconds / 60}:${(durationSeconds % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = seekPos.coerceIn(0f, durationSeconds.toFloat()),
                    enabled = canSeek,
                    onValueChange = { seekPos = it },
                    valueRange = 0f..durationSeconds.toFloat(),
                    onValueChangeFinished = { onSeek(seekPos.toLong()) },
                    modifier = Modifier.fillMaxWidth().height(20.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrev,
                    enabled = canSkipPrevious
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
                }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = onToggle,
                    enabled = canPlayPause,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                IconButton(
                    onClick = onNext,
                    enabled = canSkipNext
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }

            if (volumeSupported && maxVolume > 0) {
                var vol by remember(volume) { mutableFloatStateOf(volume.toFloat()) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = vol.coerceIn(0f, maxVolume.toFloat()),
                        onValueChange = { vol = it },
                        valueRange = 0f..maxVolume.toFloat(),
                        onValueChangeFinished = { onVolume(vol.toInt()) },
                        modifier = Modifier.weight(1f).height(16.dp)
                    )
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AndroidMusicCard(
    media: AndroidMediaState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Int) -> Unit,
) {
    NowPlayingCard(
        title = media.title.ifEmpty { "Playing" },
        subtitle = "${media.artist.ifEmpty { "Unknown artist" }} · ${media.playerName}",
        artwork = media.artwork,
        playing = media.playing,
        positionSeconds = media.positionSeconds,
        durationSeconds = media.durationSeconds,
        volume = media.volume,
        maxVolume = media.maxVolume,
        volumeSupported = media.volumeSupported,
        canPlayPause = media.canPlayPause,
        canSkipNext = media.canSkipNext,
        canSkipPrevious = media.canSkipPrevious,
        canSeek = media.canSeek,
        onToggle = onToggle,
        onNext = onNext,
        onPrev = onPrev,
        onSeek = onSeek,
        onVolume = onVolume
    )
}

@Composable
private fun SynqLinkSection(
    nearbyAndroidPeers: List<DiscoveredPeer>,
    activeTransfer: AndroidShareState,
    onSendFiles: (DiscoveredPeer) -> Unit,
    onCancelTransfer: () -> Unit,
    onClearTransfer: () -> Unit,
    onNavigateToDevices: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("SYNQ Link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        "Nearby Sharing",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (activeTransfer is AndroidShareState.Transferring) {
            AndroidTransferCard(
                transfer = activeTransfer,
                onCancel = onCancelTransfer,
                onDismiss = onClearTransfer
            )
        }

        if (nearbyAndroidPeers.isNotEmpty()) {
            nearbyAndroidPeers.forEach { peer ->
                Card(
                    onClick = { onSendFiles(peer) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_device_phone),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.deviceNameHint, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            Text("Available · Tap to send files", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2EAD69))
                        }
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else if (activeTransfer !is AndroidShareState.Transferring) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_device_phone),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Looking for nearby Android devices…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Make sure other phones have SYNQ open on this Wi-Fi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 6)
    val pre = "KMGTPE"[exp - 1]
    return String.format(java.util.Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
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

