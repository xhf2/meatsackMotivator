package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.util.Log
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.GenerationLimits
import com.meatsack.shared.constants.MessageLimits
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message
import com.meatsack.shared.retention.LibraryPruner

/**
 * Orchestrates a "Generate" press: one Claude call per escalation level (vote-steered prompt),
 * insert, prune the library to keep buckets bounded, and sync to the watch once. Dependencies are
 * injected so the loop is unit-testable; [create] wires the production Room + Anthropic + sync.
 */
class AiMessageGenerator(
    private val store: GenerationStore,
    private val client: InsultClient,
    private val sync: suspend () -> SyncResult,
) {

    suspend fun generateAcrossLevels(
        trigger: TriggerType,
        tone: MessageTone,
        hourOfDay: Int,
        currentSteps: Int,
    ): GenerationResult {
        val loved = store.getLovedTexts(GenerationLimits.LOVED_EXAMPLES)
        val hated = store.getHatedTexts(GenerationLimits.HATED_EXAMPLES)

        val accumulated = mutableListOf<Message>()
        var firstFailure: GenerationResult? = null

        for (level in EscalationLevel.entries) {
            val prompt = Prompts.buildUserPrompt(
                currentSteps,
                hourOfDay,
                level,
                trigger,
                tone,
                loved = loved,
                hated = hated,
                count = GenerationLimits.INSULTS_PER_LEVEL,
            )
            when (val result = client.generate(Prompts.SYSTEM_PROMPT, prompt)) {
                is GenerationResult.Success -> {
                    val valid = result.messages.filter {
                        it.length <= MessageLimits.MAX_MESSAGE_TEXT_LENGTH &&
                            !it.contains('|') && !it.contains('\n')
                    }
                    accumulated += valid.map {
                        Message(
                            text = it,
                            level = level,
                            triggerType = trigger,
                            tone = tone,
                            source = MessageSource.AI_GENERATED,
                            lastShownTimestamp = 0,
                            isActive = true,
                        )
                    }
                }
                GenerationResult.NoApiKey -> return GenerationResult.NoApiKey
                is GenerationResult.HttpError -> if (firstFailure == null) firstFailure = result
                is GenerationResult.Failed -> if (firstFailure == null) firstFailure = result
            }
        }

        if (accumulated.isEmpty()) {
            return firstFailure ?: GenerationResult.Success(emptyList())
        }

        store.insertAll(accumulated)

        val toDelete = LibraryPruner.selectForDeletion(
            store.getAllMessages(),
            GenerationLimits.BUCKET_CAP,
            GenerationLimits.BUCKET_FLOOR,
        )
        if (toDelete.isNotEmpty()) store.deleteByIds(toDelete)

        when (val syncResult = sync()) {
            is SyncResult.Success -> Log.d(TAG, "Synced ${syncResult.count} messages to watch")
            SyncResult.NoMessages -> Log.w(TAG, "Sync had nothing to send after generation")
            is SyncResult.Failed -> Log.w(TAG, "Sync to watch failed after generation", syncResult.error)
        }
        return GenerationResult.Success(accumulated.map { it.text })
    }

    companion object {
        private const val TAG = "AiMessageGenerator"

        fun create(context: Context): AiMessageGenerator {
            val dao = AppDatabase.getDatabase(context).messageDao()
            return AiMessageGenerator(
                store = RoomGenerationStore(dao),
                client = ClaudeApiClient(ApiKeyStore(context)),
                sync = { PhoneSyncSender(context).syncMessagesToWatch() },
            )
        }
    }
}
