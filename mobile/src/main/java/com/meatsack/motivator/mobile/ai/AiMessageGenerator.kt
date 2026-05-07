package com.meatsack.motivator.mobile.ai

import android.content.Context
import android.util.Log
import com.meatsack.motivator.mobile.sync.PhoneSyncSender
import com.meatsack.motivator.mobile.sync.SyncResult
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType
import com.meatsack.shared.db.AppDatabase
import com.meatsack.shared.model.Message

/**
 * Orchestrates a full "Generate Now" cycle:
 * 1. Read the user's top-upvoted messages as style examples
 * 2. Ask Claude for N new insults at the given level+trigger+tone
 * 3. Filter invalid lines (contain '|' or '\n' — our sync wire constraint)
 * 4. Insert into Room tagged as AI_GENERATED
 * 5. Trigger a phone→watch sync so new messages are available immediately
 */
class AiMessageGenerator(
    private val context: Context,
    private val client: ClaudeApiClient = ClaudeApiClient(ApiKeyStore(context)),
) {

    suspend fun generateBatch(
        level: EscalationLevel,
        trigger: TriggerType,
        tone: MessageTone,
        hourOfDay: Int,
        currentSteps: Int,
        count: Int = 10,
    ): GenerationResult {
        val db = AppDatabase.getDatabase(context)
        val topVoted = db.messageDao().getTopUpvotedTexts(5)

        val userPrompt = Prompts.buildUserPrompt(
            currentSteps,
            hourOfDay,
            level,
            trigger,
            tone,
            topVoted,
            count,
        )

        return when (val result = client.generate(Prompts.SYSTEM_PROMPT, userPrompt)) {
            is GenerationResult.Success -> {
                val valid = result.messages
                    .filter { it.length <= 200 && !it.contains('|') && !it.contains('\n') }
                if (valid.isEmpty()) {
                    Log.w(TAG, "Claude returned no valid messages after filtering")
                    return GenerationResult.Success(emptyList())
                }
                val entities = valid.map { text ->
                    Message(
                        text = text,
                        level = level,
                        triggerType = trigger,
                        tone = tone,
                        source = MessageSource.AI_GENERATED,
                        lastShownTimestamp = 0,
                        isActive = true,
                    )
                }
                db.messageDao().insertAll(entities)
                Log.d(TAG, "Inserted ${entities.size} AI messages; syncing to watch")
                when (val syncResult = PhoneSyncSender(context).syncMessagesToWatch()) {
                    is SyncResult.Success ->
                        Log.d(TAG, "Synced ${syncResult.count} messages to watch")
                    SyncResult.NoMessages ->
                        Log.w(TAG, "Sync had nothing to send despite just inserting $entities.size rows")
                    is SyncResult.Failed ->
                        Log.w(TAG, "Sync to watch failed after AI generation", syncResult.error)
                }
                GenerationResult.Success(valid)
            }
            else -> result
        }
    }

    companion object {
        private const val TAG = "AiMessageGenerator"
    }
}
