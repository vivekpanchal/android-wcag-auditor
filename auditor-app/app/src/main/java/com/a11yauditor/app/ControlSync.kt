package com.a11yauditor.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pushes locally-initiated state to the dashboard via the local server's
 * HTTP API: a manual Start/Stop tap in the app's own UI (pushControl), and
 * the installed-app list on resume (pushAppList). The reverse direction —
 * picking up control changes made from the dashboard — is handled by
 * DeviceSocket's persistent WebSocket instead, not by this class.
 */
class ControlSync(private val baseUrl: String = "http://localhost:8080") {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Fire-and-forget — best effort, doesn't block the caller on network issues. */
    fun pushControl(targetPackage: String?, auditing: Boolean) {
        val body = JSONObject().apply {
            put("targetPackage", targetPackage ?: JSONObject.NULL)
            put("auditing", auditing)
        }
        val request = Request.Builder()
            .url("$baseUrl/control")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).enqueue(loggingCallback(TAG, "push control"))
    }

    /** Fire-and-forget — lets the dashboard offer a real app picker instead of free text. */
    fun pushAppList(apps: List<InstalledAppInfo>) {
        Log.i(TAG, "pushAppList: sending ${apps.size} apps to $baseUrl/apps")
        val body = JSONArray().apply {
            apps.forEach {
                put(JSONObject().apply {
                    put("appName", it.appName)
                    put("packageName", it.packageName)
                })
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/apps")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).enqueue(loggingCallback(TAG, "push app list"))
    }

    companion object {
        private const val TAG = "A11yAuditor.Control"
    }
}
