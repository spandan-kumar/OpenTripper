package io.opentripper.core

import org.json.JSONArray
import org.json.JSONObject

/** The field kit's optional text capture stays bounded and excludes every other app. */
object FieldDiagnostics {
    fun maps(note: MapsNotification, includeText: Boolean): JSONObject? {
        if (note.packageName != MapsParser.PACKAGE) return null
        val parsed = MapsParser.parse(note)
        return JSONObject().put("type", "mapsNotification").put("ongoing", note.ongoing)
            .put("snapshot", note.snapshot).put("recognized", parsed != null)
            .put("maneuver", parsed?.maneuver?.name ?: JSONObject.NULL)
            .put("meters", parsed?.meters ?: JSONObject.NULL)
            .put("lineLengths", JSONArray(note.lines.take(8).map { it.length }))
            .also { event -> if (includeText) event.put("text", JSONArray(note.lines.take(8).map { it.take(512) })) }
    }
}
