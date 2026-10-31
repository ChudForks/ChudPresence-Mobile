package dev.chudpresence.mobile.link

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal sealed interface LinkEvent {
    data class Revision(val serverId: String, val revision: Long) : LinkEvent
    data object Revoked : LinkEvent
}

/** Decodes one SSE frame at a time. Callers must also bound their line reader. */
internal class LinkEventDecoder {
    private var eventName = ""
    private val data = mutableListOf<String>()
    private var frameBytes = 0

    /** Accepts a line without its line ending; an optional trailing CR is also tolerated. */
    fun accept(rawLine: String): LinkEvent? {
        val line = rawLine.removeSuffix("\r")
        val lineBytes = line.toByteArray(Charsets.UTF_8).size
        if (lineBytes > MAX_FRAME_BYTES) throw IOException("Link event line is too large.")
        if (line.isEmpty()) return dispatch()

        // Include the line separator so many short lines cannot bypass the frame limit.
        if (lineBytes + 1 > MAX_FRAME_BYTES - frameBytes) throw IOException("Link event is too large.")
        frameBytes += lineBytes + 1
        if (line.startsWith(':')) return null

        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        var value = if (separator < 0) "" else line.substring(separator + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> data.add(value)
        }
        return null
    }

    /** A frame without its terminating blank line is incomplete and must be discarded. */
    fun endOfInput(): LinkEvent? {
        clearFrame()
        return null
    }

    private fun dispatch(): LinkEvent? {
        val name = eventName
        val hasData = data.isNotEmpty()
        val payload = data.joinToString("\n")
        clearFrame()
        if (!hasData) return null
        return when (name) {
            "revision" -> decodeRevision(payload)
            "revoked" -> {
                parseObject(payload)
                LinkEvent.Revoked
            }
            else -> null
        }
    }

    private fun decodeRevision(payload: String): LinkEvent.Revision {
        val root = parseObject(payload)
        val server = root["serverId"] as? JsonPrimitive
        val version = root["revision"] as? JsonPrimitive
        val serverId = server?.takeIf { it.isString }?.content
        val revision = version?.takeUnless { it.isString }?.longOrNull
        if (serverId.isNullOrBlank() || revision == null || revision < 0) {
            throw IOException("Link returned an invalid revision event.")
        }
        return LinkEvent.Revision(serverId, revision)
    }

    private fun parseObject(payload: String): JsonObject = try {
        Json.parseToJsonElement(payload).jsonObject
    } catch (failure: Exception) {
        throw IOException("Link returned an invalid event.", failure)
    }

    private fun clearFrame() {
        eventName = ""
        data.clear()
        frameBytes = 0
    }

    private companion object {
        const val MAX_FRAME_BYTES = 64 * 1024
    }
}
