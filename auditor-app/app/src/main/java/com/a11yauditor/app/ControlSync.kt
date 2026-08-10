package com.a11yauditor.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class RemoteControl(val targetPackage: String?, val auditing: Boolean)

/**
 * Syncs desired auditing state with the dashboard via the local server's
 * /control endpoint. The device can only ever reach the server (adb reverse
 * is one-directional), so control flows as: dashboard POSTs desired state,
 * device polls GET to pick it up.
 */
class ControlSync(private val baseUrl: String = "http://localhost:8080") {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Blocking GET — call off the main thread. Returns null if the server is unreachable. */
    fun fetchControl(): RemoteControl? {
        val request = Request.Builder().url("$baseUrl/control").get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = JSONObject(response.body?.string() ?: return null)
                // org.json's optString() returns the literal string "null" (not
                // Kotlin null) when the field holds JSON null — must check isNull first.
                val targetPackage = if (body.isNull("targetPackage")) null else body.optString("targetPackage").ifBlank { null }
                RemoteControl(
                    targetPackage = targetPackage,
                    auditing = body.optBoolean("auditing", false),
                )
            }
        } catch (e: Exception) {
            // Covers IOException (server unreachable) and org.json.JSONException
            // (malformed/truncated body on a flaky connection) alike — this runs
            // in an uncaught-exception-crashes-the-app poll loop, so any failure
            // here must degrade to "try again next poll," never propagate.
            Log.w(TAG, "fetchControl failed: ${e.message}")
            null
        }
    }

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
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "pushControl failed: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
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
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.w(TAG, "pushAppList failed: ${e.message}")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.i(TAG, "pushAppList response: ${response.code}")
                response.close()
            }
        })
    }

    companion object {
        private const val TAG = "A11yAuditor.Control"
    }
}
