package com.a11yauditor.app

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Fire-and-forget POST callback used by ControlSync's HTTP calls (the
 * device's own locally-initiated writes — /ws/device's WebSocket traffic
 * uses its own WebSocketListener, not this).
 * OkHttp only calls onFailure for network-level errors — a non-2xx response
 * (validation failure, 413 too-large, etc.) lands in onResponse and would
 * otherwise be closed and forgotten, making delivery failures invisible.
 */
fun loggingCallback(tag: String, label: String): Callback = object : Callback {
    override fun onFailure(call: Call, e: IOException) {
        Log.w(tag, "$label failed: ${e.message}")
    }

    override fun onResponse(call: Call, response: Response) {
        if (!response.isSuccessful) {
            Log.w(tag, "$label failed: HTTP ${response.code}")
        }
        response.close()
    }
}
