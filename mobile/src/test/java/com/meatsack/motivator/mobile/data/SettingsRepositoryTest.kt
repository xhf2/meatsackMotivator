package com.meatsack.motivator.mobile.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun validateHour_acceptsBoundaryValues() {
        SettingsRepository.validateHour("test", 0)
        SettingsRepository.validateHour("test", 23)
    }

    @Test
    fun validateHour_acceptsInteriorValues() {
        for (h in 1..22) {
            SettingsRepository.validateHour("test", h)
        }
    }

    @Test
    fun validateHour_rejectsNegative() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SettingsRepository.validateHour("Active hours start", -1)
        }
        assertTrue(
            "Message should include field name; was: ${e.message}",
            e.message?.contains("Active hours start") == true,
        )
        assertTrue(
            "Message should include 'was -1' (locks format, not just any '-1'); was: ${e.message}",
            e.message?.contains("was -1") == true,
        )
    }

    @Test
    fun validateHour_rejects24() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsRepository.validateHour("test", 24)
        }
    }

    @Test
    fun validatePositive_acceptsOne() {
        SettingsRepository.validatePositive("test", 1)
    }

    @Test
    fun validatePositive_acceptsLargeValues() {
        SettingsRepository.validatePositive("test", 10_000)
        SettingsRepository.validatePositive("test", Int.MAX_VALUE)
    }

    @Test
    fun validatePositive_rejectsZero() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SettingsRepository.validatePositive("Daily step goal", 0)
        }
        assertTrue(
            "Message should include field name; was: ${e.message}",
            e.message?.contains("Daily step goal") == true,
        )
        assertTrue(
            "Message should include 'was 0' (locks format, not any '0'); was: ${e.message}",
            e.message?.contains("was 0") == true,
        )
    }

    @Test
    fun validatePositive_rejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsRepository.validatePositive("test", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsRepository.validatePositive("test", -500)
        }
    }
}
