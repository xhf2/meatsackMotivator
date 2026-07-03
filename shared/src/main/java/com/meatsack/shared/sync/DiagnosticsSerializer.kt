package com.meatsack.shared.sync

/**
 * Wire (de)serializer for the `/diagnostics` Data Layer channel (watch → phone).
 * Each diagnostic line is already a single preformatted string (timestamp + text); the
 * payload is just those lines joined by `\n`. Blank lines are dropped on the way back so a
 * trailing separator never materialises as an empty entry.
 *
 * Part of the on-device diagnostics tool (see docs/debug/triggering-investigation.md).
 * Retained as a standing dev-debugging aid — do not strip.
 */
object DiagnosticsSerializer {
    private const val LINE_SEPARATOR = "\n"

    fun serialize(lines: List<String>): String = lines.joinToString(LINE_SEPARATOR)

    fun deserialize(data: String): List<String> =
        if (data.isEmpty()) emptyList() else data.split(LINE_SEPARATOR).filter { it.isNotEmpty() }
}
