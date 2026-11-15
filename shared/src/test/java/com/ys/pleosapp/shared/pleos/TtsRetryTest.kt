package com.ys.pleosapp.shared.pleos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRetryTest {
    @Test
    fun keepsRetryingBelowCap() {
        assertTrue(shouldKeepRetryingTts(0))
        assertTrue(shouldKeepRetryingTts(TTS_NOT_READY_MAX_RETRIES - 1))
    }

    @Test
    fun dropsLineOnceCapReached() {
        assertFalse(shouldKeepRetryingTts(TTS_NOT_READY_MAX_RETRIES))
        assertFalse(shouldKeepRetryingTts(TTS_NOT_READY_MAX_RETRIES + 1))
    }
}
