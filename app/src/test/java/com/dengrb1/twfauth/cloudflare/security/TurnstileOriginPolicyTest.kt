package com.dengrb1.twfauth.cloudflare.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnstileOriginPolicyTest {
    private val policy = TurnstileOriginPolicy("https://twofactor.example.com:8443/app")

    @Test fun `allows only the exact worker and Cloudflare challenge origins`() {
        assertTrue(policy.allows("https://twofactor.example.com:8443/login"))
        assertTrue(policy.allows("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertFalse(policy.allows("https://twofactor.example.com/login"))
        assertFalse(policy.allows("https://evil.twofactor.example.com:8443/"))
        assertFalse(policy.allows("http://twofactor.example.com:8443/"))
        assertFalse(policy.allows("https://challenges.cloudflare.com:8443/"))
    }
}

