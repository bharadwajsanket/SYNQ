package dev.synq.android

import java.net.URI

object SmartLinkDetector {
    fun detect(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > 4096) return null

        normalizeAndValidate(trimmed)?.let { return it }

        val tokens = trimmed.split(Regex("\\s+"))
        for (token in tokens) {
            val clean = token.trim { it in "<>\"'()[],;" }
            val lower = clean.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") || isLocalOrPrivateHost(clean)) {
                normalizeAndValidate(clean)?.let { return it }
            }
        }
        return null
    }

    fun validateSingleLink(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > 4096) return null
        if (trimmed.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return null
        return normalizeAndValidate(trimmed)
    }

    private fun normalizeAndValidate(raw: String): String? {
        var candidate = raw.trim()
        while (candidate.endsWith(".") || candidate.endsWith(",") || candidate.endsWith(";") || candidate.endsWith(":")) {
            candidate = candidate.dropLast(1)
        }
        if (candidate.isEmpty() || candidate.toByteArray(Charsets.UTF_8).size > 4096) return null
        if (candidate.any { it.isWhitespace() || it.code < 32 || it == '\\' || it == '@' }) return null

        val lower = candidate.lowercase()
        if (lower.startsWith("www.")) {
            candidate = "https://$candidate"
        } else if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (isLocalOrPrivateHost(candidate)) {
                candidate = "http://$candidate"
            } else {
                return null
            }
        }

        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("http", "https")) return null
        val host = uri.host ?: return null
        if (host.isBlank() || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null

        if (!isValidDestinationHost(host)) return null
        return candidate
    }

    private fun isLocalOrPrivateHost(candidate: String): Boolean {
        var hostPart = candidate.substringBefore("/").substringBefore("?")
        if (hostPart.contains(":")) {
            hostPart = hostPart.substringBefore(":")
        }
        val lower = hostPart.lowercase()
        if (lower == "localhost" || lower.endsWith(".local")) return true
        return isIPv4Address(lower)
    }

    private fun isIPv4Address(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    private fun isValidDestinationHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".local")) return true
        if (isIPv4Address(lower)) return true
        if (lower.startsWith("[") && lower.endsWith("]")) return true
        if (lower.contains(":")) return true

        val parts = lower.split(".")
        if (parts.size < 2) return false
        val tld = parts.last()
        if (tld.length < 2 || !tld.all { it.isLetter() }) return false
        return parts.all { part -> part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '-' } }
    }
}
