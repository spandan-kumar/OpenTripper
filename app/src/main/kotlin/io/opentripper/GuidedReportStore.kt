package io.opentripper

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import io.opentripper.core.GuidedRun
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** App-private evidence; only explicitly shared ZIPs are exposed through FileProvider. */
class GuidedReportStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pod", Context.MODE_PRIVATE)
    fun create(): String = UUID.randomUUID().toString().also { id ->
        folder(id).mkdirs(); prefs.edit().putString("latest_guided_run", id).apply()
    }
    fun latestId(): String? = prefs.getString("latest_guided_run", null)
    fun folder(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid report identifier" }
        return File(context.filesDir, "test-runs/$id")
    }
    fun photoFile(id: String, name: String): File {
        require(name.matches(Regex("photo-[a-z0-9-]+\\.jpg"))) { "Invalid photo name" }
        return File(folder(id), name)
    }
    fun save(id: String, run: GuidedRun, events: List<String>) {
        val previous = read(id)
        val rows = JSONArray()
        run.steps.forEachIndexed { index, step ->
            val result = run.results[index]
            rows.put(JSONObject().put("id", step.id).put("expected", step.title)
                .put("maneuver", step.probe.cue.maneuver.name).put("exit", step.probe.cue.exit ?: JSONObject.NULL)
                .put("drivingSide", step.probe.cue.drivingSide.name).put("meters", step.probe.meters)
                .put("nextManeuver", step.probe.next?.maneuver?.name ?: JSONObject.NULL)
                .put("experimental", step.probe.experimental).put("successfulWrites", result.writes)
                .put("firstWriteElapsedMs", result.firstWriteAt?.minus(run.startedAt) ?: JSONObject.NULL)
                .put("photo", result.photo ?: JSONObject.NULL).put("skippedByUser", result.skipped)
                .put("visualResult", "Not automatically evaluated")
                .put("packetHex", step.probe.packet().joinToString(" ") { "%02X".format(it.toInt() and 255) }))
        }
        val report = JSONObject().put("schemaVersion", 1).put("runId", id)
            .put("appVersion", "0.4.0-experimental").put("phoneModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
            .put("startedAt", previous?.optString("startedAt") ?: Instant.now().toString())
            .put("updatedAt", Instant.now().toString()).put("phase", run.phase.name)
            .put("reason", run.reason ?: JSONObject.NULL).put("elapsedMs", SystemClock.elapsedRealtime() - run.startedAt)
            .put("currentStep", run.index).put("steps", rows).put("events", JSONArray(events))
            .put("evidence", "Successful BLE callbacks and user-captured photos; no automatic visual verdict or PIN acceptance decoder")
        write(id, report)
    }
    private fun write(id: String, report: JSONObject) {
        val atomic = AtomicFile(File(folder(id).apply { mkdirs() }, "report.json"))
        val output = atomic.startWrite()
        try { output.write(report.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun read(id: String): JSONObject? = runCatching {
        JSONObject(AtomicFile(File(folder(id), "report.json")).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    fun latestForDisplay(): JSONObject? {
        val id = latestId() ?: return null
        val report = read(id) ?: return null
        if (AppState.guidedRunId != id && report.optString("phase") !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) {
            report.put("phase", "INTERRUPTED").put("reason", "The app process ended; this test was not resumed")
            write(id, report)
        }
        return report
    }
    /** Call off the UI thread after the run has stopped; copies only photos referenced in its report. */
    fun archive(id: String): File {
        val report = read(id) ?: error("No saved report")
        require(report.optString("phase") in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) { "Stop the run before sharing its report" }
        val output = File(context.cacheDir, "test-exports").apply { mkdirs() }
        val zip = File(output, "OpenTripper-test-$id.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { stream ->
            stream.putNextEntry(ZipEntry("report.json")); stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); stream.closeEntry()
            val steps = report.getJSONArray("steps")
            for (index in 0 until steps.length()) {
                val name = steps.getJSONObject(index).optString("photo").takeUnless { it.isBlank() || it == "null" } ?: continue
                val photo = photoFile(id, name)
                require(photo.isFile && photo.length() in 1..20_000_000) { "A recorded photo is missing or invalid" }
                stream.putNextEntry(ZipEntry(name)); photo.inputStream().use { it.copyTo(stream) }; stream.closeEntry()
            }
        }
        return zip
    }
}
