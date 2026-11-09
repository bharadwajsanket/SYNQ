package dev.synq.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.min

internal const val MAX_PROTOCOL_FRAME_BYTES = 4 * 1024 * 1024
internal const val MAX_TRANSFER_HISTORY = 20

internal class ProtocolFrameTooLargeException : IllegalArgumentException("Protocol frame is too large")

internal fun InputStream.readProtocolLine(maxBytes: Int = MAX_PROTOCOL_FRAME_BYTES): String? {
    val out = ByteArrayOutputStream(min(maxBytes, 8192))
    while (true) {
        val b = read()
        if (b == -1) {
            return if (out.size() > 0) decodeUtf8(out.toByteArray(), 0, out.size()) else null
        }
        if (b == '\n'.code) {
            val bytes = out.toByteArray()
            var len = bytes.size
            if (len > 0 && bytes[len - 1] == '\r'.code.toByte()) len--
            return decodeUtf8(bytes, 0, len)
        }
        if (b != '\r'.code) {
            if (out.size() >= maxBytes) {
                throw ProtocolFrameTooLargeException()
            }
            out.write(b)
        }
    }
}

private fun decodeUtf8(bytes: ByteArray, offset: Int, length: Int): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes, offset, length))
    .toString()

private fun ByteArrayOutputStream.toUtf8String(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(toByteArray()))
    .toString()

internal fun reconnectDelayMillis(attempt: Int): Long {
    val boundedAttempt = attempt.coerceIn(0, 30)
    return min(1L shl boundedAttempt, 30L) * 1_000L
}

internal fun heartbeatExpired(
    supported: Boolean,
    lastReceivedAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = 30_000L,
): Boolean = supported && nowMillis - lastReceivedAtMillis >= timeoutMillis

internal fun recoverInterruptedTransfers(
    transfers: Map<String, FileTransferState>,
): Map<String, FileTransferState> = transfers
    .mapValues { (_, transfer) ->
        if (transfer.active) transfer.copy(
            status = "Transfer interrupted — reconnect to retry",
            active = false,
            progressPercent = null,
        ) else transfer
    }
    .values
    .sortedByDescending(FileTransferState::startedAtMillis)
    .take(MAX_TRANSFER_HISTORY)
    .associateBy(FileTransferState::id)

internal const val SECONDARY_INITIATOR_FALLBACK_DELAY_MILLIS = 4_000L

internal fun shouldInitiateConnectionImmediately(localDeviceId: String, remoteDeviceId: String): Boolean =
    localDeviceId.lowercase() < remoteDeviceId.lowercase()
