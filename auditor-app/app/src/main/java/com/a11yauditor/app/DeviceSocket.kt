package com.a11yauditor.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface DeviceSocketListener {
    fun onControl(targetPackage: String?, auditing: Boolean)
}

/**
 * Persistent WebSocket to the local server's /ws/device (see
 * server/src/deviceSocket.js), replacing the old poll-based control sync
 * (ControlSync.fetchControl(), GET /control every 3s) and the per-report
 * POST (the old ReportSender) with one connection: control is pushed down
 * the instant the dashboard changes it, and issue reports go up as
 * they're found. Reconnects with backoff if the server isn't up yet or
 * the connection drops — same degrade-gracefully behavior the HTTP paths
 * it replaces already had; a report sent while disconnected is simply
 * dropped, same as the old ReportSender's fire-and-forget POST would be
 * on a network failure.
 */
class DeviceSocket(
    private val scope: CoroutineScope,
    private val listener: DeviceSocketListener,
    private val baseUrl: String = "ws://localhost:8080",
) {
    private val client = OkHttpClient()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = true
    private var reconnectJob: Job? = null

    fun connect() {
        if (!stopped) return
        stopped = false
        openSocket()
    }

    fun close() {
        stopped = true
        reconnectJob?.cancel()
        socket?.close(NORMAL_CLOSURE_CODE, "service stopping")
        socket = null
    }

    fun sendReport(packageName: String, screen: String?, issues: List<AuditIssue>, screenshotPng: ByteArray?) {
        if (issues.isEmpty()) return
        val ws = socket
        if (ws == null) {
            Log.w(TAG, "sendReport: not connected, dropping ${issues.size} issue(s)")
            return
        }
        val screenshotBase64 = screenshotPng?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val message = DeviceProtocol.buildReportMessage(
            packageName = packageName,
            screen = screen,
            timestamp = System.currentTimeMillis(),
            issues = issues,
            screenshotBase64 = screenshotBase64,
        )
        ws.send(message)
    }

    private fun openSocket() {
        if (stopped) return
        val request = Request.Builder().url("$baseUrl$DEVICE_PATH").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "device socket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val control = DeviceProtocol.parseControlMessage(text) ?: return
                listener.onControl(control.targetPackage, control.auditing)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "device socket closed: $code $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Covers "server not up yet" (connection refused) and a
                // mid-session drop (Wi-Fi/USB blip, adb reverse dropped)
                // alike — either way, keep retrying rather than giving up
                // the audit session over a transient failure.
                Log.w(TAG, "device socket failed: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        socket = null
        if (stopped) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!stopped) openSocket()
        }
    }

    companion object {
        private const val TAG = "A11yAuditor.DeviceSocket"
        private const val DEVICE_PATH = "/ws/device"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val NORMAL_CLOSURE_CODE = 1000
    }
}
