package dev.gpsarrow.data

import android.content.Context
import android.util.Log
import dev.gpsarrow.core.Destination
import dev.gpsarrow.core.DestinationEdit
import dev.gpsarrow.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Saved destinations, in a single JSON file.
 *
 * Deliberately not Room and not DataStore: the v0 data model is a flat list of a few hundred
 * points at most, this has zero dependencies and zero schema migration machinery, and the file
 * is human-readable so users can back it up and edit it themselves. Revisit if v3 adds tracks.
 *
 * Writes are atomic (temp file + rename) so a kill mid-write can't corrupt the list.
 */
class DestinationStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _destinations.value = runCatching { read() }.getOrElse {
            Log.w(TAG, "Could not read $FILE_NAME, starting empty", it)
            emptyList()
        }
    }

    suspend fun add(
        name: String,
        position: LatLon,
        note: String? = null,
        source: String = "manual",
        /** Accuracy of the fix this came from; null when it was typed, pasted or imported. */
        accuracyMeters: Float? = null,
    ): Destination = withContext(Dispatchers.IO) {
        val destination = Destination(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Unnamed" },
            position = position,
            note = note,
            createdAtMillis = System.currentTimeMillis(),
            source = source,
            accuracyMeters = accuracyMeters,
        )
        _destinations.value = _destinations.value + destination
        write(_destinations.value)
        destination
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        _destinations.value = _destinations.value.map {
            if (it.id == id) it.copy(name = name) else it
        }
        write(_destinations.value)
    }

    /**
     * Apply the editor's name and position. Keeps id, createdAt, the note, the star and
     * everything else the editor does not present — see [DestinationEdit.applied], which holds
     * the rules and the test that pins them.
     *
     * There is deliberately **no `note` parameter**. It used to take one, defaulted to null, and
     * assign it unconditionally; since nothing in the app edits notes, every edit erased the
     * note it was not editing. Adding note editing means adding a way to say "unchanged" that is
     * distinct from "cleared", not restoring a defaulted parameter.
     */
    suspend fun update(
        id: String,
        name: String,
        position: LatLon,
    ): Destination? = withContext(Dispatchers.IO) {
        var updated: Destination? = null
        _destinations.value = _destinations.value.map {
            if (it.id != id) {
                it
            } else {
                DestinationEdit.applied(it, name, position).also { edited -> updated = edited }
            }
        }
        write(_destinations.value)
        updated
    }

    suspend fun setFavourite(id: String, favourite: Boolean) = withContext(Dispatchers.IO) {
        _destinations.value = _destinations.value.map {
            if (it.id == id) it.copy(isFavourite = favourite) else it
        }
        write(_destinations.value)
    }

    /** Stamped whenever a destination becomes the navigation target. Drives "recently used". */
    suspend fun markUsed(id: String) = withContext(Dispatchers.IO) {
        _destinations.value = _destinations.value.map {
            if (it.id == id) it.copy(lastUsedAtMillis = System.currentTimeMillis()) else it
        }
        write(_destinations.value)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        _destinations.value = _destinations.value.filterNot { it.id == id }
        write(_destinations.value)
    }

    /** Puts a deleted destination back, id and all, so undo restores rather than re-creates. */
    suspend fun restore(destination: Destination) = withContext(Dispatchers.IO) {
        if (_destinations.value.none { it.id == destination.id }) {
            _destinations.value = _destinations.value + destination
            write(_destinations.value)
        }
    }

    fun byId(id: String?): Destination? = _destinations.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- io

    private fun read(): List<Destination> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    Destination(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "Unnamed"),
                        position = LatLon(o.getDouble("lat"), o.getDouble("lon")),
                        note = o.optString("note").takeIf { it.isNotBlank() },
                        createdAtMillis = o.optLong("createdAt", 0L),
                        source = o.optString("source", "manual"),
                        isFavourite = o.optBoolean("favourite", false),
                        // Absent in files written by older versions; null means "never used".
                        lastUsedAtMillis = o.optLong("lastUsed", 0L).takeIf { it > 0L },
                        // Also absent in older files. Null means "we don't know how good this
                        // point is", which the UI must render as silence, never as ±0 m.
                        accuracyMeters = if (o.has(KEY_ACCURACY)) {
                            o.getDouble(KEY_ACCURACY).toFloat()
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    private fun write(list: List<Destination>) {
        val array = JSONArray()
        list.forEach { d ->
            array.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("lat", d.position.lat)
                    put("lon", d.position.lon)
                    d.note?.let { put("note", it) }
                    put("createdAt", d.createdAtMillis)
                    put("source", d.source)
                    if (d.isFavourite) put("favourite", true)
                    d.lastUsedAtMillis?.let { put("lastUsed", it) }
                    // Omitted rather than written as 0 when unknown: absent and "perfect" must
                    // not collapse to the same thing on the way back in.
                    d.accuracyMeters?.let { put(KEY_ACCURACY, it.toDouble()) }
                },
            )
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(array.toString(2))
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString(2))
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- import / export

    /** Minimal GPX 1.1 waypoint export — readable by every desktop mapping tool. */
    fun toGpx(list: List<Destination> = _destinations.value): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="GPS Baibbat" xmlns="http://www.topografix.com/GPX/1/1">""",
        )
        list.forEach { d ->
            appendLine("""  <wpt lat="${d.position.lat}" lon="${d.position.lon}">""")
            appendLine("""    <name>${d.name.xmlEscaped()}</name>""")
            d.note?.let { appendLine("""    <desc>${it.xmlEscaped()}</desc>""") }
            appendLine("""  </wpt>""")
        }
        appendLine("""</gpx>""")
    }

    /** Tolerant GPX waypoint import: regex rather than a parser, because GPX in the wild varies. */
    fun parseGpx(xml: String): List<Pair<String, LatLon>> {
        val wpt = Regex(
            """<wpt[^>]*lat\s*=\s*"(-?[\d.]+)"[^>]*lon\s*=\s*"(-?[\d.]+)"[^>]*>(.*?)</wpt>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val name = Regex("""<name>(.*?)</name>""", RegexOption.DOT_MATCHES_ALL)
        return wpt.findAll(xml).mapNotNull { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val label = name.find(m.groupValues[3])?.groupValues?.get(1)?.trim() ?: "Imported"
            label to LatLon(lat, lon)
        }.toList()
    }

    private fun String.xmlEscaped() = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val FILE_NAME = "destinations.json"
        const val TAG = "DestinationStore"
        const val KEY_ACCURACY = "accuracyM"
    }
}
