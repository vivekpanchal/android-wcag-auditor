package com.a11yauditor.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect

data class AuditIssue(
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val elementDescription: String,
    val description: String,
    val suggestedFix: String?,
    // Screen-pixel bounds of the flagged element, same coordinate space as
    // the report's screenshot — lets the dashboard draw a highlight box on
    // the image.
    val bounds: Rect? = null,
)

/**
 * Fire-and-forget POST to the local dashboard server. Reached via
 * `adb reverse tcp:8080 tcp:8080`, so from the device's point of view the
 * server really is on localhost — no device IP wrangling needed.
 */
class ReportSender(private val serverUrl: String = "http://localhost:8080/report") {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // screenshotPng is one whole-screen capture shared by every issue found in
    // the same audit pass — sent once at the report level instead of once per
    // issue, since duplicating a multi-hundred-KB PNG N times risked crossing
    // the server's request-body limit on screens with several issues.
    fun send(packageName: String, screen: String?, issues: List<AuditIssue>, screenshotPng: ByteArray?) {
        if (issues.isEmpty()) return

        val body = JSONObject().apply {
            put("packageName", packageName)
            put("screen", screen ?: "")
            put("timestamp", System.currentTimeMillis())
            screenshotPng?.let { put("screenshot", Base64.encodeToString(it, Base64.NO_WRAP)) }
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

        val request = Request.Builder()
            .url(serverUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(loggingCallback(TAG, "send report"))
    }

    companion object {
        private const val TAG = "A11yAuditor.Report"
    }
}
