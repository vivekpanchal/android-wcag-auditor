package com.a11yauditor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON encode/decode for the server's /ws/device protocol (see
 * server/src/deviceSocket.js) -- no OkHttp or Android framework types, so
 * it's testable under plain JUnit like WcagMapping. DeviceSocket (the
 * actual WebSocket wrapper) is the only caller; kept separate so the
 * message shapes can be verified without a live socket.
 */
object DeviceProtocol {

    data class RemoteControl(val targetPackage: String?, val auditing: Boolean)

    /** screenshotBase64 should already be encoded -- this class has no Android dependency. */
    fun buildReportMessage(
        packageName: String,
        screen: String?,
        timestamp: Long,
        issues: List<AuditIssue>,
        screenshotBase64: String?,
    ): String {
        val body = JSONObject().apply {
            put("type", "report")
            put("packageName", packageName)
            put("screen", screen ?: "")
            put("timestamp", timestamp)
            screenshotBase64?.let { put("screenshot", it) }
            put("issues", JSONArray().apply {
                issues.forEach { issue ->
                    put(JSONObject().apply {
                        put("severity", issue.severity)
                        put("wcagSC", issue.wcagSc)
                        put("wcagLevel", issue.wcagLevel)
                        put("elementDescription", issue.elementDescription)
                        put("description", issue.description)
                        issue.suggestedFix?.let { put("suggestedFix", it) }
                        issue.bounds?.let {
                            put("bounds", JSONObject().apply {
                                put("x", it.left)
                                put("y", it.top)
                                put("width", it.width)
                                put("height", it.height)
                            })
                        }
                    })
                }
            })
        }
        return body.toString()
    }

    /** Null for anything that isn't a well-formed control message -- caller ignores it. */
    fun parseControlMessage(text: String): RemoteControl? = try {
        val json = JSONObject(text)
        if (json.optString("type") != "control") {
            null
        } else {
            val control = json.getJSONObject("control")
            // org.json's optString() returns the literal string "null" (not
            // Kotlin null) when the field holds JSON null -- must check isNull first.
            val targetPackage = if (control.isNull("targetPackage")) null
                else control.optString("targetPackage").ifBlank { null }
            RemoteControl(targetPackage = targetPackage, auditing = control.optBoolean("auditing", false))
        }
    } catch (e: Exception) {
        null
    }
}
