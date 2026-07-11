package com.dengrb1.twfauth.cloudflare.security

import java.net.URI

class TurnstileOriginPolicy(workerUrl: String) {
    private val worker = Origin.parse(workerUrl)
    private val challenge = Origin("https", "challenges.cloudflare.com", 443)

    fun allows(url: String): Boolean = runCatching { Origin.parse(url) }.getOrNull() in setOf(worker, challenge)

    private data class Origin(val scheme: String, val host: String, val port: Int) {
        companion object {
            fun parse(value: String): Origin {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase() ?: error("Missing scheme")
                val host = uri.host?.lowercase() ?: error("Missing host")
                val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
                return Origin(scheme, host, port)
            }
        }
    }
}

