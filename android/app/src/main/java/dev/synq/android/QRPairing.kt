package dev.synq.android

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

data class QRPairingPayload(
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val token: String,
    val expiresAt: Long,
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis >= expiresAt
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val SCHEME = "synq"
        const val HOST_PAIR = "pair"

        fun parse(raw: String): Result<QRPairingPayload> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Empty QR content"))
            }

            return if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                parseJson(trimmed)
            } else if (trimmed.startsWith("synq://", ignoreCase = true)) {
                parseUri(trimmed)
            } else {
                Result.failure(IllegalArgumentException("Unsupported QR payload format: must be synq:// URI or JSON"))
            }
        }

        private fun parseJson(jsonString: String): Result<QRPairingPayload> = runCatching {
            val json = JSONObject(jsonString)
            val version = json.optInt("v", json.optInt("version", -1))
            val deviceId = if (json.has("id")) json.getString("id") else json.getString("deviceId")
            val deviceName = if (json.has("name")) json.getString("name") else json.getString("deviceName")
            val host = json.getString("host")
            val port = json.getInt("port")
            val token = json.getString("token")
            val expiresAt = if (json.has("exp")) json.getLong("exp") else json.getLong("expiresAt")
            validateAndConstruct(version, deviceId, deviceName, host, port, token, expiresAt)
        }

        private fun parseUri(uriString: String): Result<QRPairingPayload> = runCatching {
            val uri = URI(uriString)
            val scheme = uri.scheme
            val host = uri.host
            if (!SCHEME.equals(scheme, ignoreCase = true) || !HOST_PAIR.equals(host, ignoreCase = true)) {
                throw IllegalArgumentException("Invalid QR URI scheme or host: $scheme://$host")
            }
            val query = uri.rawQuery ?: ""
            val params = query.split('&').filter { it.isNotEmpty() }.associate { param ->
                val parts = param.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }
            val version = params["v"]?.toIntOrNull() ?: -1
            val deviceId = params["id"] ?: throw IllegalArgumentException("Missing device id")
            val deviceName = params["name"] ?: throw IllegalArgumentException("Missing device name")
            val hostParam = params["host"] ?: throw IllegalArgumentException("Missing host")
            val port = params["port"]?.toIntOrNull() ?: throw IllegalArgumentException("Missing port")
            val token = params["token"] ?: throw IllegalArgumentException("Missing token")
            val expiresAt = params["exp"]?.toLongOrNull() ?: throw IllegalArgumentException("Missing expiry")
            validateAndConstruct(version, deviceId, deviceName, hostParam, port, token, expiresAt)
        }

        fun validateAndConstruct(
            version: Int,
            deviceId: String,
            deviceName: String,
            host: String,
            port: Int,
            token: String,
            expiresAt: Long,
        ): QRPairingPayload {
            if (version != CURRENT_VERSION) {
                throw IllegalArgumentException("Unsupported protocol version: $version")
            }
            if (deviceId.length < 6 || !deviceId.all { it.isLetterOrDigit() || it == '-' }) {
                throw IllegalArgumentException("Invalid device ID format: $deviceId")
            }
            if (deviceName.isBlank() || deviceName.length > 64) {
                throw IllegalArgumentException("Invalid device name length: ${deviceName.length}")
            }
            if (port !in 1024..65535) {
                throw IllegalArgumentException("Port $port is outside valid range (1024-65535)")
            }
            if (token.length < 16 || !token.all { it.isLetterOrDigit() }) {
                throw IllegalArgumentException("Invalid token format")
            }
            if (!isPrivateOrLocalHost(host)) {
                throw IllegalArgumentException("Security rejection: non-local host IP address ($host)")
            }
            val normalizedExpiry = if (expiresAt < 10_000_000_000L) expiresAt * 1000L else expiresAt
            if (normalizedExpiry <= System.currentTimeMillis()) {
                throw IllegalArgumentException("Pairing session has already expired")
            }
            return QRPairingPayload(version, deviceId, deviceName, host, port, token, normalizedExpiry)
        }

        fun isPrivateOrLocalHost(host: String): Boolean {
            val clean = host.trim().lowercase()
            if (clean == "localhost" || clean.endsWith(".local")) return true

            val parts = clean.split(".")
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
                val b0 = parts[0].toInt()
                val b1 = parts[1].toInt()
                if (b0 == 10) return true
                if (b0 == 172 && b1 in 16..31) return true
                if (b0 == 192 && b1 == 168) return true
                if (b0 == 127) return true
                if (b0 == 169 && b1 == 254) return true
                return false
            }
            if (clean.startsWith("fe80:") || clean == "::1") return true
            return false
        }
    }
}
