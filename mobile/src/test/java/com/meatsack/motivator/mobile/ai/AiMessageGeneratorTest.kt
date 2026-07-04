package com.meatsack.motivator.mobile.ai

import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.model.Message
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMessageGeneratorTest {

    private class FakeStore(
        val loved: List<String> = emptyList(),
        val hated: List<String> = emptyList(),
        var all: List<Message> = emptyList(),
    ) : GenerationStore {
        val inserted = mutableListOf<Message>()
        val deleted = mutableListOf<Long>()
        override suspend fun getLovedTexts(limit: Int) = loved.take(limit)
        override suspend fun getHatedTexts(limit: Int) = hated.take(limit)
        override suspend fun getAllMessages() = all
        override suspend fun insertAll(messages: List<Message>) {
            inserted += messages
            all = all + messages
        }
        override suspend fun deleteByIds(ids: List<Long>) {
            deleted += ids
        }
    }

    // Implements the InsultClient seam (Step 3) — no Anthropic SDK / key needed.
    private class FakeClient(private val results: ArrayDeque<GenerationResult>) : InsultClient {
        val prompts = mutableListOf<String>()
        override suspend fun generate(systemPrompt: String, userPrompt: String): GenerationResult {
            prompts += userPrompt
            return results.removeFirst()
        }
    }

    private fun gen(store: GenerationStore, client: InsultClient, sync: suspend () -> SyncResult = { SyncResult.Success(0) }) =
        AiMessageGenerator(store, client, sync)

    @Test fun generatesFiveInsultsForEachOfFourLevels() = runTest {
        val store = FakeStore()
        val client = FakeClient(ArrayDeque(List(4) { GenerationResult.Success(List(5) { i -> "line$i" }) }))
        var syncs = 0
        val result = gen(store, client, sync = {
            syncs++
            SyncResult.Success(20)
        })
            .generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, hourOfDay = 9, currentSteps = 0)

        assertTrue(result is GenerationResult.Success)
        assertEquals(20, store.inserted.size)
        assertEquals(
            setOf(EscalationLevel.AGGRESSIVE, EscalationLevel.SAVAGE, EscalationLevel.NUCLEAR, EscalationLevel.EXISTENTIAL),
            store.inserted.map { it.level }.toSet(),
        )
        assertEquals(1, syncs) // synced once, not per level
    }

    @Test fun partialFailureStillInsertsSucceededLevels() = runTest {
        val store = FakeStore()
        val client = FakeClient(
            ArrayDeque(
                listOf(
                    GenerationResult.Success(List(5) { "ok" }),
                    GenerationResult.Failed(RuntimeException("network")),
                    GenerationResult.Success(List(5) { "ok" }),
                    GenerationResult.HttpError(429, "rate"),
                ),
            ),
        )
        val result = gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(result is GenerationResult.Success)
        assertEquals(10, store.inserted.size) // 2 levels x 5
    }

    @Test fun noApiKeyShortCircuitsWithoutInserting() = runTest {
        val store = FakeStore()
        val client = FakeClient(ArrayDeque(listOf<GenerationResult>(GenerationResult.NoApiKey)))
        val result = gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(result is GenerationResult.NoApiKey)
        assertEquals(0, store.inserted.size)
    }

    @Test fun filtersOverLongAndPipeAndNewlineLines() = runTest {
        val store = FakeStore()
        val bad = "x".repeat(101)
        val client = FakeClient(ArrayDeque(List(4) { GenerationResult.Success(listOf("good", bad, "a|b")) }))
        gen(store, client).generateAcrossLevels(TriggerType.INACTIVITY, MessageTone.FULL_SEND, 9, 0)
        assertTrue(store.inserted.all { it.text == "good" }) // only the valid line survives per level
        assertEquals(4, store.inserted.size)
    }
}
