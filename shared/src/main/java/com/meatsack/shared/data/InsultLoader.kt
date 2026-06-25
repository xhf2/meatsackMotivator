package com.meatsack.shared.data

import android.content.Context
import com.meatsack.shared.model.Message
import kotlinx.serialization.json.Json

/**
 * Loads the bundled insults.json seed asset into [Message]s.
 *
 * [parse] is pure (no Android deps) so it can be unit-tested on the plain JVM;
 * [load] is the thin AssetManager wrapper used at runtime by the app seeders.
 * Any failure (missing asset, malformed JSON, unknown enum name, missing field)
 * propagates — it is a build-author error, caught by the seeders' try/catch as a
 * runtime backstop and prevented at build time by InsultLoaderFileTest.
 */
object InsultLoader {

    const val ASSET_NAME = "insults.json"

    private val json = Json { ignoreUnknownKeys = false }

    fun parse(jsonText: String): List<Message> =
        json.decodeFromString<List<InsultDto>>(jsonText).map { it.toMessage() }

    fun load(context: Context): List<Message> =
        parse(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
}
