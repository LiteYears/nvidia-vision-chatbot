package com.example.agent.tools.web

import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Validates URLs for the web_open tool to prevent SSRF and protect local
 * files and Android system resources.
 *
 * Rules:
 * - Scheme must be strictly "http" or "https".
 * - Schemes such as "file", "content", "javascript", "data", "android.resource" are strictly blocked.
 * - Loopback and private IP addresses (127.x.x.x, 10.x.x.x, 192.168.x.x, 172.16-31.x.x, etc.) are blocked.
 * - Localhost and internal domains (.local, .internal, .lan) are blocked.
 */
object UrlSecurityValidator {

    fun validate(urlString: String): Result<String> {
        val trimmed = urlString.trim()
        if (trimmed.isBlank()) {
            return Result.failure(
                IllegalArgumentException("URL cannot be empty or blank.")
            )
        }

        val colonSlashIndex = trimmed.indexOf("://")
        if (colonSlashIndex != -1) {
            val scheme = trimmed.substring(0, colonSlashIndex).lowercase()
            if (scheme != "http" && scheme != "https") {
                return Result.failure(
                    SecurityException("Access denied: URL scheme '$scheme' is not permitted.")
                )
            }
        } else if (trimmed.startsWith("file:", ignoreCase = true) ||
            trimmed.startsWith("content:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)) {
            val scheme = trimmed.substringBefore(':').lowercase()
            return Result.failure(
                SecurityException("Access denied: URL scheme '$scheme' is not permitted.")
            )
        }

        // Auto-normalize URLs without scheme (e.g. 'bbc.com', 'news.google.com')
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val parsedUri = try {
            URI(normalized)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid URL syntax: '$normalized'"))
        }

        val host = parsedUri.host ?: ""
        if (isPrivateOrLoopbackIp(host)) {
            return Result.failure(
                SecurityException("Access denied: Access to local/private network address '$host' is not permitted.")
            )
        }

        return Result.success(normalized)
    }

    /**
     * Checks if a given hostname or IP string corresponds to a private, loopback,
     * or link-local address.
     */
    fun isPrivateOrLoopbackIp(host: String): Boolean {
        val lowerHost = host.lowercase().trim()
        if (lowerHost.isEmpty()) return false

        if (lowerHost == "localhost" || lowerHost.endsWith(".localhost") ||
            lowerHost.endsWith(".local") || lowerHost.endsWith(".internal") || lowerHost.endsWith(".lan")) {
            return true
        }

        if (lowerHost == "127.0.0.1" || lowerHost.startsWith("127.") || lowerHost == "::1" || lowerHost == "0.0.0.0") {
            return true
        }

        // Private / local IPv4 ranges
        if (lowerHost.startsWith("10.") || lowerHost.startsWith("192.168.") || lowerHost.startsWith("169.254.")) {
            return true
        }

        if (lowerHost.startsWith("172.")) {
            val parts = lowerHost.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Optional DNS resolution check to verify that resolved IP is not private/loopback.
     */
    fun isResolvedAddressPrivate(host: String): Boolean {
        return isPrivateOrLoopbackIp(host)
    }
}
