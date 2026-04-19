package com.meatsack.shared.db

import androidx.room.TypeConverter
import com.meatsack.shared.constants.EscalationLevel
import com.meatsack.shared.constants.MessageSource
import com.meatsack.shared.constants.MessageTone
import com.meatsack.shared.constants.TriggerType

class Converters {
    @TypeConverter fun fromEscalationLevel(value: EscalationLevel): Int = value.value

    @TypeConverter fun toEscalationLevel(value: Int): EscalationLevel = EscalationLevel.fromValue(value)

    @TypeConverter fun fromTriggerType(value: TriggerType): String = value.name

    @TypeConverter fun toTriggerType(value: String): TriggerType = TriggerType.valueOf(value)

    @TypeConverter fun fromMessageTone(value: MessageTone): String = value.name

    @TypeConverter fun toMessageTone(value: String): MessageTone = MessageTone.valueOf(value)

    @TypeConverter fun fromMessageSource(value: MessageSource): String = value.name

    @TypeConverter fun toMessageSource(value: String): MessageSource = MessageSource.valueOf(value)
}
