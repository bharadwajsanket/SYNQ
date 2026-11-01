package dev.synq.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import dev.synq.core.discovery.DiscoveredPeer
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

enum class TransferPhase {
    DISCOVERING,
    REQUEST_RECEIVED,
    AWAITING_DECISION,
    CONNECTING,
    TRANSFERRING,
    VERIFYING,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED
}

sealed class AndroidShareState {
    object Idle : AndroidShareState()

    object Discovering : AndroidShareState()

    data class IncomingOffer(
        val transferId: String,
        val senderDeviceId: String,
        val senderDeviceName: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val sha256: String,
        val phase: TransferPhase = TransferPhase.AWAITING_DECISION,
    ) : AndroidShareState()

    data class Transferring(
        val transferId: String,
        val peerName: String,
        val fileName: String,
        val isSending: Boolean,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val status: String,
        val phase: TransferPhase = TransferPhase.TRANSFERRING,
        val isCompleted: Boolean = false,
        val isError: Boolean = false,
    ) : AndroidShareState()
}

class AndroidShareManager(
    private val context: Context,
    private val localDeviceId: String,
    private var localDeviceName: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AndroidShareState>(AndroidShareState.Idle)
    val state: StateFlow<AndroidShareState> = mutableState.asStateFlow()

    private var pendingIncomingDecision: CompletableDeferred<Boolean>? = null
    private var activeJob: Job? = null
    private var currentActiveSocket: Socket? = null

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val TAG = "SYNQShare"
        private const val CHUNK_SIZE = 64 * 1024
        private const val MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024 // 10 GB
        const val INCOMING_SHARE_CHANNEL_ID = "synq_link_share_offer"
        const val NOTIFICATION_SHARE_OFFER_ID = 52_458
        const val ACTION_ACCEPT_SHARE = "dev.synq.action.ACCEPT_SHARE"
        const val ACTION_DECLINE_SHARE = "dev.synq.action.DECLINE_SHARE"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                INCOMING_SHARE_CHANNEL_ID,
                "SYNQ Link File Sharing",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming transfer requests and progress between phones"
                setShowBadge(true)
                enableVibration(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun updateDeviceName(name: String) {
        localDeviceName = name.trim().take(64).ifBlank { "Android device" }
    }

    fun acceptIncomingTransfer() {
        dismissIncomingNotification()
        pendingIncomingDecision?.complete(true)
    }

    fun rejectIncomingTransfer() {
        dismissIncomingNotification()
        pendingIncomingDecision?.complete(false)
    }

    fun cancelTransfer() {
        dismissIncomingNotification()
        activeJob?.cancel()
        activeJob = null
        runCatching { currentActiveSocket?.close() }
        currentActiveSocket = null

        val current = mutableState.value
        if (current is AndroidShareState.Transferring) {
            mutableState.value = current.copy(
                status = "Transfer cancelled",
                phase = TransferPhase.CANCELLED,
                isCompleted = false,
                isError = true
            )
        } else if (current is AndroidShareState.IncomingOffer) {
            rejectIncomingTransfer()
            mutableState.value = AndroidShareState.Idle
        }
    }

    fun clearTransferState() {
        dismissIncomingNotification()
        if (mutableState.value is AndroidShareState.Transferring) {
            val current = mutableState.value as AndroidShareState.Transferring
            if (current.isCompleted || current.isError) {
                mutableState.value = AndroidShareState.Idle
            }
        } else if (mutableState.value is AndroidShareState.IncomingOffer) {
            rejectIncomingTransfer()
            mutableState.value = AndroidShareState.Idle
        }
    }

    fun sendFiles(peer: DiscoveredPeer, uris: List<Uri>, resolver: ContentResolver) {
        val host = peer.host
        val port = peer.port ?: 42_458
        if (host == null) {
            mutableState.value = AndroidShareState.Transferring(
                transferId = UUID.randomUUID().toString(),
                peerName = peer.deviceNameHint,
                fileName = "Files",
                isSending = true,
                bytesTransferred = 0,
                totalBytes = 0,
                status = "Recipient host address unknown",
                phase = TransferPhase.FAILED,
                isError = true,
            )
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            for (uri in uris) {
                ensureActive()
                val metadata = runCatching {
                    var name = "file"
                    var size = -1L
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                        }
                    }
                    Triple(sanitizeFilename(name), resolver.getType(uri) ?: "application/octet-stream", size)
                }.getOrNull()

                if (metadata == null || metadata.third < 0) {
                    mutableState.value = AndroidShareState.Transferring(
                        transferId = UUID.randomUUID().toString(),
                        peerName = peer.deviceNameHint,
                        fileName = metadata?.first ?: "file",
                        isSending = true,
                        bytesTransferred = 0,
                        totalBytes = 0,
                        status = "Could not read selected file",
                        phase = TransferPhase.FAILED,
                        isError = true,
                    )
                    continue
                }

                sendFileStream(
                    host = host,
                    port = port,
                    peerName = peer.deviceNameHint,
                    name = metadata.first,
                    mimeType = metadata.second,
                    size = metadata.third,
                    openInputStream = { resolver.openInputStream(uri) },
                )
            }
        }
    }

    fun sendLocalFile(peer: DiscoveredPeer, file: File) {
        val host = peer.host
        val port = peer.port ?: 42_458
        if (host == null || !file.exists() || !file.canRead()) {
            mutableState.value = AndroidShareState.Transferring(
                transferId = UUID.randomUUID().toString(),
                peerName = peer.deviceNameHint,
                fileName = file.name,
                isSending = true,
                bytesTransferred = 0,
                totalBytes = file.length(),
                status = if (host == null) "Recipient address unknown" else "File cannot be read",
                phase = TransferPhase.FAILED,
                isError = true,
            )
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            sendFileStream(
                host = host,
                port = port,
                peerName = peer.deviceNameHint,
                name = sanitizeFilename(file.name),
                mimeType = mimeType,
                size = file.length(),
                openInputStream = { FileInputStream(file) },
            )
        }
    }

    private suspend fun sendFileStream(
        host: String,
        port: Int,
        peerName: String,
        name: String,
        mimeType: String,
        size: Long,
        openInputStream: () -> InputStream?,
    ) {
        val transferId = UUID.randomUUID().toString()
        mutableState.value = AndroidShareState.Transferring(
            transferId = transferId,
            peerName = peerName,
            fileName = name,
            isSending = true,
            bytesTransferred = 0,
            totalBytes = size,
            status = "Connecting to $peerName…",
            phase = TransferPhase.CONNECTING,
        )

        // 1. Calculate file SHA-256 digest
        val digest = MessageDigest.getInstance("SHA-256")
        val sha256 = runCatching {
            openInputStream()?.use { input ->
                val buf = ByteArray(CHUNK_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                }
            } ?: error("Input stream unavailable")
            Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        }.getOrNull()

        if (sha256 == null) {
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = peerName,
                fileName = name,
                isSending = true,
                bytesTransferred = 0,
                totalBytes = size,
                status = "Failed to calculate file checksum",
                phase = TransferPhase.FAILED,
                isError = true,
            )
            return
        }

        // 2. Open temporary socket
        val socket = Socket()
        currentActiveSocket = socket
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 256 * 1024
            socket.receiveBufferSize = 256 * 1024
            socket.soTimeout = 12_000
            socket.connect(InetSocketAddress(host, port), 6_000)

            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

            // Ephemeral key pair for this session
            val senderKeyPair = Crypto.generateKeyPair()
            val senderEphemeralPub = Crypto.encodePublicKey(senderKeyPair)
            val sessionId = UUID.randomUUID().toString()

            // 3. Send direct transfer offer
            val offerJson = JSONObject().apply {
                put("kind", "direct.transfer.offer")
                put("sessionId", sessionId)
                put("senderDeviceId", localDeviceId)
                put("senderDeviceName", localDeviceName)
                put("publicKey", senderEphemeralPub)
                put("platform", "android")
                put("transferId", transferId)
                put("name", name)
                put("size", size)
                put("mimeType", mimeType)
                put("sha256", sha256)
            }
            writer.write(offerJson.toString() + "\n")
            writer.flush()

            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = peerName,
                fileName = name,
                isSending = true,
                bytesTransferred = 0,
                totalBytes = size,
                status = "Waiting for $peerName to accept…",
                phase = TransferPhase.AWAITING_DECISION,
            )

            // 4. Await accept / reject
            socket.soTimeout = 65_000
            val responseLine = reader.readLine() ?: error("Connection closed by recipient")
            val responseJson = JSONObject(responseLine)
            val responseKind = responseJson.optString("kind")

            if (responseKind != "direct.transfer.accept") {
                val reason = responseJson.optString("reason", "declined")
                val isUserDeclined = reason == "user_declined"
                mutableState.value = AndroidShareState.Transferring(
                    transferId = transferId,
                    peerName = peerName,
                    fileName = name,
                    isSending = true,
                    bytesTransferred = 0,
                    totalBytes = size,
                    status = if (isUserDeclined) "Transfer declined by $peerName" else "Transfer declined ($reason)",
                    phase = if (isUserDeclined) TransferPhase.REJECTED else TransferPhase.FAILED,
                    isError = true,
                )
                return
            }

            // Derive shared session key
            val receiverEphemeralPub = responseJson.getString("publicKey")
            val sessionKey = Crypto.pairingMaterial(senderKeyPair, receiverEphemeralPub, sessionId).key

            // 5. Stream chunks
            socket.soTimeout = 15_000
            var sentBytes = 0L
            var sequence = 0L
            val progress = TransferProgress(size)

            val input = openInputStream() ?: error("Input stream cannot be opened")
            input.use { inStream ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (sentBytes < size) {
                    val read = inStream.read(buffer)
                    if (read <= 0) break
                    val chunkPayload = buffer.copyOf(read)
                    val (nonce, ciphertext) = Crypto.encrypt(sessionKey, chunkPayload)

                    val chunkJson = JSONObject().apply {
                        put("kind", "direct.transfer.chunk")
                        put("sessionId", sessionId)
                        put("transferId", transferId)
                        put("sequence", sequence++)
                        put("nonce", nonce)
                        put("ciphertext", ciphertext)
                    }
                    writer.write(chunkJson.toString() + "\n")
                    writer.flush()

                    sentBytes += read
                    progress.status(sentBytes)?.let { st ->
                        mutableState.value = AndroidShareState.Transferring(
                            transferId = transferId,
                            peerName = peerName,
                            fileName = name,
                            isSending = true,
                            bytesTransferred = sentBytes,
                            totalBytes = size,
                            status = "Sending $name: $st",
                            phase = TransferPhase.TRANSFERRING,
                        )
                    }
                }
            }

            // 6. Complete and await verification ACK
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = peerName,
                fileName = name,
                isSending = true,
                bytesTransferred = sentBytes,
                totalBytes = size,
                status = "Verifying file integrity on recipient…",
                phase = TransferPhase.VERIFYING,
            )

            val completeJson = JSONObject().apply {
                put("kind", "direct.transfer.complete")
                put("sessionId", sessionId)
                put("transferId", transferId)
                put("sha256", sha256)
            }
            writer.write(completeJson.toString() + "\n")
            writer.flush()

            socket.soTimeout = 30_000
            val ackLine = reader.readLine() ?: error("Connection closed during verification")
            val ackJson = JSONObject(ackLine)

            if (ackJson.optString("kind") == "direct.transfer.complete.ack") {
                mutableState.value = AndroidShareState.Transferring(
                    transferId = transferId,
                    peerName = peerName,
                    fileName = name,
                    isSending = true,
                    bytesTransferred = size,
                    totalBytes = size,
                    status = "$name sent and verified",
                    phase = TransferPhase.COMPLETED,
                    isCompleted = true,
                )
                Log.i(TAG, "SYNQ Link transfer complete: $name sent to $peerName")
            } else {
                val error = ackJson.optString("error", "Verification failed")
                mutableState.value = AndroidShareState.Transferring(
                    transferId = transferId,
                    peerName = peerName,
                    fileName = name,
                    isSending = true,
                    bytesTransferred = sentBytes,
                    totalBytes = size,
                    status = "File verification failed on recipient ($error)",
                    phase = TransferPhase.FAILED,
                    isError = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SYNQ Link transfer exception: ${e.message}")
            val isCancelled = activeJob?.isCancelled == true
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = peerName,
                fileName = name,
                isSending = true,
                bytesTransferred = 0,
                totalBytes = size,
                status = if (isCancelled) "Transfer cancelled" else "Transfer failed: ${e.localizedMessage ?: "Connection error"}",
                phase = if (isCancelled) TransferPhase.CANCELLED else TransferPhase.FAILED,
                isError = true,
            )
        } finally {
            runCatching { socket.close() }
            if (currentActiveSocket === socket) currentActiveSocket = null
        }
    }

    fun handleIncomingTransfer(socket: Socket, reader: BufferedReader, offerJson: JSONObject) {
        scope.launch {
            handleIncomingTransferInternal(socket, reader, offerJson)
        }
    }

    private suspend fun handleIncomingTransferInternal(socket: Socket, reader: BufferedReader, offerJson: JSONObject) {
        currentActiveSocket = socket
        val transferId = offerJson.optString("transferId")
        val sessionId = offerJson.optString("sessionId")
        val senderDeviceId = offerJson.optString("senderDeviceId")
        val senderDeviceName = offerJson.optString("senderDeviceName", "Nearby Android Device")
        val senderPublicKey = offerJson.optString("publicKey")
        val rawName = offerJson.optString("name")
        val mimeType = offerJson.optString("mimeType", "application/octet-stream")
        val size = offerJson.optLong("size", -1L)
        val expectedSha256 = offerJson.optString("sha256")

        val sanitizedName = sanitizeFilename(rawName)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        if (sanitizedName.isEmpty() || size !in 0..MAX_FILE_SIZE || senderPublicKey.isEmpty() || expectedSha256.isEmpty()) {
            val reject = JSONObject().apply {
                put("kind", "direct.transfer.reject")
                put("sessionId", sessionId)
                put("transferId", transferId)
                put("reason", "invalid_metadata")
            }
            runCatching {
                writer.write(reject.toString() + "\n")
                writer.flush()
                socket.close()
            }
            return
        }

        // Check storage space
        val freeBytes = runCatching { Environment.getExternalStorageDirectory().freeSpace }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes in 1 until size) {
            val reject = JSONObject().apply {
                put("kind", "direct.transfer.reject")
                put("sessionId", sessionId)
                put("transferId", transferId)
                put("reason", "insufficient_storage")
            }
            runCatching {
                writer.write(reject.toString() + "\n")
                writer.flush()
                socket.close()
            }
            return
        }

        // Prompt user for acceptance
        val decision = CompletableDeferred<Boolean>()
        pendingIncomingDecision = decision

        mutableState.value = AndroidShareState.IncomingOffer(
            transferId = transferId,
            senderDeviceId = senderDeviceId,
            senderDeviceName = senderDeviceName,
            fileName = sanitizedName,
            fileSize = size,
            mimeType = mimeType,
            sha256 = expectedSha256,
            phase = TransferPhase.AWAITING_DECISION,
        )

        // If app is not in foreground, post notification with Accept / Decline actions
        val isForeground = (context.applicationContext as? SYNQApplication)?.isAppInForeground == true
        if (!isForeground) {
            postIncomingTransferNotification(senderDeviceName, sanitizedName, size)
        }

        val accepted = withTimeoutOrNull(60_000L) {
            decision.await()
        } == true
        pendingIncomingDecision = null
        dismissIncomingNotification()

        if (!accepted) {
            val reject = JSONObject().apply {
                put("kind", "direct.transfer.reject")
                put("sessionId", sessionId)
                put("transferId", transferId)
                put("reason", "user_declined")
            }
            runCatching {
                writer.write(reject.toString() + "\n")
                writer.flush()
                socket.close()
            }
            mutableState.value = AndroidShareState.Idle
            return
        }

        // Accepted: generate receiver ephemeral key and derive session key
        val receiverKeyPair = Crypto.generateKeyPair()
        val receiverEphemeralPub = Crypto.encodePublicKey(receiverKeyPair)
        val sessionKey = Crypto.pairingMaterial(receiverKeyPair, senderPublicKey, sessionId).key

        val acceptJson = JSONObject().apply {
            put("kind", "direct.transfer.accept")
            put("sessionId", sessionId)
            put("transferId", transferId)
            put("publicKey", receiverEphemeralPub)
        }
        writer.write(acceptJson.toString() + "\n")
        writer.flush()

        mutableState.value = AndroidShareState.Transferring(
            transferId = transferId,
            peerName = senderDeviceName,
            fileName = sanitizedName,
            isSending = false,
            bytesTransferred = 0,
            totalBytes = size,
            status = "Receiving $sanitizedName…",
            phase = TransferPhase.TRANSFERRING,
        )

        // Allocate file in Downloads/SYNQ
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizedName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SYNQ")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (fileUri == null) {
            runCatching {
                writer.write(JSONObject().put("kind", "direct.transfer.error").put("error", "storage_allocation_failed").toString() + "\n")
                writer.flush()
                socket.close()
            }
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = senderDeviceName,
                fileName = sanitizedName,
                isSending = false,
                bytesTransferred = 0,
                totalBytes = size,
                status = "Failed to allocate file in Downloads/SYNQ",
                phase = TransferPhase.FAILED,
                isError = true,
            )
            return
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var receivedBytes = 0L
        val progress = TransferProgress(size)

        try {
            socket.soTimeout = 20_000
            val outputStream = resolver.openOutputStream(fileUri, "w")
                ?.let { BufferedOutputStream(it, 256 * 1024) }
                ?: error("Could not open output stream for download")

            outputStream.use { out ->
                while (receivedBytes < size) {
                    val line = reader.readLine() ?: break
                    val msg = JSONObject(line)
                    val kind = msg.optString("kind")
                    if (kind == "direct.transfer.chunk") {
                        val nonce = msg.getString("nonce")
                        val ciphertext = msg.getString("ciphertext")
                        val chunk = Crypto.decrypt(sessionKey, nonce, ciphertext)
                            ?: error("Decryption failure in chunk")
                        out.write(chunk)
                        digest.update(chunk)
                        receivedBytes += chunk.size
                        progress.status(receivedBytes)?.let { st ->
                            mutableState.value = AndroidShareState.Transferring(
                                transferId = transferId,
                                peerName = senderDeviceName,
                                fileName = sanitizedName,
                                isSending = false,
                                bytesTransferred = receivedBytes,
                                totalBytes = size,
                                status = "Receiving $sanitizedName: $st",
                                phase = TransferPhase.TRANSFERRING,
                            )
                        }
                    } else if (kind == "direct.transfer.complete") {
                        break
                    }
                }
                out.flush()
            }

            // Verifying phase
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = senderDeviceName,
                fileName = sanitizedName,
                isSending = false,
                bytesTransferred = receivedBytes,
                totalBytes = size,
                status = "Verifying file integrity…",
                phase = TransferPhase.VERIFYING,
            )

            val actualSha256 = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            if (receivedBytes == size && actualSha256 == expectedSha256) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(fileUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                val ack = JSONObject().apply {
                    put("kind", "direct.transfer.complete.ack")
                    put("sessionId", sessionId)
                    put("transferId", transferId)
                    put("status", "verified")
                }
                writer.write(ack.toString() + "\n")
                writer.flush()

                ReceivedFileNotifier.show(context, sanitizedName, mimeType, fileUri)
                mutableState.value = AndroidShareState.Transferring(
                    transferId = transferId,
                    peerName = senderDeviceName,
                    fileName = sanitizedName,
                    isSending = false,
                    bytesTransferred = size,
                    totalBytes = size,
                    status = "$sanitizedName saved to Download/SYNQ (Verified)",
                    phase = TransferPhase.COMPLETED,
                    isCompleted = true,
                )
                Log.i(TAG, "SYNQ Link transfer verified & saved: $sanitizedName from $senderDeviceName")
            } else {
                resolver.delete(fileUri, null, null)
                val err = JSONObject().apply {
                    put("kind", "direct.transfer.error")
                    put("sessionId", sessionId)
                    put("transferId", transferId)
                    put("error", "checksum_mismatch")
                }
                writer.write(err.toString() + "\n")
                writer.flush()

                mutableState.value = AndroidShareState.Transferring(
                    transferId = transferId,
                    peerName = senderDeviceName,
                    fileName = sanitizedName,
                    isSending = false,
                    bytesTransferred = receivedBytes,
                    totalBytes = size,
                    status = "Verification failed — checksum mismatch (Corrupt file deleted)",
                    phase = TransferPhase.FAILED,
                    isError = true,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Incoming transfer exception: ${e.message}")
            resolver.delete(fileUri, null, null)
            val isCancelled = activeJob?.isCancelled == true
            mutableState.value = AndroidShareState.Transferring(
                transferId = transferId,
                peerName = senderDeviceName,
                fileName = sanitizedName,
                isSending = false,
                bytesTransferred = receivedBytes,
                totalBytes = size,
                status = if (isCancelled) "Transfer cancelled" else "Transfer error: ${e.localizedMessage ?: "Connection interrupted"}",
                phase = if (isCancelled) TransferPhase.CANCELLED else TransferPhase.FAILED,
                isError = true,
            )
        } finally {
            runCatching { socket.close() }
            if (currentActiveSocket === socket) currentActiveSocket = null
        }
    }

    private fun postIncomingTransferNotification(sender: String, fileName: String, size: Long) {
        val acceptIntent = Intent(context, SYNQConnectionService::class.java).apply {
            action = ACTION_ACCEPT_SHARE
        }
        val pendingAccept = PendingIntent.getService(
            context,
            1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, SYNQConnectionService::class.java).apply {
            action = ACTION_DECLINE_SHARE
        }
        val pendingDecline = PendingIntent.getService(
            context,
            2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, INCOMING_SHARE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder.setSmallIcon(R.drawable.ic_synq_notification)
            .setContentTitle("SYNQ Link: $sender")
            .setContentText("Wants to send $fileName (${formatBytes(size)})")
            .setContentIntent(pendingOpen)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_save, "Accept", pendingAccept)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", pendingDecline)

        notificationManager?.notify(NOTIFICATION_SHARE_OFFER_ID, builder.build())
    }

    private fun dismissIncomingNotification() {
        notificationManager?.cancel(NOTIFICATION_SHARE_OFFER_ID)
    }

    private fun sanitizeFilename(name: String): String {
        val leaf = File(name).name.replace(Regex("[/\\\\:?*\"<>|]"), "_").trim()
        return leaf.take(128).ifEmpty { "file_${System.currentTimeMillis()}" }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
