package dev.chudpresence.mobile.link

import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive

/** Called only by explicit local edits, inside the same transaction as the preference write. */
internal fun LinkStoredState.recordEdit(id: String, field: LinkField, value: JsonPrimitive?): LinkStoredState {
    val editId = UUID.randomUUID().toString()
    val fields = pending[id]?.fields.orEmpty().toMutableMap()
    if (!requiresPairing && field in fieldsFor(id) && value != null) fields[field] = LinkFieldEdit(value, editId)
    else fields.remove(field)
    val edits = pending.toMutableMap()
    if (fields.isEmpty()) edits.remove(id)
    else edits[id] = LinkEdit(fields, pending[id]?.baseVersion ?: baselines[id]?.version ?: 0L)
    return copy(pending = edits, localEdits = localEdits + (id to (localEdits[id].orEmpty() + (field to editId))))
}

/** A new selection/source must be adopted before any writes based on the old configuration. */
internal fun LinkStoredState.withDiscovery(selections: Map<String, LinkSelection>): LinkStoredState {
    val reset = selections.filter { (id, selection) ->
        id !in enabled || fieldsFor(id) != selection.fields ||
            (syncVersions[id] != null && syncVersions[id] != selection.version)
    }.keys
    return copy(enabled = selections.keys,
        syncFields = selections.mapValues { it.value.fields }, syncVersions = selections.mapValues { it.value.version },
        baselines = baselines.filterKeys { it in selections && it !in reset },
        pending = pending.filterKeys { it in selections && it !in reset }.mapValues { (id, edit) ->
            edit.copy(fields = edit.fields.filterKeys { it in selections.getValue(id).fields })
        }.filterValues { it.fields.isNotEmpty() })
}

internal data class LinkSettingsMerge(val state: LinkStoredState, val apply: Map<String, Map<LinkField, JsonPrimitive>>)

/** Merge per field so a newer artwork tap cannot prevent adoption of a remote enable/disable. */
internal fun mergeLinkSettings(
    current: LinkStoredState, expected: LinkStoredState, sent: Map<String, LinkEdit>,
    response: LinkSettingsSnapshot, active: Set<String>, localValues: Map<String, Map<LinkField, JsonPrimitive>>,
    choices: Map<String, List<String>>
): LinkSettingsMerge {
    val pending = current.pending.filterKeys { it in active }.toMutableMap()
    val baselines = current.baselines.filterKeys { it in active }.toMutableMap()
    val applied = mutableMapOf<String, Map<LinkField, JsonPrimitive>>()
    response.services.forEach service@{ (id, remote) ->
        if (id !in active || remote.version <= 0) return@service
        val selected = current.fieldsFor(id).intersect(localValues[id].orEmpty().keys)
        var edit = pending[id]
        val previous = sent[id]
        val remaining = edit?.fields.orEmpty().filter { (field, value) ->
            field in selected && (field != LinkField.DISPLAY || value.value.content in choices[id].orEmpty())
        }.toMutableMap()
        val values = mutableMapOf<LinkField, JsonPrimitive>()
        remote.values.filterKeys { it in selected }.forEach setting@{ (field, value) ->
            if (field == LinkField.DISPLAY && value.content !in choices[id].orEmpty()) return@setting
            val localEditId = current.localEdits[id]?.get(field)
            val newer = localEditId != (previous?.fields?.get(field)?.editId ?: expected.localEdits[id]?.get(field))
            val dirty = remaining[field]
            // A tap during discovery can precede learning that this field was selected. Keep
            // that tap with the first usable baseline, even though it could not queue earlier.
            if (newer && dirty == null && localEditId != null) {
                remaining[field] = LinkFieldEdit(localValues.getValue(id).getValue(field), localEditId)
                if (edit == null) edit = LinkEdit(emptyMap(), current.baselines[id]?.version ?: 0L)
            }
            if (newer || (dirty != null && dirty.editId != previous?.fields?.get(field)?.editId)) return@setting
            values[field] = value
            remaining.remove(field)
        }
        if (remaining.isEmpty()) pending.remove(id)
        else {
            val remainingEdit = checkNotNull(edit)
            val ownWriteAccepted = previous != null && id !in response.conflicts &&
                previous.fields.all { (field, change) -> remote.values[field] == change.value }
            val base = if (remainingEdit.baseVersion == 0L || (ownWriteAccepted && remainingEdit.baseVersion == previous!!.baseVersion))
                remote.version else remainingEdit.baseVersion
            pending[id] = remainingEdit.copy(fields = remaining, baseVersion = base)
        }
        applied[id] = values
        baselines[id] = remote.copy(values = remote.values.filterKeys { it in selected })
    }
    return LinkSettingsMerge(current.copy(enabled = active, baselines = baselines, pending = pending), applied)
}
