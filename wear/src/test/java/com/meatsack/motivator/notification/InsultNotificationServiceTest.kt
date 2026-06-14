package com.meatsack.motivator.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class InsultNotificationServiceTest {

    @Test
    fun bubbles_includeStats_whenPresent() {
        assertEquals(
            listOf("GET UP.", "438 steps. It's 2pm."),
            InsultNotificationService.insultBubbles("GET UP.", "438 steps. It's 2pm."),
        )
    }

    @Test
    fun bubbles_insultOnly_whenStatsEmpty() {
        assertEquals(listOf("GET UP."), InsultNotificationService.insultBubbles("GET UP.", ""))
    }

    @Test
    fun bubbles_insultOnly_whenStatsBlank() {
        assertEquals(listOf("GET UP."), InsultNotificationService.insultBubbles("GET UP.", "   "))
    }
}
