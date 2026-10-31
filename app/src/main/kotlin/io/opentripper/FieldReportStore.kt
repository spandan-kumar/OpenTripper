package io.opentripper

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import io.opentripper.core.FieldPlan
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One local bundle links immutable child runs, bounded Maps diagnostics, and live photos. */
class FieldReportStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pod", Context.MODE_PRIVATE)
    fun latestId(): String? = prefs.getString("latest_field_session", null)
    fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(context.filesDir, "field-sessions/$id")
    }
    fun photoFile(id: String, name: String): File {
        require(name.matches(Regex("photo-[a-z0-9-]+\\.jpg")))
        return File(folder(id), name)
    }
    fun create(groups: List<FieldPlan.Group>, includeText: Boolean): String {
        val id = UUID.randomUUID().toString()
        folder(id).mkdirs()
        save(id, JSONObject().put("schemaVersion", 1).put("id", id).put("appVersion", "0.5.0-experimental")
            .put("phoneModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT).put("startedAt", Instant.now().toString())
            .put("drivingSide", prefs.getString("driving_side", "LEFT")).put("phase", "SCREENS").put("groups", JSONArray(groups.map { it.name })).put("includeMapsText", includeText)
            .put("runs", JSONArray()).put("mapsEvents", JSONArray()).put("livePhotos", JSONArray())
            .put("limits", "Stationary evidence only; no automatic visual verdict. Sample Maps replay is synthetic. Live photos may show a changing direction; compare timestamps with the Maps event log."))
        prefs.edit().putString("latest_field_session", id).apply()
        return id
    }
    fun read(id: String): JSONObject? = runCatching {
        JSONObject(AtomicFile(File(folder(id), "suite.json")).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    fun save(id: String, report: JSONObject) {
        report.put("updatedAt", Instant.now().toString())
        val atomic = AtomicFile(File(folder(id).apply { mkdirs() }, "suite.json"))
        val out = atomic.startWrite()
        try { out.write(report.toString(2).toByteArray()); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
    }
    fun update(id: String, change: (JSONObject) -> Unit) {
        val report = read(id) ?: error("Missing field session")
        change(report); save(id, report)
    }
    fun latestForDisplay(): JSONObject? {
        val id = latestId() ?: return null
        val report = read(id) ?: return null
        if (AppState.fieldSessionId != id && report.optString("phase") in setOf("SCREENS", "LIVE_MAPS")) {
            report.put("phase", "INTERRUPTED").put("reason", "Connection service ended; continue unphotographed checks or retry a group")
            save(id, report)
            val runs = report.getJSONArray("runs")
            for (index in 0 until runs.length()) GuidedReportStore(context).interrupt(runs.getString(index), "Field session process ended")
        }
        return report
    }
    fun photographed(id: String): Set<String> {
        val runs = read(id)?.optJSONArray("runs") ?: return emptySet()
        return buildSet {
            for (index in 0 until runs.length()) {
                val steps = GuidedReportStore(context).read(runs.getString(index))?.optJSONArray("steps") ?: continue
                for (row in 0 until steps.length()) {
                    val step = steps.getJSONObject(row)
                    if (!step.isNull("photo") && step.optString("photo").isNotEmpty()) add(step.getString("id"))
                }
            }
        }
    }
    fun event(id: String, value: JSONObject) = update(id) { report ->
        value.put("at", Instant.now().toString())
        val events = report.getJSONArray("mapsEvents")
        if (events.length() >= 120) events.remove(0)
        events.put(value)
    }
    fun archive(id: String): File {
        val report = read(id) ?: error("Missing field session")
        require(report.getString("phase") !in setOf("SCREENS", "LIVE_MAPS")) { "Finish the session before sharing" }
        val zip = File(context.cacheDir, "test-exports").apply { mkdirs() }.resolve("OpenTripper-field-$id.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun bytes(name: String, content: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(content); out.closeEntry() }
            fun photo(name: String, file: File) {
                require(file.isFile && file.length() in 1..20_000_000)
                out.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
            bytes("suite.json", report.toString(2).toByteArray())
            val runs = report.getJSONArray("runs")
            val store = GuidedReportStore(context)
            for (index in 0 until runs.length()) {
                val runId = runs.getString(index)
                val child = store.read(runId) ?: error("Missing child report")
                bytes("runs/$runId/report.json", child.toString(2).toByteArray())
                val steps = child.getJSONArray("steps")
                for (row in 0 until steps.length()) {
                    val name = steps.getJSONObject(row).optString("photo").takeUnless { it.isBlank() || it == "null" } ?: continue
                    photo("runs/$runId/$name", store.photoFile(runId, name))
                }
            }
            val live = report.getJSONArray("livePhotos")
            for (index in 0 until live.length()) {
                val name = live.getJSONObject(index).getString("photo")
                photo("live/$name", photoFile(id, name))
            }
        }
        return zip
    }
}
