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

    private val BLOCKED_SCHEMES = setOf(
        "file",
        "content",
        "javascript",
        "data",
        "android.resource",
        "about",
        "ftp",
        "ws",
        "wss",
        "jar",
        "intent"
    )

    private val BLOCKED_HOST_SUFFIXES = listOf(
        ".local",
        ".internal",
        ".lan",
        ".localdomain",
        ".home",
        ".corp"
    )

    /**
     * Validates a URL string for web access.
     *
     * @param urlString The raw URL string provided by the agent.
     * @return Result.success with normalized URL, or Result.failure with descriptive security error.
     */
    fun validate(urlString: String): Result<String> {
        val trimmed = urlString.trim()
        if (trimmed.isBlank()) {
            return Result.failure(
                IllegalArgumentException("URL cannot be empty or blank.")
            )
        }

        // 1. Auto-normalize URLs without scheme (e.g. 'bbc.com', 'news.google.com')
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else if (trimmed.contains("://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        // 2. Basic structural parsing
        val uri: URI = try {
            URI(normalized)
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Invalid URL syntax: '${trimmed}'. Error: ${e.message}")
            )
        }

        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Missing URL protocol/scheme. Only 'http://' and 'https://' URLs are supported.")
            )
        }

        if (scheme in BLOCKED_SCHEMES || (scheme != "http" && scheme != "https")) {
            return Result.failure(
                SecurityException(
                    "Access denied: Protocol '$scheme' is not permitted. Only 'http' and 'https' web pages can be opened. " +
                        "Local files, Android system resources, and internal schemes are strictly restricted."
                )
            )
        }

        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("Invalid URL: missing host name in '$trimmed'.")
            )
        }

        // 3. Reject localhost and known local hostnames
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1" || host == "[::1]") {
            return Result.failure(
                SecurityException("Access denied: Access to localhost/loopback address is restricted for security.")
            )
        }

        if (BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }) {
            return Result.failure(
                SecurityException("Access denied: Access to internal local domain '$host' is restricted.")
            )
        }

        // 4. Reject direct private and loopback IPv4/IPv6 addresses
        if (isPrivateOrLoopbackIp(host)) {
            return Result.failure(
                SecurityException("Access denied: IP address '$host' is a private, loopback, or local network address.")
            )
        }

        // 5. Validate that java.net.URL can parse it
        try {
            URL(normalized)
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Malformed URL: '${trimmed}'. Error: ${e.message}")
            )
        }

        return Result.success(normalized)
    }

    /**
     * Checks if a given hostname or IP string corresponds to a private, loopback,
     * or link-local address.
     */
    fun isPrivateOrLoopbackIp(host: String): Boolean {
        // Direct regex check for common private IPv4 patterns
        if (host.startsWith("127.")) return true // Loopback
        if (host.startsWith("10.")) return true // Class A private
        if (host.startsWith("192.168.")) return true // Class C private
        if (host.startsWith("169.254.")) return true // Link-local
        if (host == "10.0.2.2") return true // Android emulator host alias

        // 172.16.0.0 to 172.31.255.255
        if (host.startsWith("172.")) {
            val parts = host.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) {
                    return true
                }
            }
        }

        // 100.64.0.0 to 100.127.255.255 (Carrier-grade NAT)
        if (host.startsWith("100.")) {
            val parts = host.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 64..127) {
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
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
        } catch (_: Exception) {
            false
        }
    }
}
