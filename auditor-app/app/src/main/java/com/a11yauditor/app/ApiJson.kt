package com.a11yauditor.app

import org.json.JSONArray
import org.json.JSONObject

data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

data class StoredIssue(
    val id: Long,
    val packageName: String,
    val screen: String?,
    val timestamp: Long,
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val elementDescription: String,
    val description: String,
    val suggestedFix: String?,
    val bounds: Bounds?,
    val screenshotFile: String?,
)

data class Control(val targetPackage: String?, val auditing: Boolean)

data class AppEntry(val appName: String, val packageName: String)

/** Wire shapes for the embedded server's REST + SSE API (pure org.json, JVM-testable). */
object ApiJson {

    /** JSON string stored in the suggestedFix field; the dashboard parses it. */
    fun suggestedFix(fix: WcagMapping.Fix?, framework: String): String? = fix?.let {
        JSONObject().apply {
            put("summary", it.summary)
            put("views", it.views)
            put("compose", it.compose)
            put("docUrl", it.docUrl)
            put("framework", framework)
        }.toString()
    }

    fun issue(i: StoredIssue): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("packageName", i.packageName)
        put("screen", i.screen ?: JSONObject.NULL)
        put("timestamp", i.timestamp)
        put("severity", i.severity)
        put("wcagSC", i.wcagSc)
        put("wcagLevel", i.wcagLevel)
        put("elementDescription", i.elementDescription)
        put("description", i.description)
        put("suggestedFix", i.suggestedFix ?: JSONObject.NULL)
        put("bounds", i.bounds?.let {
            JSONObject().put("x", it.x).put("y", it.y).put("width", it.width).put("height", it.height)
        } ?: JSONObject.NULL)
        put("screenshotUrl", i.screenshotFile?.let { "/api/v1/screenshots/$it" } ?: JSONObject.NULL)
    }

    fun issues(list: List<StoredIssue>): String =
        JSONArray().apply { list.forEach { put(issue(it)) } }.toString()

    fun control(c: Control): String = JSONObject()
        .put("targetPackage", c.targetPackage ?: JSONObject.NULL)
        .put("auditing", c.auditing)
        .toString()

    fun apps(list: List<AppEntry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("appName", it.appName).put("packageName", it.packageName)) }
    }.toString()

    /** Null for invalid JSON or a missing/non-boolean `auditing`. */
    fun parseControl(body: String): Control? = try {
        val json = JSONObject(body)
        val auditing = json.opt("auditing") as? Boolean
        // optString would return the literal "null" for a JSON null.
        val target = if (json.isNull("targetPackage")) null else json.optString("targetPackage").ifBlank { null }
        auditing?.let { Control(target, it) }
    } catch (e: org.json.JSONException) {
        null
    }

    fun sse(event: String, data: String): String = "event: $event\ndata: $data\n\n"
}
