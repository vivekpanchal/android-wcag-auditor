package com.a11yauditor.app

import android.util.Base64
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect

data class AuditIssue(
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val elementDescription: String,
    val description: String,
    val suggestedFix: String?,
    val screenshotPng: ByteArray?,
    // Screen-pixel bounds of the flagged element, same coordinate space as
    // screenshotPng — lets the dashboard draw a highlight box on the image.
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

    fun send(packageName: String, screen: String?, issues: List<AuditIssue>) {
        if (issues.isEmpty()) return

        val body = JSONObject().apply {
            put("packageName", packageName)
            put("screen", screen ?: "")
            put("timestamp", System.currentTimeMillis())
            put("issues", JSONArray().apply {
                issues.forEach { issue ->
                    put(JSONObject().apply {
                        put("severity", issue.severity)
                        put("wcagSC", issue.wcagSc)
                        put("wcagLevel", issue.wcagLevel)
                        put("elementDescription", issue.elementDescription)
                        put("description", issue.description)
                        issue.suggestedFix?.let { put("suggestedFix", it) }
                        issue.screenshotPng?.let {
                            put("screenshot", Base64.encodeToString(it, Base64.NO_WRAP))
                        }
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

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Dashboard server might just not be running yet — don't crash the
                // host app's UI thread over it, just log and move on.
                Log.w(TAG, "Failed to send report: ${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }

    companion object {
        private const val TAG = "A11yAuditor.Report"
    }
}
