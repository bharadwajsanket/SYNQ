package dev.synq.android

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.os.SystemClock
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedWriter
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.json.JSONArray

sealed interface PairingState {
    data object Idle : PairingState
    data class Connecting(val peerName: String) : PairingState
    data class Verification(val peerName: String, val code: String) : PairingState
    data class Connected(val deviceId: String, val peerName: String) : PairingState
    data class Failed(val message: String) : PairingState
}

enum class ClipboardSendResult {
    DELIVERED,
    EMPTY,
    DISABLED,
    NOT_CONNECTED,
    CONNECTION_LOST,
    NO_ACKNOWLEDGEMENT,
    TOO_LARGE,
}

data class FileTransferState(
    val id: String,
    val name: String,
    val status: String,
    val active: Boolean,
    val progressPercent: Int?,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val retryable: Boolean = false,
)

data class TrustedDevice(val id: String, val name: String)

data class RemoteBatteryStatus(val level: Int, val isCharging: Boolean)

class PairingCoordinator(
    context: Context,
    private val localDeviceId: String,
    localDeviceName: String,
    private val port: Int = 42_458,
    private val settings: SYNQSettings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val quickActions = QuickActions(appContext, scope, ::isFeatureAvailable, ::sendQuickPayload)
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = mutableState.asStateFlow()
    private val identity = AndroidIdentity(context.applicationContext)
    private val trust = AndroidTrustRegistry(context.applicationContext)
    val trustedDeviceIds: StateFlow<Set<String>> = trust.trustedDeviceIds
    val trustedDevices: StateFlow<List<TrustedDevice>> = trust.trustedDevices
    @Volatile private var localDeviceName = localDeviceName
    private val mutableClipboardStatus = MutableStateFlow<String?>(null)
    val clipboardStatus: StateFlow<String?> = mutableClipboardStatus.asStateFlow()
    private val pendingClipboardSends = ConcurrentHashMap<String, (ClipboardSendResult) -> Unit>()
    private val pendingFileAccepts = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingFileCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutableFileTransferStatus = MutableStateFlow<String?>(null)
    val fileTransferStatus: StateFlow<String?> = mutableFileTransferStatus.asStateFlow()
    private val mutableFileTransferActive = MutableStateFlow(false)
    val fileTransferActive: StateFlow<Boolean> = mutableFileTransferActive.asStateFlow()
    private val mutableFileTransfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val fileTransfers: StateFlow<Map<String, FileTransferState>> = mutableFileTransfers.asStateFlow()
    private val mutablePhoneRinging = MutableStateFlow(false)
    val phoneRinging: StateFlow<Boolean> = mutablePhoneRinging.asStateFlow()
    private val mutableMacRinging = MutableStateFlow(false)
    val macRinging: StateFlow<Boolean> = mutableMacRinging.asStateFlow()
    private val mutableRemoteBattery = MutableStateFlow<RemoteBatteryStatus?>(null)
    val remoteBattery: StateFlow<RemoteBatteryStatus?> = mutableRemoteBattery.asStateFlow()
    private val mutablePingStatus = MutableStateFlow<String?>(null)
    val pingStatus: StateFlow<String?> = mutablePingStatus.asStateFlow()
    private var pendingPingId: String? = null
    private val mutableRemoteFeatures = MutableStateFlow(defaultFeatureState())
    val remoteFeatures: StateFlow<Map<SYNQFeature, Boolean>> = mutableRemoteFeatures.asStateFlow()
    private val incomingFiles = ConcurrentHashMap<String, IncomingFileTransfer>()
    private val cancelledTransferIds = ConcurrentHashMap.newKeySet<String>()
    private val outgoingFileJobs = ConcurrentHashMap<String, Job>()
    private val outgoingFileSources = ConcurrentHashMap<String, Uri>()
    private val outgoingLocalFileSources = ConcurrentHashMap<String, File>()
    private val processedSmsSendIds = ConcurrentHashMap.newKeySet<String>()
    val deviceId: String get() = localDeviceId
    private var server: ServerSocket? = null
    private var session: Session? = null
    private var acceptJob: Job? = null
    private var findRingtone: Ringtone? = null
    private val diagnostics = SYNQDiagnostics()
    private val remoteCallRequest = RemoteCallRequest(appContext)
    private var lastRemoteCallRequestAt = 0L
    var automaticReconnectSuppressed: Boolean = false
        private set

    init {
        scope.launch {
            settings.state.collect {
                if (!featureEnabled(SYNQFeature.CLIPBOARD)) mutableClipboardStatus.value = null
                if (!featureEnabled(SYNQFeature.BATTERY)) mutableRemoteBattery.value = null
                if (!featureEnabled(SYNQFeature.PING)) clearPingStatus()
                quickActions.policyChanged()
                sendFeatureState()
                sendQuietModeState()
            }
        }
    }

    fun start() {
        if (acceptJob != null) return
        diagnostics.record("transport", "listener_started")
        acceptJob = scope.launch {
            runCatching {
                ServerSocket(port).also { server = it }.use { listener ->
                    while (!listener.isClosed) handle(listener.accept(), initiatedLocally = false, peerHint = null)
                }
            }.onFailure { if (server?.isClosed == false) fail("Pairing listener failed") }
        }
    }

    fun pair(host: String, port: Int, peerName: String, userInitiated: Boolean = true) {
        if (userInitiated) automaticReconnectSuppressed = false
        diagnostics.record("pairing", "connection_started")
        android.util.Log.i("SYNQ", "PAIRING started peer=$peerName")
        mutableState.value = PairingState.Connecting(peerName)
        scope.launch {
            runCatching {
                Socket().apply {
                    connect(java.net.InetSocketAddress(host, port), 6000)
                }
            }
                .onSuccess { handle(it, initiatedLocally = true, peerHint = peerName) }
                .onFailure {
                    if (trustedDeviceIds.value.isNotEmpty() && !userInitiated) {
                        mutableState.value = PairingState.Idle
                    } else {
                        fail("Could not connect to $peerName")
                    }
                }
        }
    }

    fun confirm() {
        scope.launch {
            val current = session ?: return@launch
            confirm(current)
        }
    }

    fun cancel() {
        automaticReconnectSuppressed = true
        val current = session
        session = null
        cancelIncomingFiles()
        current?.send(Message(kind = "pairing.cancel", sessionId = current.id))
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteBattery.value = null
        clearPingStatus()
        quickActions.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableState.value = PairingState.Idle
    }

    fun dismiss() {
        automaticReconnectSuppressed = true
        val current = session
        session = null
        cancelIncomingFiles()
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteBattery.value = null
        clearPingStatus()
        quickActions.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableState.value = PairingState.Idle
    }

    fun forget(deviceId: String) {
        trust.remove(deviceId)
        settings.removeDevice(deviceId)
        if ((mutableState.value as? PairingState.Connected)?.deviceId == deviceId) dismiss()
        android.util.Log.i("SYNQ", "PAIRING revoked peerId=${deviceId.take(8)}")
    }

    fun updateDeviceName(value: String) {
        localDeviceName = value.trim().take(64).ifBlank { "Android device" }
    }

    private fun featureEnabled(feature: SYNQFeature, current: Session? = session): Boolean =
        settings.isEnabled(feature, current?.remoteDeviceId?.takeIf(String::isNotEmpty))

    fun isFeatureAvailable(feature: SYNQFeature): Boolean =
        effectiveFeatureAvailable(featureEnabled(feature), mutableRemoteFeatures.value[feature] != false)

    fun sendClipboard() {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java) ?: return
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        sendText(text)
    }

    fun sendText(text: String, onResult: (ClipboardSendResult) -> Unit = {}) {
        sendClipboardContent(text = text, html = null, onResult = onResult)
    }

    private fun sendClipboardContent(
        text: String,
        html: String?,
        onResult: (ClipboardSendResult) -> Unit = {},
    ) {
        if (!isFeatureAvailable(SYNQFeature.CLIPBOARD)) {
            mutableClipboardStatus.value = "Clipboard is turned off on one of your devices"
            onResult(ClipboardSendResult.DISABLED)
            return
        }
        if (text.isEmpty()) {
            mutableClipboardStatus.value = "Clipboard is empty"
            onResult(ClipboardSendResult.EMPTY)
            return
        }
        if (!clipboardTextFits(text)) {
            mutableClipboardStatus.value = "Clipboard exceeds 32 KiB. Send large text or diagnostics as a file."
            onResult(ClipboardSendResult.TOO_LARGE)
            return
        }
        val connectedSession = session
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableClipboardStatus.value = "Not connected — clipboard was not sent"
            onResult(ClipboardSendResult.NOT_CONNECTED)
            return
        }
        scope.launch {
            val current = connectedSession
            if (session !== current || mutableState.value !is PairingState.Connected) {
                mutableClipboardStatus.value = "Not connected — clipboard was not sent"
                onResult(ClipboardSendResult.NOT_CONNECTED)
                return@launch
            }
            val messageId = UUID.randomUUID().toString()
            pendingClipboardSends[messageId] = onResult
            val richContent = RichClipboardContent.create(text, html)
            val plaintext = richContent?.encode() ?: text.toByteArray(Charsets.UTF_8)
            val encrypted = Crypto.encrypt(current.pairingKey!!, plaintext)
            val message = Message(
                kind = if (richContent == null) "clipboard.update" else "clipboard.rich",
                sessionId = current.id,
                messageId = messageId,
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            )
            if (!current.send(message)) {
                pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                mutableClipboardStatus.value = "Connection lost"
                current.close()
                return@launch
            }
            mutableClipboardStatus.value = "Sending…"
            android.util.Log.i("SYNQ", "PLUGIN clipboard sent")
            delay(3_000)
            if (pendingClipboardSends.containsKey(messageId) && session === current) {
                if (!current.send(message)) {
                    pendingClipboardSends.remove(messageId)?.invoke(ClipboardSendResult.CONNECTION_LOST)
                    mutableClipboardStatus.value = "Connection lost"
                    current.close()
                    return@launch
                }
                delay(3_000)
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "No delivery acknowledgement"
                    callback(ClipboardSendResult.NO_ACKNOWLEDGEMENT)
                }
            }
        }
    }

    fun sendBattery(level: Int, isCharging: Boolean) {
        if (!isFeatureAvailable(SYNQFeature.BATTERY)) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val payload = JSONObject()
                .put("level", level.coerceIn(0, 100))
                .put("isCharging", isCharging)
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
            if (connectedSession.send(
                    Message(
                        kind = "battery.update",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("SYNQ", "PLUGIN battery sent level=$level charging=$isCharging")
            }
        }
    }

    fun sendPing() {
        val current = session
        if (current == null || mutableState.value !is PairingState.Connected) {
            mutablePingStatus.value = "Mac is not connected"
            return
        }
        if (!isFeatureAvailable(SYNQFeature.PING)) {
            mutablePingStatus.value = "Ping requires SYNQ on both devices"
            return
        }
        val messageId = UUID.randomUUID().toString()
        val encrypted = Crypto.encrypt(current.pairingKey!!, JSONObject().put("version", 1).toString().toByteArray())
        pendingPingId = messageId
        mutablePingStatus.value = "Pinging Mac…"
        scope.launch {
            if (session !== current || !current.send(
                    Message(
                        kind = "ping.request",
                        sessionId = current.id,
                        messageId = messageId,
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping could not be sent"
                }
                return@launch
            }
            delay(5_000)
            if (pendingPingId == messageId) {
                pendingPingId = null
                mutablePingStatus.value = "Mac did not acknowledge the ping"
            }
        }
    }

    private fun receivePing(current: Session, message: Message) {
        if (!featureEnabled(SYNQFeature.PING, current)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted ping"),
            message.ciphertext ?: return fail("Invalid encrypted ping"),
        ) ?: return fail("Invalid encrypted ping")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getInt("version") }.getOrNull() != 1) {
            return fail("Invalid ping")
        }
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "Ping from ${current.peerName}", android.widget.Toast.LENGTH_SHORT).show()
            RingtoneManager.getRingtone(appContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
        current.send(Message(kind = "ping.ack", sessionId = current.id, messageId = messageId))
        android.util.Log.i("SYNQ", "PLUGIN ping received")
    }

    private fun clearPingStatus() {
        pendingPingId = null
        mutablePingStatus.value = null
    }

    private fun sendQuickPayload(kind: String, payload: JSONObject): Boolean {
        val current = session ?: return false
        val key = current.pairingKey ?: return false
        if (mutableState.value !is PairingState.Connected) return false
        val encrypted = Crypto.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
        scope.launch {
            if (session === current) current.send(Message(kind = kind, sessionId = current.id,
                messageId = UUID.randomUUID().toString(), nonce = encrypted.nonce, ciphertext = encrypted.ciphertext))
        }
        return true
    }

    private fun receiveQuickPayload(current: Session, message: Message) {
        if (session !== current || mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val key = current.pairingKey ?: return
        val id = message.messageId ?: return
        if (!current.acceptMessageId(id)) return
        val data = Crypto.decrypt(key, message.nonce ?: return, message.ciphertext ?: return) ?: return
        if (data.size > if (message.kind == "media.state") 32768 else 8192) return
        val payload = runCatching { JSONObject(data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (message.kind == "quick.request") {
            val raw = payload.opt("sequence")
            if (raw !is Int && raw !is Long) return
            val sequence = (raw as Number).toLong()
            if (!current.quickSequence.accept(payload.optString("feature"), sequence)) return
        }
        quickActions.receive(message.kind, payload)
    }

    fun sendNotification(
        packageName: String,
        applicationName: String,
        notificationId: String,
        title: String,
        text: String,
        timestamp: Long,
        actions: List<ForwardedNotificationAction> = emptyList(),
        applicationIcon: String? = null,
        callType: String? = null,
    ) {
        if (!isFeatureAvailable(SYNQFeature.NOTIFICATIONS)) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val payload = JSONObject()
                .put("packageName", packageName.take(256))
                .put("applicationName", applicationName.take(128))
                .put("notificationId", notificationId.take(512))
                .put("title", title.take(1_024))
                .put("text", text.take(8_192))
                .put("timestamp", timestamp)
                .apply {
                    if (applicationIcon != null && applicationIcon.length <= MAX_NOTIFICATION_ICON_BASE64_LENGTH) {
                        put("applicationIcon", applicationIcon)
                    }
                    if (callType in setOf("incoming", "ongoing", "screening", "unknown")) {
                        put("callType", callType)
                    }
                }
                .put("actions", JSONArray().apply {
                    actions.take(4).forEach { action ->
                        put(JSONObject()
                            .put("actionToken", action.token)
                            .put("title", action.title.take(64))
                            .put("allowsReply", action.allowsReply))
                    }
                })
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
            if (connectedSession.send(
                    Message(
                        kind = "notifications.post",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("SYNQ", "PLUGIN notification sent package=$packageName")
            }
        }
    }

    fun sendNotificationRemoved(notificationId: String) {
        sendNotificationReference("notifications.remove", notificationId)
    }

    fun sendOtp(code: String, sender: String, timestamp: Long) {
        if (!isFeatureAvailable(SYNQFeature.NOTIFICATIONS)) return
        if (!settings.state.value.otpAssistanceEnabled) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        val cleanCode = code.trim().filter { it.isLetterOrDigit() }.take(16)
        if (cleanCode.length !in 4..8) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val payload = JSONObject()
                .put("version", 1)
                .put("code", cleanCode)
                .put("sender", sender.trim().take(64).ifBlank { "Verification" })
                .put("timestamp", timestamp)
                .put("expirySeconds", 180)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
            if (connectedSession.send(
                    Message(
                        kind = "otp.offer",
                        sessionId = connectedSession.id,
                        messageId = UUID.randomUUID().toString(),
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext,
                    ),
                )
            ) {
                android.util.Log.i("SYNQ", "PLUGIN OTP candidate sent to Mac sender=${sender.take(16)}")
            }
        }
    }

    private fun sendNotificationReference(kind: String, notificationId: String) {
        if (!isFeatureAvailable(SYNQFeature.NOTIFICATIONS) || notificationId.isBlank()) return
        val connectedSession = session ?: return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch {
            if (session !== connectedSession || mutableState.value !is PairingState.Connected) return@launch
            val payload = JSONObject()
                .put("notificationId", notificationId.take(512))
                .toString()
                .toByteArray()
            val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, payload)
            connectedSession.send(
                Message(
                    kind = kind,
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext,
                ),
            )
        }
    }

    fun findMac() {
        if (!isFeatureAvailable(SYNQFeature.FIND_DEVICE)) return
        if (mutableState.value !is PairingState.Connected) return
        scope.launch { sendFindCommand("find.start") }
    }

    fun stopFinding() {
        stopPhoneRinging()
        scope.launch { sendFindCommand("find.stop") }
    }

    private fun sendFindCommand(kind: String): Boolean {
        val current = session ?: return false
        if (kind == "find.start" && !isFeatureAvailable(SYNQFeature.FIND_DEVICE)) return false
        if (mutableState.value !is PairingState.Connected) return false
        val payload = JSONObject().put("alertId", "active").toString().toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        return current.send(
            Message(
                kind = kind,
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFindCommand(current: Session, message: Message, start: Boolean) {
        if (start && !settings.isEnabled(SYNQFeature.FIND_DEVICE, current.remoteDeviceId)) return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted find-device message"),
            message.ciphertext ?: return fail("Invalid encrypted find-device message"),
        ) ?: return fail("Invalid encrypted find-device message")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.isFailure) {
            return fail("Invalid find-device message")
        }
        if (start) {
            startPhoneRinging()
            sendFindCommand(if (mutablePhoneRinging.value) "find.started" else "find.stopped")
        } else {
            stopPhoneRinging()
            mutableMacRinging.value = false
            sendFindCommand("find.stopped")
        }
        android.util.Log.i("SYNQ", "PLUGIN find-device ${if (start) "started" else "stopped"}")
    }

    private fun startPhoneRinging() {
        if (mutablePhoneRinging.value) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = true
        findRingtone = ringtone
        mutablePhoneRinging.value = true
        ringtone.play()
    }

    private fun stopPhoneRinging() {
        runCatching { findRingtone?.stop() }
        findRingtone = null
        mutablePhoneRinging.value = false
    }

    fun sendFile(uri: Uri) {
        if (!isFeatureAvailable(SYNQFeature.FILES)) {
            mutableFileTransferStatus.value = "File transfer is turned off on one of your devices"
            return
        }
        val connectedSession = session
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableFileTransferStatus.value = "Not connected — file was not sent"
            return
        }
        val resolver = appContext.contentResolver
        val metadata = runCatching {
            var name = "file"
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
                }
            }
            Triple(name.take(255), resolver.getType(uri) ?: "application/octet-stream", size)
        }.getOrNull()
        if (metadata == null) {
            mutableFileTransferStatus.value = "Could not read the selected file"
            return
        }
        val transferId = UUID.randomUUID().toString()
        outgoingFileSources[transferId] = uri
        startSendingFile(connectedSession, transferId, metadata.first, metadata.second, metadata.third) {
            appContext.contentResolver.openInputStream(uri)
        }
    }

    fun sendLocalFile(file: File) {
        if (!isFeatureAvailable(SYNQFeature.FILES)) {
            mutableFileTransferStatus.value = "File transfer is turned off on one of your devices"
            return
        }
        val connectedSession = session
        if (connectedSession == null || mutableState.value !is PairingState.Connected) {
            mutableFileTransferStatus.value = "Not connected — file was not sent"
            return
        }
        if (!file.exists() || !file.isFile || !file.canRead()) {
            mutableFileTransferStatus.value = "File cannot be read"
            return
        }
        val transferId = UUID.randomUUID().toString()
        outgoingLocalFileSources[transferId] = file
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        startSendingFile(connectedSession, transferId, file.name.take(255), mimeType, file.length()) {
            FileInputStream(file)
        }
    }

    private fun startSendingFile(
        connectedSession: Session,
        transferId: String,
        name: String,
        mimeType: String,
        size: Long,
        openInputStream: () -> InputStream?,
    ) {
        diagnostics.record("transfer", "send_started")
        if (size < 0) {
            mutableFileTransferStatus.value = "This file provider did not report a file size"
            updateFileTransfer(transferId, name, mutableFileTransferStatus.value!!, false)
            return
        }
        if (size > MAX_FILE_SIZE) {
            mutableFileTransferStatus.value = "File is larger than 10 GB"
            updateFileTransfer(transferId, name, mutableFileTransferStatus.value!!, false)
            return
        }
        updateFileTransfer(transferId, name, "Preparing $name…", true)
        val job = scope.launch {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = runCatching {
                openInputStream()?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } ?: error("File unavailable")
                Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            }.getOrElse {
                mutableFileTransferStatus.value = "Could not read the selected file"
                updateFileTransfer(transferId, name, mutableFileTransferStatus.value!!, false)
                return@launch
            }

            val offerPayload = JSONObject()
                .put("transferId", transferId)
                .put("name", name)
                .put("mimeType", mimeType)
                .put("size", size)
                .put("sha256", hash)
                .toString().toByteArray()
            val offer = Crypto.encrypt(connectedSession.pairingKey!!, offerPayload)
            val accepted = CompletableDeferred<Boolean>()
            pendingFileAccepts[transferId] = accepted
            if (!connectedSession.send(Message(
                    kind = "files.offer",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    transferId = transferId,
                    nonce = offer.nonce,
                    ciphertext = offer.ciphertext,
                ))) {
                pendingFileAccepts.remove(transferId)
                mutableFileTransferStatus.value = "Connection lost — file was not sent"
                updateFileTransfer(transferId, name, mutableFileTransferStatus.value!!, false)
                return@launch
            }
            updateFileTransfer(transferId, name, "Waiting for Mac…", true)
            if (withTimeoutOrNull(10_000) { accepted.await() } != true) {
                pendingFileAccepts.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, name, "Mac did not accept the file", false)
                return@launch
            }

            val completed = CompletableDeferred<Boolean>()
            pendingFileCompletions[transferId] = completed
            val sent = runCatching {
                openInputStream()?.use { input ->
                    val buffer = ByteArray(FILE_CHUNK_SIZE)
                    var total = 0L
                    var sequence = 0L
                    val progress = TransferProgress(size)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        val encrypted = Crypto.encrypt(connectedSession.pairingKey!!, buffer.copyOf(count))
                        check(connectedSession.send(Message(
                            kind = "files.chunk",
                            sessionId = connectedSession.id,
                            messageId = UUID.randomUUID().toString(),
                            transferId = transferId,
                            sequence = sequence++,
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext,
                        )))
                        total += count
                        progress.status(total)?.let {
                            updateFileTransfer(transferId, name, "Sending $name: $it", true)
                        }
                    }
                    updateFileTransfer(transferId, name, "Verifying $name on Mac…", true)
                } ?: error("File unavailable")
                val completionPayload = Crypto.encrypt(
                    connectedSession.pairingKey!!,
                    JSONObject().put("transferId", transferId).put("sha256", hash).toString().toByteArray(),
                )
                check(connectedSession.send(Message(
                    kind = "files.complete",
                    sessionId = connectedSession.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = completionPayload.nonce,
                    ciphertext = completionPayload.ciphertext,
                )))
            }.isSuccess
            if (!sent) {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, name, "File transfer failed", false)
                return@launch
            }
            if (withTimeoutOrNull(15_000) { completed.await() } == true) {
                outgoingFileSources.remove(transferId)
                outgoingLocalFileSources.remove(transferId)
                updateFileTransfer(transferId, name, "$name saved on Mac", false)
                diagnostics.record("transfer", "send_completed")
            } else {
                pendingFileCompletions.remove(transferId)
                if (transferId in cancelledTransferIds || outgoingFileJobs[transferId] == null) return@launch
                updateFileTransfer(transferId, name, "Mac did not confirm the saved file", false)
            }
        }
        outgoingFileJobs[transferId] = job
        job.invokeOnCompletion { outgoingFileJobs.remove(transferId, job) }
    }

    fun cancelFileTransfer(transferId: String) {
        val current = session
        markTransferCancelled(transferId)
        scope.launch {
            repeat(3) { attempt ->
                if (session === current) current?.send(Message(kind = "files.cancel", sessionId = current.id, transferId = transferId))
                if (attempt < 2) delay(250)
            }
        }
        outgoingFileJobs.remove(transferId)?.cancel()
        incomingFiles.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        val name = mutableFileTransfers.value[transferId]?.name ?: "File"
        removeFileTransfer(transferId, "Transfer cancelled")
        diagnostics.record("transfer", "cancelled")
    }

    fun cancelFileTransfer() = mutableFileTransfers.value.values.filter { it.active }.forEach { cancelFileTransfer(it.id) }

    fun retryFileTransfer(transferId: String) {
        val uri = outgoingFileSources[transferId]
        val file = outgoingLocalFileSources[transferId]
        if (uri == null && file == null) return
        if (session == null || mutableState.value !is PairingState.Connected) {
            removeFileTransfer(transferId, "Reconnect before retrying")
            return
        }
        if (!isFeatureAvailable(SYNQFeature.FILES)) {
            removeFileTransfer(transferId, "File transfer is turned off on one of your devices")
            return
        }
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply { remove(transferId) }
        outgoingFileSources.remove(transferId)
        outgoingLocalFileSources.remove(transferId)
        diagnostics.record("transfer", "retry_started")
        if (uri != null) sendFile(uri)
        else if (file != null) sendLocalFile(file)
    }

    fun clearCompletedFileTransfer(transferId: String) {
        val transfer = mutableFileTransfers.value[transferId] ?: return
        if (transfer.active) return
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply { remove(transferId) }
        outgoingFileSources.remove(transferId)
        outgoingLocalFileSources.remove(transferId)
    }

    fun clearCompletedFileTransfers() {
        val remaining = mutableFileTransfers.value.filterValues { it.active }
        mutableFileTransfers.value = remaining
        val activeIds = remaining.keys
        outgoingFileSources.keys.retainAll(activeIds)
        outgoingLocalFileSources.keys.retainAll(activeIds)
    }

    fun diagnosticsReport(): String {
        val stateName = when (mutableState.value) {
            PairingState.Idle -> "idle"
            is PairingState.Connecting -> "connecting"
            is PairingState.Verification -> "verification"
            is PairingState.Connected -> "connected"
            is PairingState.Failed -> "failed"
        }
        val localFeatures = SYNQFeature.entries.associateWith { settings.isEnabled(it, null) }
        return diagnostics.report(appContext, stateName, mutableFileTransfers.value.values, localFeatures, mutableRemoteFeatures.value)
    }

    fun clearTransferHistory() {
        val inactiveIds = mutableFileTransfers.value.values.filterNot(FileTransferState::active).map(FileTransferState::id)
        inactiveIds.forEach {
            outgoingFileSources.remove(it)
            outgoingLocalFileSources.remove(it)
        }
        mutableFileTransfers.value = mutableFileTransfers.value.filterValues(FileTransferState::active)
        refreshFileTransferSummary()
    }

    fun stop() {
        pause()
        scope.cancel()
    }

    fun pause() {
        val current = session
        session = null
        current?.close()
        stopPhoneRinging()
        mutableMacRinging.value = false
        mutableRemoteBattery.value = null
        clearPingStatus()
        quickActions.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        server?.close()
        server = null
        acceptJob?.cancel()
        acceptJob = null
        pendingClipboardSends.values.forEach { it(ClipboardSendResult.NOT_CONNECTED) }
        pendingClipboardSends.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        mutableState.value = PairingState.Idle
    }

    private fun handle(socket: Socket, initiatedLocally: Boolean, peerHint: String?) {
        session?.close()
        socket.keepAlive = true
        socket.tcpNoDelay = true
        socket.soTimeout = HEARTBEAT_INTERVAL_MILLIS.toInt()
        val current = Session(socket)
        current.initiatedLocally = initiatedLocally
        session = current
        mutableRemoteBattery.value = null
        clearPingStatus()
        quickActions.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        if (initiatedLocally) {
            current.peerName = peerHint ?: "Mac"
            current.keyPair = Crypto.generateKeyPair()
            current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
            current.id = UUID.randomUUID().toString()
            current.send(
                Message(
                    kind = "pairing.offer",
                    sessionId = current.id,
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    publicKey = current.localEphemeralKey,
                ),
            )
        } else {
            mutableState.value = PairingState.Connecting(peerHint ?: "Mac")
        }
        val result = runCatching {
            while (true) {
                try {
                    val line = current.input.readProtocolLine() ?: break
                    current.lastReceivedAtMillis = SystemClock.elapsedRealtime()
                    receive(current, Message.decode(line))
                } catch (_: SocketTimeoutException) {
                    if (session !== current) break
                    if (mutableState.value is PairingState.Connected) {
                        if (heartbeatExpired(
                                supported = current.heartbeatSupported,
                                lastReceivedAtMillis = current.lastReceivedAtMillis,
                                nowMillis = SystemClock.elapsedRealtime(),
                            )
                        ) {
                            android.util.Log.w("SYNQ", "TRANSPORT heartbeat timed out")
                            break
                        }
                        if (!current.send(Message(
                                kind = "heartbeat.ping",
                                sessionId = current.id,
                                messageId = UUID.randomUUID().toString(),
                            ))
                        ) break
                    }
                }
            }
        }
        if (session === current && mutableState.value is PairingState.Connected) {
            session = null
            cancelIncomingFiles()
            stopPhoneRinging()
            mutableMacRinging.value = false
            mutableRemoteBattery.value = null
            clearPingStatus()
            quickActions.reset()
            mutableRemoteFeatures.value = defaultFeatureState()
            mutableState.value = PairingState.Idle
            diagnostics.record("transport", "disconnected", "reconnecting")
            android.util.Log.i("SYNQ", "TRANSPORT disconnected")
        } else if (session === current) {
            result.exceptionOrNull()?.let {
                android.util.Log.w("SYNQ", "PAIRING disconnected before confirmation")
            }
            if (trustedDeviceIds.value.isNotEmpty()) {
                session = null
                cancelIncomingFiles()
                stopPhoneRinging()
                mutableMacRinging.value = false
                mutableRemoteBattery.value = null
                clearPingStatus()
                quickActions.reset()
                mutableRemoteFeatures.value = defaultFeatureState()
                mutableState.value = PairingState.Idle
                diagnostics.record("transport", "disconnected", "reconnecting")
            } else {
                fail("Pairing connection lost")
            }
        }
    }

    private fun receive(current: Session, message: Message) {
        when (message.kind) {
            "quick.request", "quick.result", "quick.event", "media.state" -> receiveQuickPayload(current, message)
            "heartbeat.ping" -> {
                if (message.sessionId != current.id || mutableState.value !is PairingState.Connected) return
                current.heartbeatSupported = true
                current.send(Message(kind = "heartbeat.pong", sessionId = current.id, messageId = message.messageId))
            }
            "heartbeat.pong" -> {
                if (message.sessionId == current.id && mutableState.value is PairingState.Connected) {
                    current.heartbeatSupported = true
                }
            }
            "pairing.offer" -> {
                current.id = message.sessionId
                current.peerName = message.deviceName ?: "Android device"
                current.remoteDeviceId = message.deviceId ?: return
                current.remoteEphemeralKey = message.publicKey ?: return
                current.keyPair = Crypto.generateKeyPair()
                current.localEphemeralKey = Crypto.encodePublicKey(current.keyPair!!)
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                current.send(
                    Message(
                        kind = "pairing.answer",
                        sessionId = current.id,
                        deviceId = localDeviceId,
                        deviceName = localDeviceName,
                        publicKey = current.localEphemeralKey,
                    ),
                )
                authenticateOrPrompt(current)
            }
            "pairing.answer" -> {
                if (message.sessionId != current.id) return
                current.peerName = message.deviceName ?: current.peerName
                current.remoteDeviceId = message.deviceId ?: return
                current.remoteEphemeralKey = message.publicKey ?: return
                val material = Crypto.pairingMaterial(current.keyPair!!, current.remoteEphemeralKey, current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            }
            "pairing.confirm" -> {
                if (message.sessionId != current.id) return
                val remoteId = message.deviceId ?: return fail("Invalid pairing confirmation")
                val identityKey = message.identityKey ?: return fail("Invalid pairing confirmation")
                val proof = message.proof ?: return fail("Invalid pairing confirmation")
                val signature = message.signature ?: return fail("Invalid pairing confirmation")
                val pinnedKey = trust.identityKey(remoteId)
                if (pinnedKey != null && pinnedKey != identityKey) return fail("Pinned identity changed")
                if (
                    remoteId != current.remoteDeviceId || !Crypto.verifyConfirmationProof(
                        current.pairingKey!!,
                        current.id,
                        remoteId,
                        identityKey,
                        proof,
                    ) || !Crypto.verifySignature(identityKey, authTranscript(current), signature)
                ) return fail("Pairing authentication failed")
                current.remoteIdentityKey = identityKey
                current.remoteConfirmed = true
                completeIfConfirmed(current)
            }
            "pairing.cancel" -> cancel()
            "features.update" -> receiveFeatureState(current, message)
            "quiet_mode.update" -> receiveQuietModeState(current, message)
            "clipboard.update" -> receiveClipboard(current, message, rich = false)
            "clipboard.rich" -> receiveClipboard(current, message, rich = true)
            "clipboard.ack" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Delivered"
                    callback(ClipboardSendResult.DELIVERED)
                    android.util.Log.i("SYNQ", "PLUGIN clipboard acknowledged")
                }
            }
            "clipboard.rejected" -> {
                val messageId = message.messageId ?: return
                pendingClipboardSends.remove(messageId)?.let { callback ->
                    mutableClipboardStatus.value = "Clipboard is turned off on Mac"
                    callback(ClipboardSendResult.DISABLED)
                }
            }
            "notifications.dismiss" -> receiveNotificationDismiss(current, message)
            "notifications.action" -> receiveNotificationAction(current, message)
            "calls.request" -> receiveCallRequest(current, message)
            "contacts.request" -> receiveContactsRequest(current, message)
            "favourites.update" -> receiveFavouritesUpdate(current, message)
            "otp.ack" -> android.util.Log.i("SYNQ", "PLUGIN OTP acknowledged by Mac")
            "find.start" -> receiveFindCommand(current, message, start = true)
            "find.stop" -> receiveFindCommand(current, message, start = false)
            "find.started" -> receiveFindAcknowledgement(current, message, started = true)
            "find.stopped" -> receiveFindAcknowledgement(current, message, started = false)
            "ping.request" -> receivePing(current, message)
            "ping.ack" -> {
                if (session !== current || message.sessionId != current.id || mutableState.value !is PairingState.Connected) return
                val messageId = message.messageId ?: return
                if (pendingPingId == messageId) {
                    pendingPingId = null
                    mutablePingStatus.value = "Ping delivered"
                }
            }
            "battery.update" -> receiveBattery(current, message)
            "files.accept" -> message.transferId?.let { pendingFileAccepts.remove(it)?.complete(true) }
            "files.complete.ack" -> message.transferId?.let { pendingFileCompletions.remove(it)?.complete(true) }
            "files.offer" -> {
                if (settings.isEnabled(SYNQFeature.FILES, current.remoteDeviceId)) {
                    receiveFileOffer(current, message)
                } else {
                    current.send(Message(kind = "files.rejected", sessionId = current.id, transferId = message.transferId))
                    sendFeatureState()
                }
            }
            "files.rejected" -> message.transferId?.let { transferId ->
                pendingFileAccepts.remove(transferId)?.complete(false)
                outgoingFileJobs.remove(transferId)?.cancel()
                removeFileTransfer(transferId, "File transfer is turned off on Mac")
            }
            // Once an offer has been accepted, let that transfer finish even if the
            // setting changes. Disabling Files blocks the next offer instead.
            "files.chunk" -> receiveFileChunk(current, message)
            "files.complete" -> receiveFileComplete(current, message)
            "files.cancel" -> receiveFileCancel(message.transferId)
            "files.cancel.ack" -> message.transferId?.let { removeFileTransfer(it, "Transfer cancelled") }
            "sms.send" -> receiveSmsSend(current, message)
            "fs.list" -> receiveFsList(current, message)
            "fs.pull" -> receiveFsPull(current, message)
        }
    }

    private fun receiveNotificationDismiss(current: Session, message: Message) {
        if (!settings.isEnabled(SYNQFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted notification command"),
            message.ciphertext ?: return fail("Invalid encrypted notification command"),
        ) ?: return fail("Invalid encrypted notification command")
        val notificationId = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("notificationId")
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 512 }
            ?: return fail("Invalid notification command")
        SYNQNotificationListenerService.dismiss(notificationId)
        diagnostics.record("notification", "dismiss_requested")
    }

    private fun receiveCallRequest(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        if (!settings.isEnabled(SYNQFeature.CALLS, current.remoteDeviceId)) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            sendFeatureState()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastRemoteCallRequestAt != 0L && now - lastRemoteCallRequestAt < 3_000) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        val nonce = message.nonce ?: return fail("Invalid encrypted call request")
        val ciphertext = message.ciphertext ?: return fail("Invalid encrypted call request")
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            nonce,
            ciphertext,
        ) ?: return fail("Invalid encrypted call request")
        val number = runCatching {
            JSONObject(plaintext.toString(Charsets.UTF_8)).getString("number")
        }.getOrNull()?.let(::normalizedPhoneNumber)
        if (number == null) {
            current.send(Message(kind = "calls.rejected", sessionId = current.id, messageId = messageId))
            return
        }
        lastRemoteCallRequestAt = now
        val result = remoteCallRequest.execute(number, settings.state.value.directCallsEnabled)
        current.send(Message(kind = result.wireKind, sessionId = current.id, messageId = messageId))
        diagnostics.record("calls", "request", outcome = result.name.lowercase())
    }

    private fun receiveSmsSend(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        val nonce = message.nonce ?: return
        val ciphertext = message.ciphertext ?: return
        val plaintext = Crypto.decrypt(current.pairingKey ?: return, nonce, ciphertext) ?: return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val sendId = payload.optString("sendId", UUID.randomUUID().toString())
        val recipient = payload.optString("recipient").trim()
        val body = payload.optString("body")

        if (!processedSmsSendIds.add(sendId)) {
            return
        }

        fun sendSmsResult(status: String, error: String? = null) {
            val responsePayload = JSONObject()
                .put("sendId", sendId)
                .put("status", status)
            if (error != null) responsePayload.put("error", error)
            val enc = Crypto.encrypt(current.pairingKey!!, responsePayload.toString().toByteArray(Charsets.UTF_8))
            current.send(
                Message(
                    kind = "sms.result",
                    sessionId = current.id,
                    messageId = UUID.randomUUID().toString(),
                    nonce = enc.nonce,
                    ciphertext = enc.ciphertext
                )
            )
        }

        if (!settings.isEnabled(SYNQFeature.CALLS, current.remoteDeviceId)) {
            sendSmsResult("disabled", "Messaging/Calls disabled on Android")
            return
        }

        val normalizedNumber = normalizedPhoneNumber(recipient)
        if (normalizedNumber.isNullOrBlank() || body.isEmpty()) {
            sendSmsResult("invalid_recipient", "Invalid recipient phone number or empty message")
            return
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            sendSmsResult("permission_denied", "SMS permission not granted on Android. Please grant SMS permission in phone settings.")
            return
        }

        scope.launch(Dispatchers.Main) {
            try {
                val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    appContext.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                val parts = smsManager.divideMessage(body)
                if (parts.size > 1) {
                    smsManager.sendMultipartTextMessage(normalizedNumber, null, parts, null, null)
                } else {
                    smsManager.sendTextMessage(normalizedNumber, null, body, null, null)
                }
                android.util.Log.i("SYNQ", "SMS dispatched to telephony stack for $normalizedNumber (parts=${parts.size})")
                sendSmsResult("sent")
            } catch (e: Exception) {
                android.util.Log.e("SYNQ", "Failed to send SMS", e)
                sendSmsResult("failed", e.message ?: "Failed to send SMS")
            }
        }
    }

    fun sendContactsResponse(requestId: String? = null) {
        val current = session ?: return
        if (mutableState.value !is PairingState.Connected || current.pairingKey == null) return
        val messageId = UUID.randomUUID().toString()
        dispatchContacts(current, messageId)
    }

    private fun receiveContactsRequest(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        dispatchContacts(current, UUID.randomUUID().toString())
    }

    private fun dispatchContacts(current: Session, messageId: String) {
        if (!settings.state.value.contactsSyncEnabled) {
            current.send(Message(kind = "contacts.disabled", sessionId = current.id, messageId = messageId))
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            current.send(Message(kind = "contacts.permission_needed", sessionId = current.id, messageId = messageId))
            return
        }
        val contactsArray = JSONArray()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor = try {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )
        } catch (_: SecurityException) {
            current.send(Message(kind = "contacts.permission_needed", sessionId = current.id, messageId = messageId))
            return
        } catch (_: RuntimeException) {
            current.send(Message(kind = "contacts.unavailable", sessionId = current.id, messageId = messageId))
            return
        }
        if (cursor == null) {
            current.send(Message(kind = "contacts.unavailable", sessionId = current.id, messageId = messageId))
            return
        }
        try {
            cursor.use { c ->
                val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<String>()
                while (c.moveToNext() && contactsArray.length() < 300) {
                    val name = if (nameIndex >= 0) c.getString(nameIndex)?.trim().orEmpty() else ""
                    val number = if (numberIndex >= 0) c.getString(numberIndex)?.trim().orEmpty() else ""
                    val numberKey = number.filter(Char::isDigit)
                    if (name.isNotEmpty() && number.isNotEmpty() && seen.add("$name|$numberKey")) {
                        contactsArray.put(JSONObject().put("name", name).put("number", number))
                    }
                }
            }
        } catch (_: SecurityException) {
            current.send(Message(kind = "contacts.permission_needed", sessionId = current.id, messageId = messageId))
            return
        } catch (_: RuntimeException) {
            current.send(Message(kind = "contacts.unavailable", sessionId = current.id, messageId = messageId))
            return
        }
        val favouritesArray = JSONArray()
        settings.state.value.favouriteContacts.take(MAX_FAVOURITE_CONTACTS).forEach {
            favouritesArray.put(JSONObject().put("id", it.id).put("name", it.name).put("number", it.number))
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("contacts", contactsArray)
            .put("favourites", favouritesArray)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        val response = Message(
            kind = "contacts.response",
            sessionId = current.id,
            messageId = messageId,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
        )
        scope.launch {
            val sent = current.send(response)
            if (sent) {
                settings.recordContactsSync(contactsArray.length())
                diagnostics.record("contacts", "sync", outcome = "sent_${contactsArray.length()}")
                android.util.Log.i("SYNQ", "PLUGIN contacts response sent count=${contactsArray.length()}")
            } else {
                diagnostics.record("contacts", "sync", outcome = "send_failed")
            }
        }
    }

    private fun receiveFavouritesUpdate(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        val nonce = message.nonce ?: return
        val ciphertext = message.ciphertext ?: return
        val plaintext = Crypto.decrypt(current.pairingKey!!, nonce, ciphertext) ?: return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val array = payload.optJSONArray("favourites") ?: return
        val updated = runCatching {
            buildList {
                for (i in 0 until minOf(array.length(), MAX_FAVOURITE_CONTACTS)) {
                    val obj = array.getJSONObject(i)
                    add(
                        ContactItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name"),
                            number = obj.optString("number"),
                        )
                    )
                }
            }
        }.getOrNull() ?: return
        settings.setFavouriteContacts(updated)
    }

    fun sendFavouritesUpdate(list: List<ContactItem>) {
        val current = session ?: return
        if (mutableState.value !is PairingState.Connected || current.pairingKey == null) return
        val array = JSONArray()
        list.take(MAX_FAVOURITE_CONTACTS).forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("number", it.number))
        }
        val payload = JSONObject().put("version", 1).put("favourites", array).toString().toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        val message = Message(
            kind = "favourites.update",
            sessionId = current.id,
            messageId = UUID.randomUUID().toString(),
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
        )
        scope.launch {
            if (session !== current || mutableState.value !is PairingState.Connected) return@launch
            if (current.send(message)) {
                android.util.Log.i("SYNQ", "PLUGIN favourites update sent count=${list.take(MAX_FAVOURITE_CONTACTS).size}")
            }
        }
    }

    private fun receiveNotificationAction(current: Session, message: Message) {
        if (!settings.isEnabled(SYNQFeature.NOTIFICATIONS, current.remoteDeviceId)) {
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted notification action"),
            message.ciphertext ?: return fail("Invalid encrypted notification action"),
        ) ?: return fail("Invalid encrypted notification action")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid notification action")
        val notificationId = payload.optString("notificationId")
        val actionToken = payload.optString("actionToken")
        val replyText = payload.optString("replyText").takeIf { payload.has("replyText") }
        if (notificationId.isBlank() || notificationId.length > 512 ||
            !actionToken.matches(Regex("[0-9a-f]{64}")) ||
            (replyText?.length ?: 0) > 4_096) return fail("Invalid notification action")
        val performed = SYNQNotificationListenerService.perform(notificationId, actionToken, replyText)
        if (!performed) {
            android.util.Log.w("SYNQ", "PLUGIN notification action failed or rejected token=$actionToken")
            current.send(
                Message(
                    kind = "notifications.action_failed",
                    sessionId = current.id,
                    messageId = messageId,
                ),
            )
            diagnostics.record("notification", "action_failed")
            return
        }
        diagnostics.record("notification", if (replyText == null) "action_requested" else "reply_requested")
    }

    private fun receiveClipboard(current: Session, message: Message, rich: Boolean) {
        if (!settings.isEnabled(SYNQFeature.CLIPBOARD, current.remoteDeviceId)) {
            current.send(Message(kind = "clipboard.rejected", sessionId = current.id, messageId = message.messageId))
            sendFeatureState()
            return
        }
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return fail("Invalid encrypted clipboard message")
        val (clip, rawText) = if (rich) {
            val content = RichClipboardContent.decode(plaintext)
                ?: return fail("Invalid rich clipboard message")
            ClipData.newHtmlText("SYNQ", content.text, content.html) to content.text
        } else {
            val text = plaintext.toString(Charsets.UTF_8)
            if (!clipboardTextFits(text)) return fail("Invalid clipboard message")
            ClipData.newPlainText("SYNQ", text) to text
        }
        appContext.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        mutableClipboardStatus.value = "Clipboard received from Mac"
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "Clipboard received from Mac", android.widget.Toast.LENGTH_SHORT).show()
        }
        val detectedLink = SmartLinkDetector.detect(rawText)
        if (detectedLink != null && isFeatureAvailable(SYNQFeature.LINKS) && !settings.state.value.quietModeEnabled) {
            quickActions.handleReceivedClipboardLink(detectedLink)
        }
        diagnostics.record("clipboard", if (rich) "rich_received" else "text_received")
        android.util.Log.i("SYNQ", "PLUGIN clipboard received")
        current.send(Message(kind = "clipboard.ack", sessionId = current.id, messageId = messageId))
    }

    private fun receiveFindAcknowledgement(current: Session, message: Message, started: Boolean) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted find-device acknowledgement"),
            message.ciphertext ?: return fail("Invalid encrypted find-device acknowledgement"),
        ) ?: return fail("Invalid encrypted find-device acknowledgement")
        if (runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)).getString("alertId") }.getOrNull() != "active") {
            return fail("Invalid find-device acknowledgement")
        }
        mutableMacRinging.value = started
    }

    private fun receiveFileOffer(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file offer"),
            message.ciphertext ?: return fail("Invalid encrypted file offer"),
        ) ?: return fail("Invalid encrypted file offer")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid file offer")
        val transferId = payload.optString("transferId")
        val name = payload.optString("name")
        val mimeType = payload.optString("mimeType", "application/octet-stream")
        val size = payload.optLong("size", -1)
        val hash = payload.optString("sha256")
        if (runCatching { UUID.fromString(transferId) }.isFailure || name.isBlank() || size !in 0..MAX_FILE_SIZE ||
            runCatching { Base64.decode(hash, Base64.DEFAULT).size == 32 }.getOrDefault(false).not() ||
            incomingFiles.containsKey(transferId)
        ) return fail("Invalid file offer")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return fail("Receiving files requires Android 10 or newer")
        }
        val freeBytes = runCatching { Environment.getExternalStorageDirectory().freeSpace }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes in 1 until size) {
            current.send(Message(kind = "files.rejected", sessionId = current.id, transferId = transferId))
            updateFileTransfer(transferId, name, "Insufficient storage on phone", false)
            return
        }
        val transfer = runCatching {
            IncomingFileTransfer(appContext, transferId, name, mimeType, size, hash)
        }.getOrElse { return fail("Could not create file in Downloads") }
        incomingFiles[transferId] = transfer
        updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: ${transfer.progress.status(0, force = true)}", true)
        current.send(Message(kind = "files.accept", sessionId = current.id, transferId = transferId))
        android.util.Log.i("SYNQ", "PLUGIN file accepted name=${transfer.displayName} size=$size")
    }

    private fun receiveFileChunk(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val transferId = message.transferId ?: return
        val transfer = incomingFiles[transferId] ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val chunk = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file chunk"),
            message.ciphertext ?: return fail("Invalid encrypted file chunk"),
        ) ?: return fail("Invalid encrypted file chunk")
        if (!runCatching { transfer.append(chunk, message.sequence ?: -1) }.isSuccess) {
            incomingFiles.remove(transfer.transferId)?.cancel()
            return fail("Invalid file data")
        }
        transfer.progress.status(transfer.receivedSize)?.let {
            updateFileTransfer(transferId, transfer.displayName, "Receiving ${transfer.displayName}: $it", true)
        }
        current.send(
            Message(
                kind = "files.chunk.ack",
                sessionId = current.id,
                transferId = transferId,
                sequence = message.sequence,
            ),
        )
    }

    private fun receiveFileComplete(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted file completion"),
            message.ciphertext ?: return fail("Invalid encrypted file completion"),
        ) ?: return fail("Invalid encrypted file completion")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid file completion")
        val transferId = payload.optString("transferId")
        val transfer = incomingFiles.remove(transferId) ?: run {
            if (transferId in cancelledTransferIds) return
            return fail("Unknown file transfer")
        }
        val savedUri = runCatching { transfer.finish(payload.optString("sha256")) }.getOrNull()
        if (savedUri == null) {
            transfer.cancel()
            mutableFileTransferStatus.value = "File verification failed"
            return fail("File verification failed")
        }
        updateFileTransfer(transferId, transfer.displayName, "${transfer.displayName} saved to Download/SYNQ", false)
        ReceivedFileNotifier.show(appContext, transfer.displayName, transfer.mimeType, savedUri)
        current.send(Message(kind = "files.complete.ack", sessionId = current.id, transferId = transferId))
        android.util.Log.i("SYNQ", "PLUGIN file received name=${transfer.displayName}")
    }

    private fun receiveFileCancel(transferId: String?) {
        if (transferId == null) return
        markTransferCancelled(transferId)
        incomingFiles.remove(transferId)?.cancel()
        outgoingFileJobs.remove(transferId)?.cancel()
        pendingFileAccepts.remove(transferId)?.complete(false)
        pendingFileCompletions.remove(transferId)?.complete(false)
        removeFileTransfer(transferId, "Transfer cancelled by Mac")
        session?.send(Message(kind = "files.cancel.ack", sessionId = session?.id ?: return, transferId = transferId))
        android.util.Log.i("SYNQ", "PLUGIN file cancellation received transfer=${transferId.take(8)}")
    }

    private fun markTransferCancelled(transferId: String) {
        cancelledTransferIds += transferId
        while (cancelledTransferIds.size > 64) cancelledTransferIds.firstOrNull()?.let(cancelledTransferIds::remove)
    }

    private fun updateFileTransfer(id: String, name: String, status: String, active: Boolean) {
        mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
            val percent = Regex("(\\d{1,3})%").find(status)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val previous = get(id)
            put(id, FileTransferState(
                id = id,
                name = name,
                status = status,
                active = active,
                progressPercent = percent,
                startedAtMillis = previous?.startedAtMillis ?: System.currentTimeMillis(),
                retryable = !active && (outgoingFileSources.containsKey(id) || outgoingLocalFileSources.containsKey(id)),
            ))
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun removeFileTransfer(id: String, status: String) {
        mutableFileTransfers.value[id]?.let { previous ->
            mutableFileTransfers.value = mutableFileTransfers.value.toMutableMap().apply {
                put(id, previous.copy(
                    status = status,
                    active = false,
                    progressPercent = null,
                    retryable = outgoingFileSources.containsKey(id) || outgoingLocalFileSources.containsKey(id),
                ))
            }
        }
        pruneTransferHistory()
        refreshFileTransferSummary(status)
    }

    private fun pruneTransferHistory() {
        val active = mutableFileTransfers.value.values.filter(FileTransferState::active)
        val history = mutableFileTransfers.value.values.filterNot(FileTransferState::active)
            .sortedByDescending(FileTransferState::startedAtMillis)
            .take(MAX_TRANSFER_HISTORY)
        mutableFileTransfers.value = (active + history).associateBy(FileTransferState::id)
    }

    private fun refreshFileTransferSummary(fallback: String? = null) {
        val activeTransfers = mutableFileTransfers.value.values.filter { it.active }
        mutableFileTransferActive.value = activeTransfers.isNotEmpty()
        mutableFileTransferStatus.value = activeTransfers.firstOrNull()?.status ?: fallback
    }

    private fun resolveAuthorizedBrowsingPath(relativePath: String): File? {
        if (!settings.state.value.fileBrowsingEnabled) return null
        val currentSession = session ?: return null
        if (!settings.isEnabled(SYNQFeature.FILES, currentSession.remoteDeviceId)) return null

        val clean = relativePath.trim().removePrefix("/").removePrefix("\\")
        val components = clean.split("/", "\\").filter { it.isNotEmpty() }
        if (components.any { it == ".." || it == "." }) return null

        val customPath = settings.state.value.customBrowsingPath
        val rootDir = if (!customPath.isNullOrBlank()) {
            val customFile = File(customPath).canonicalFile
            if (customFile.exists() && customFile.isDirectory && customFile.canRead()) {
                customFile
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
            }
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        }
        if (!rootDir.exists()) rootDir.mkdirs()

        val target = if (components.isEmpty()) rootDir else File(rootDir, components.joinToString(File.separator)).canonicalFile
        if (!target.path.startsWith(rootDir.path)) return null
        return target
    }

    private fun receiveFsList(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        val nonce = message.nonce ?: return
        val ciphertext = message.ciphertext ?: return
        val plaintext = Crypto.decrypt(current.pairingKey ?: return, nonce, ciphertext) ?: return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val requestedPath = payload.optString("path", "")

        fun sendFsError(err: String) {
            val errObj = JSONObject().put("path", requestedPath).put("error", err)
            val enc = Crypto.encrypt(current.pairingKey!!, errObj.toString().toByteArray(Charsets.UTF_8))
            current.send(Message(kind = "fs.error", sessionId = current.id, messageId = UUID.randomUUID().toString(), nonce = enc.nonce, ciphertext = enc.ciphertext))
        }

        val targetDir = resolveAuthorizedBrowsingPath(requestedPath)
        if (targetDir == null) {
            sendFsError("Access denied: File browsing is disabled or path is outside authorized storage")
            return
        }

        if (!targetDir.exists() || !targetDir.isDirectory) {
            sendFsError("Folder not found")
            return
        }

        val rootDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val entriesArray = JSONArray()
        val files = targetDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

        for (file in files) {
            if (file.name.startsWith(".") || file.name.endsWith(".bridgey-part")) continue
            val relPath = file.canonicalPath.removePrefix(rootDir.canonicalPath).removePrefix(File.separator)
            val mimeType = if (file.isDirectory) "inode/directory" else (MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream")
            val item = JSONObject()
                .put("name", file.name)
                .put("path", relPath)
                .put("isDirectory", file.isDirectory)
                .put("size", if (file.isDirectory) 0L else file.length())
                .put("lastModified", file.lastModified())
                .put("mimeType", mimeType)
            entriesArray.put(item)
        }

        val respObj = JSONObject()
            .put("path", requestedPath)
            .put("entries", entriesArray)
        val enc = Crypto.encrypt(current.pairingKey!!, respObj.toString().toByteArray(Charsets.UTF_8))
        current.send(
            Message(
                kind = "fs.list.response",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = enc.nonce,
                ciphertext = enc.ciphertext
            )
        )
    }

    private fun receiveFsPull(current: Session, message: Message) {
        val messageId = message.messageId ?: return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id || !current.acceptMessageId(messageId)) return
        val nonce = message.nonce ?: return
        val ciphertext = message.ciphertext ?: return
        val plaintext = Crypto.decrypt(current.pairingKey ?: return, nonce, ciphertext) ?: return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val requestedPath = payload.optString("path", "")

        fun sendFsError(err: String) {
            val errObj = JSONObject().put("path", requestedPath).put("error", err)
            val enc = Crypto.encrypt(current.pairingKey!!, errObj.toString().toByteArray(Charsets.UTF_8))
            current.send(Message(kind = "fs.error", sessionId = current.id, messageId = UUID.randomUUID().toString(), nonce = enc.nonce, ciphertext = enc.ciphertext))
        }

        val targetFile = resolveAuthorizedBrowsingPath(requestedPath)
        if (targetFile == null || !targetFile.exists() || !targetFile.isFile || !targetFile.canRead()) {
            sendFsError("File not found or cannot be read")
            return
        }

        sendLocalFile(targetFile)
    }

    private fun authenticateOrPrompt(current: Session) {
        if (trust.identityKey(current.remoteDeviceId) != null) {
            confirm(current)
        } else {
            mutableState.value = PairingState.Verification(current.peerName, current.code!!)
        }
    }

    private fun confirm(current: Session) {
        current.localConfirmed = true
        val identityKey = identity.publicKey()
        current.send(
            Message(
                kind = "pairing.confirm",
                sessionId = current.id,
                deviceId = localDeviceId,
                identityKey = identityKey,
                proof = Crypto.confirmationProof(current.pairingKey!!, current.id, localDeviceId, identityKey),
                signature = identity.sign(authTranscript(current)),
            ),
        )
        completeIfConfirmed(current)
    }

    private fun authTranscript(current: Session): ByteArray {
        val fields = if (current.initiatedLocally) {
            listOf(localDeviceId, current.remoteDeviceId, current.localEphemeralKey, current.remoteEphemeralKey)
        } else {
            listOf(current.remoteDeviceId, localDeviceId, current.remoteEphemeralKey, current.localEphemeralKey)
        }
        return (listOf("bridgey-auth-v1", current.id) + fields).joinToString("\u0000").toByteArray()
    }

    private fun completeIfConfirmed(current: Session) {
        if (current.localConfirmed && current.remoteConfirmed && mutableState.value !is PairingState.Connected) {
            automaticReconnectSuppressed = false
            trust.save(current.remoteDeviceId, current.peerName, current.remoteIdentityKey!!)
            mutableState.value = PairingState.Connected(current.remoteDeviceId, current.peerName)
            diagnostics.record("pairing", "connected")
            sendFeatureState()
            // Favourites are explicitly selected data and can be synced even
            // when full address-book sync is turned off or not yet permitted.
            // Sending them here also restores them on reconnect after an app
            // restart, instead of waiting for the user to edit a favourite.
            sendFavouritesUpdate(settings.state.value.favouriteContacts)
            sendQuietModeState()
            android.util.Log.i("SYNQ", "PAIRING verified peer=${current.peerName}")
        }
    }

    private fun sendQuietModeState() {
        val current = session ?: return
        if (mutableState.value !is PairingState.Connected || current.pairingKey == null) return
        val payload = JSONObject()
            .put("version", 1)
            .put("enabled", settings.state.value.quietModeEnabled)
            .put("updatedAt", settings.state.value.quietModeUpdatedAt)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        current.send(
            Message(
                kind = "quiet_mode.update",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveQuietModeState(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (payload.optInt("version") != 1) return
        val enabled = payload.optBoolean("enabled", false)
        val updatedAt = payload.optLong("updatedAt", 0L)
        if (updatedAt > settings.state.value.quietModeUpdatedAt) {
            settings.setQuietModeEnabled(enabled, updatedAt)
        }
    }

    private fun sendFeatureState() {
        val current = session ?: return
        if (mutableState.value !is PairingState.Connected || current.pairingKey == null) return
        val featureValues = JSONObject()
        SYNQFeature.entries.forEach { feature ->
            featureValues.put(feature.key, settings.isEnabled(feature, current.remoteDeviceId))
        }
        val payload = JSONObject()
            .put("version", 1)
            .put("features", featureValues)
            .toString()
            .toByteArray()
        val encrypted = Crypto.encrypt(current.pairingKey!!, payload)
        current.send(
            Message(
                kind = "features.update",
                sessionId = current.id,
                messageId = UUID.randomUUID().toString(),
                nonce = encrypted.nonce,
                ciphertext = encrypted.ciphertext,
            ),
        )
    }

    private fun receiveFeatureState(current: Session, message: Message) {
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return,
            message.ciphertext ?: return,
        ) ?: return fail("Invalid encrypted feature state")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid feature state")
        val values = payload.optJSONObject("features") ?: return fail("Invalid feature state")
        if (payload.optInt("version") != 1) return
        val received = SYNQFeature.entries.associateWith { feature ->
            if (!values.has(feature.key)) {
                if (!featureEnabledByLegacyPeer(feature)) return@associateWith false
                return fail("Invalid feature state")
            }
            if (values.opt(feature.key) !is Boolean) return fail("Invalid feature state")
            values.getBoolean(feature.key)
        }
        mutableRemoteFeatures.value = received
        quickActions.policyChanged()
        if (received[SYNQFeature.CLIPBOARD] == false) mutableClipboardStatus.value = null
        if (received[SYNQFeature.BATTERY] == false) mutableRemoteBattery.value = null
        if (received[SYNQFeature.PING] == false) clearPingStatus()
        if (received[SYNQFeature.FIND_DEVICE] == false) {
            stopPhoneRinging()
            mutableMacRinging.value = false
        }
    }

    private fun receiveBattery(current: Session, message: Message) {
        if (!featureEnabled(SYNQFeature.BATTERY, current)) return
        if (mutableState.value !is PairingState.Connected || message.sessionId != current.id) return
        val messageId = message.messageId ?: return
        if (!current.acceptMessageId(messageId)) return
        val plaintext = Crypto.decrypt(
            current.pairingKey!!,
            message.nonce ?: return fail("Invalid encrypted battery status"),
            message.ciphertext ?: return fail("Invalid encrypted battery status"),
        ) ?: return fail("Invalid encrypted battery status")
        val payload = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return fail("Invalid battery status")
        val level = payload.optInt("level", -1)
        if (level !in 0..100 || payload.opt("isCharging") !is Boolean) return fail("Invalid battery status")
        mutableRemoteBattery.value = RemoteBatteryStatus(level, payload.getBoolean("isCharging"))
        android.util.Log.i("SYNQ", "PLUGIN battery received level=$level")
    }

    private fun fail(message: String) {
        session?.close()
        session = null
        cancelIncomingFiles()
        mutableRemoteBattery.value = null
        clearPingStatus()
        quickActions.reset()
        mutableRemoteFeatures.value = defaultFeatureState()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        mutableState.value = PairingState.Failed(message)
        diagnostics.record("protocol", "session_failed", "rejected")
    }

    private fun cancelIncomingFiles() {
        val hadActiveTransfers = mutableFileTransfers.value.values.any(FileTransferState::active)
        incomingFiles.values.forEach(IncomingFileTransfer::cancel)
        incomingFiles.clear()
        outgoingFileJobs.values.forEach(Job::cancel)
        outgoingFileJobs.clear()
        pendingFileAccepts.values.forEach { it.complete(false) }
        pendingFileAccepts.clear()
        pendingFileCompletions.values.forEach { it.complete(false) }
        pendingFileCompletions.clear()
        mutableFileTransfers.value = recoverInterruptedTransfers(mutableFileTransfers.value)
        refreshFileTransferSummary("File transfer interrupted")
        if (hadActiveTransfers) diagnostics.record("transfer", "interrupted", "retry_available")
        if (mutableFileTransferStatus.value?.startsWith("Receiving ") == true) {
            mutableFileTransferStatus.value = "File transfer interrupted"
        }
    }

    private companion object {
        fun defaultFeatureState(): Map<SYNQFeature, Boolean> = SYNQFeature.entries.associateWith(::featureEnabledByLegacyPeer)
        const val FILE_CHUNK_SIZE = 24 * 1024
        const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024
        const val MAX_NOTIFICATION_ICON_BASE64_LENGTH = 28 * 1024
        const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
    }

    private class Session(private val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        var id = ""
        var peerName = "Device"
        var remoteDeviceId = ""
        var remoteIdentityKey: String? = null
        var initiatedLocally = false
        var localEphemeralKey = ""
        var remoteEphemeralKey = ""
        var keyPair: KeyPair? = null
        var code: String? = null
        var pairingKey: ByteArray? = null
        var localConfirmed = false
        var remoteConfirmed = false
        @Volatile var lastReceivedAtMillis = SystemClock.elapsedRealtime()
        @Volatile var heartbeatSupported = false
        private val seenMessageIds = LinkedHashSet<String>()

        @Synchronized fun send(message: Message): Boolean {
            val result = runCatching {
                writer.write(message.encode())
                writer.newLine()
                writer.flush()
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("SYNQ", "TRANSPORT send failed kind=${message.kind}", it)
            }
            return result.isSuccess
        }

        fun close() = runCatching { socket.close() }.let { Unit }

        val quickSequence = QuickRequestSequence()

        @Synchronized fun acceptMessageId(id: String): Boolean {
            if (!seenMessageIds.add(id)) return false
            while (seenMessageIds.size > 256) seenMessageIds.remove(seenMessageIds.first())
            return true
        }
    }
}

private class TransferProgress(private val totalBytes: Long) {
    private var lastUpdateAt = SystemClock.elapsedRealtime()
    private var lastBytes = 0L
    private var smoothedBytesPerSecond = 0.0

    fun status(transferred: Long, force: Boolean = false): String? {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastUpdateAt
        if (!force && elapsedMs < 250 && transferred < totalBytes) return null
        if (elapsedMs > 0) {
            val sample = (transferred - lastBytes).coerceAtLeast(0) * 1000.0 / elapsedMs
            smoothedBytesPerSecond = if (smoothedBytesPerSecond == 0.0) sample else smoothedBytesPerSecond * 0.7 + sample * 0.3
        }
        lastUpdateAt = now
        lastBytes = transferred
        val percent = if (totalBytes == 0L) 100 else ((transferred * 100) / totalBytes).coerceIn(0, 100)
        val remaining = (totalBytes - transferred).coerceAtLeast(0)
        val eta = if (smoothedBytesPerSecond >= 1 && remaining > 0) formatDuration((remaining / smoothedBytesPerSecond).toLong()) else "calculating…"
        return "$percent% · ${formatBytes(transferred)} / ${formatBytes(totalBytes)} · ${formatBytes(smoothedBytesPerSecond.toLong())}/s · $eta left"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 10) "%.0f %s".format(java.util.Locale.US, value, units[unit])
        else "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 1 -> "<1 sec"
        seconds < 60 -> "$seconds sec"
        seconds < 3600 -> "${seconds / 60} min ${seconds % 60} sec"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private class IncomingFileTransfer(
    private val context: Context,
    val transferId: String,
    offeredName: String,
    val mimeType: String,
    val expectedSize: Long,
    private val expectedHash: String,
) {
    val displayName = sanitize(offeredName)
    val progress = TransferProgress(expectedSize)
    private val resolver = context.contentResolver
    private val uri: Uri
    private val output: OutputStream
    private val digest = MessageDigest.getInstance("SHA-256")
    var receivedSize = 0L
        private set
    private var nextSequence = 0L

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.take(255))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SYNQ")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not allocate download")
        output = resolver.openOutputStream(uri, "w") ?: run {
            resolver.delete(uri, null, null)
            error("Could not open download")
        }
    }

    @Synchronized fun append(bytes: ByteArray, sequence: Long) {
        check(sequence == nextSequence)
        check(bytes.size <= 24 * 1024)
        check(receivedSize + bytes.size <= expectedSize)
        output.write(bytes)
        digest.update(bytes)
        receivedSize += bytes.size
        nextSequence++
    }

    @Synchronized fun finish(completionHash: String): Uri? {
        output.flush()
        output.close()
        val actual = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        if (receivedSize != expectedSize || completionHash != expectedHash || actual != expectedHash) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    @Synchronized fun cancel() {
        runCatching { output.close() }
        resolver.delete(uri, null, null)
    }

    private fun sanitize(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:]"), "_")
            .take(255)
        return leaf.ifBlank { "file" }
    }
}

private data class Message(
    val kind: String,
    val sessionId: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val publicKey: String? = null,
    val identityKey: String? = null,
    val proof: String? = null,
    val signature: String? = null,
    val messageId: String? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val transferId: String? = null,
    val sequence: Long? = null,
) {
    fun encode(): String = JSONObject().apply {
        put("kind", kind)
        put("sessionId", sessionId)
        deviceId?.let { put("deviceId", it) }
        deviceName?.let { put("deviceName", it) }
        publicKey?.let { put("publicKey", it) }
        identityKey?.let { put("identityKey", it) }
        proof?.let { put("proof", it) }
        signature?.let { put("signature", it) }
        messageId?.let { put("messageId", it) }
        nonce?.let { put("nonce", it) }
        ciphertext?.let { put("ciphertext", it) }
        transferId?.let { put("transferId", it) }
        sequence?.let { put("sequence", it) }
    }.toString()

    companion object {
        fun decode(value: String): Message = JSONObject(value).let {
            Message(
                kind = it.getString("kind"),
                sessionId = it.getString("sessionId"),
                deviceId = it.optString("deviceId").takeIf(String::isNotEmpty),
                deviceName = it.optString("deviceName").takeIf(String::isNotEmpty),
                publicKey = it.optString("publicKey").takeIf(String::isNotEmpty),
                identityKey = it.optString("identityKey").takeIf(String::isNotEmpty),
                proof = it.optString("proof").takeIf(String::isNotEmpty),
                signature = it.optString("signature").takeIf(String::isNotEmpty),
                messageId = it.optString("messageId").takeIf(String::isNotEmpty),
                nonce = it.optString("nonce").takeIf(String::isNotEmpty),
                ciphertext = it.optString("ciphertext").takeIf(String::isNotEmpty),
                transferId = it.optString("transferId").takeIf(String::isNotEmpty),
                sequence = if (it.has("sequence")) it.getLong("sequence") else null,
            )
        }
    }
}

internal object Crypto {
    data class PairingMaterial(val code: String, val key: ByteArray)
    data class EncryptedPayload(val nonce: String, val ciphertext: String)

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun encodePublicKey(pair: KeyPair): String {
        val public = pair.public as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return java.util.Base64.getEncoder().encodeToString(raw)
    }

    fun pairingMaterial(pair: KeyPair, remoteBase64: String, sessionId: String): PairingMaterial {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(decodePublicKey(java.util.Base64.getDecoder().decode(remoteBase64)), true)
        val sharedSecret = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(sharedSecret)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val output = expand.doFinal("bridgey-pairing-v1".toByteArray() + byteArrayOf(1))
        val number = ((output[0].toLong() and 0xff) shl 24) or
            ((output[1].toLong() and 0xff) shl 16) or
            ((output[2].toLong() and 0xff) shl 8) or (output[3].toLong() and 0xff)
        return PairingMaterial((number % 1_000_000).toString().padStart(6, '0'), output)
    }

    fun confirmationProof(key: ByteArray, sessionId: String, deviceId: String, identityKey: String): String {
        val data = "bridgey-confirm-v1\u0000$sessionId\u0000$deviceId\u0000$identityKey".toByteArray()
        val proof = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)
        return java.util.Base64.getEncoder().encodeToString(proof)
    }

    fun verifyConfirmationProof(
        key: ByteArray,
        sessionId: String,
        deviceId: String,
        identityKey: String,
        proof: String,
    ): Boolean = runCatching {
        MessageDigest.isEqual(
            java.util.Base64.getDecoder().decode(proof),
            java.util.Base64.getDecoder().decode(confirmationProof(key, sessionId, deviceId, identityKey)),
        )
    }.getOrDefault(false)

    fun encrypt(key: ByteArray, plaintext: ByteArray): EncryptedPayload {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return EncryptedPayload(
            java.util.Base64.getEncoder().encodeToString(nonce),
            java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
        )
    }

    fun decrypt(key: ByteArray, nonce: String, ciphertext: String): ByteArray? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, java.util.Base64.getDecoder().decode(nonce)),
            )
        }.doFinal(java.util.Base64.getDecoder().decode(ciphertext))
    }.getOrNull()

    fun verifySignature(identityKey: String, data: ByteArray, signature: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").apply {
            initVerify(decodePublicKey(java.util.Base64.getDecoder().decode(identityKey)))
            update(data)
        }.verify(java.util.Base64.getDecoder().decode(signature))
    }.getOrDefault(false)

    private fun decodePublicKey(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte())
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

private class AndroidIdentity(context: Context) {
    private val alias = "synq.identity.p256.v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    fun publicKey(): String {
        val public = store.getCertificate(alias).publicKey as java.security.interfaces.ECPublicKey
        val raw = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun sign(data: ByteArray): String {
        val privateKey = store.getKey(alias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

private class AndroidTrustRegistry(context: Context) {
    private val preferences = context.getSharedPreferences("synq.trust", Context.MODE_PRIVATE)
    private val mutableIds = MutableStateFlow(loadIds())
    val trustedDeviceIds: StateFlow<Set<String>> = mutableIds.asStateFlow()
    private val mutableDevices = MutableStateFlow(loadDevices())
    val trustedDevices: StateFlow<List<TrustedDevice>> = mutableDevices.asStateFlow()

    fun save(deviceId: String, name: String, identityKey: String) {
        preferences.edit()
            .putString("peer.$deviceId.name", name)
            .putString("peer.$deviceId.identityKey", identityKey)
            .apply()
        mutableIds.value = loadIds()
        mutableDevices.value = loadDevices()
    }

    fun remove(deviceId: String) {
        preferences.edit()
            .remove("peer.$deviceId.name")
            .remove("peer.$deviceId.identityKey")
            .apply()
        mutableIds.value = loadIds()
        mutableDevices.value = loadDevices()
    }

    fun identityKey(deviceId: String): String? = preferences.getString("peer.$deviceId.identityKey", null)

    private fun loadIds(): Set<String> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith("peer.") && it.endsWith(".identityKey") }
        .map { it.removePrefix("peer.").removeSuffix(".identityKey") }
        .toSet()

    private fun loadDevices(): List<TrustedDevice> = loadIds().map { id ->
        TrustedDevice(id, preferences.getString("peer.$id.name", null) ?: "Unknown device")
    }.sortedBy { it.name.lowercase() }
}
