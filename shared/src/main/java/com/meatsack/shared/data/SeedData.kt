package com.meatsack.shared.data

import android.content.Context
import com.meatsack.shared.model.Message

/**
 * Supplies the factory-default pre-written insults seeded into the Room DB on
 * first launch. Content lives in the editable asset insults.json (loaded via
 * [InsultLoader]); after seeding, the DB is the source of truth.
 */
object SeedData {

    fun getPreWrittenMessages(context: Context): List<Message> =
        InsultLoader.load(context)
}
