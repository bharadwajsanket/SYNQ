package dev.synq.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.min

internal const val MAX_PROTOCOL_FRAME_BYTES = 65_536
internal const val MAX_TRANSFER_HISTORY = 20

internal class ProtocolFrameTooLargeException : IllegalArgumentException("Protocol frame is too large")

internal fun InputStream.readProtocolLine(maxBytes: Int = MAX_PROTOCOL_FRAME_BYTES): String? {
    if (markSupported()) {
        val window = maxBytes + 2
        mark(window)
        val buffer = ByteArray(window)
        var totalRead = 0
        var newlineIndex = -1
        while (totalRead < window) {
            val count = read(buffer, totalRead, window - totalRead)
            if (count < 0) break
            val prev = totalRead
            totalRead += count
            for (i in prev until totalRead) {
                if (buffer[i] == '\n'.code.toByte()) {
                    newlineIndex = i
                    break
                }
            }
            if (newlineIndex >= 0) break
        }
        if (newlineIndex >= 0) {
            if (newlineIndex > maxBytes) {
                reset()
                throw ProtocolFrameTooLargeException()
            }
            reset()
            var toSkip = (newlineIndex + 1).toLong()
            while (toSkip > 0) {
                val skipped = skip(toSkip)
                if (skipped <= 0) {
                    if (read() < 0) break
                    toSkip--
                } else {
                    toSkip -= skipped
                }
            }
            var len = newlineIndex
            if (len > 0 && buffer[len - 1] == '\r'.code.toByte()) len--
            return decodeUtf8(buffer, 0, len)
        }
        if (totalRead > maxBytes) {
            reset()
            throw ProtocolFrameTooLargeException()
        }
        if (totalRead == 0) return null
        reset()
        var toSkip = totalRead.toLong()
        while (toSkip > 0) {
            val skipped = skip(toSkip)
            if (skipped <= 0) {
                if (read() < 0) break
                toSkip--
            } else {
                toSkip -= skipped
            }
        }
        var len = totalRead
        if (len > 0 && buffer[len - 1] == '\r'.code.toByte()) len--
        return decodeUtf8(buffer, 0, len)
    }

    val value = ByteArrayOutputStream(min(maxBytes, 4096))
    while (true) {
        when (val byte = read()) {
            -1 -> return value.takeIf { it.size() > 0 }?.toUtf8String()
            '\n'.code -> return value.toUtf8String()
            '\r'.code -> Unit
            else -> {
                if (value.size() >= maxBytes) throw ProtocolFrameTooLargeException()
                value.write(byte)
            }
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
