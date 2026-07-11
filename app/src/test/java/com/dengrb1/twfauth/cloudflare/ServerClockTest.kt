package com.dengrb1.twfauth.cloudflare

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerClockTest {
    @Test fun `server expiry is projected onto a device clock that is five minutes fast`() {
        assertEquals(1_321L, correctedExpiryEpochSeconds(serverTime = 1_000, expiresIn = 21, deviceNow = 1_300))
    }

    @Test fun `server expiry is projected onto a device clock that is five minutes slow`() {
        assertEquals(721L, correctedExpiryEpochSeconds(serverTime = 1_000, expiresIn = 21, deviceNow = 700))
    }
}

