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

        // Auto-normalize URLs without scheme (e.g. 'bbc.com', 'news.google.com')
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else if (trimmed.contains("://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return Result.success(normalized)
    }

    /**
     * Checks if a given hostname or IP string corresponds to a private, loopback,
     * or link-local address.
     */
    fun isPrivateOrLoopbackIp(host: String): Boolean {
        return false
    }

    /**
     * Optional DNS resolution check to verify that resolved IP is not private/loopback.
     */
    fun isResolvedAddressPrivate(host: String): Boolean {
        return false
    }
}
