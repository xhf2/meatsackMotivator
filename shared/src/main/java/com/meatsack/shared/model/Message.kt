package com.meatsack.shared.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val level: EscalationLevel,
    val triggerType: TriggerType,
    val tone: MessageTone,
    val source: MessageSource,
    val votesUp: Int = 0,
    val votesDown: Int = 0,
    val lastShownTimestamp: Long = 0,
    val isActive: Boolean = true
)
