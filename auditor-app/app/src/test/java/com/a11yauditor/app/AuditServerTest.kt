package com.a11yauditor.app

import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: AuditStore
    private lateinit var control: ControlStore
    private lateinit var deps: ServerDeps

    private val assets = mapOf("index.html" to "<h1>dash</h1>", "assets/a.js" to "x")

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        store = AuditStore(app, null, tmp.newFolder("shots"))
        control = ControlStore(app.getSharedPreferences(AuditorAccessibilityService.PREFS_NAME, 0))
        deps = deps()
    }

    private fun deps(apps: () -> List<AppEntry> = { listOf(AppEntry("Ex", "com.ex")) }) = ServerDeps(
        store, control, "t0k", apps,
        { path -> assets[path]?.let { ByteArrayInputStream(it.toByteArray()) } },
        // Short enough that an open stream ends soon after the test drops it, long enough not to interleave.
        heartbeatMs = 1_000,
    )

    private val issue = AuditIssue(
        "serious", "1.4.3", "AA", "Button b", "d",
        WcagMapping.Fix("S", "v", "c", "https://d"), "views", Rect(1, 2, 11, 22),
    )

    private fun HttpRequestBuilder.authed() = header(HttpHeaders.Authorization, "Bearer t0k")

    private fun serve(d: ServerDeps = deps, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication { application { auditModule(d) }; block() }

    @Test fun `api routes require the bearer token`() = serve {
        for (path in listOf("/api/v1/issues", "/api/v1/control", "/api/v1/screenshots/x.webp", "/api/v1/events")) {
            assertEquals(path, HttpStatusCode.Unauthorized, client.get(path).status)
        }
    }

    @Test fun `a cookie alone is refused`() = serve {
        val res = client.get("/api/v1/issues") { header(HttpHeaders.Cookie, "a11y_session=t0k") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test fun `the auth route is gone and sets no cookie`() = serve {
        val res = client.post("/api/v1/auth") { setBody("""{"token":"t0k"}""") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(null, res.headers[HttpHeaders.SetCookie])
    }

    @Test fun `issues returns recorded issues with screenshot urls`() = serve {
        deps.recordReport("p", "S", 1, ByteArray(4), listOf(issue))
        val arr = JSONArray(client.get("/api/v1/issues") { authed() }.bodyAsText())
        assertEquals(1, arr.length())
        val url = arr.getJSONObject(0).getString("screenshotUrl")
        assertTrue(url.startsWith("/api/v1/screenshots/"))
        val shot = client.get(url) { authed() }
        assertEquals(HttpStatusCode.OK, shot.status)
        assertEquals(ContentType.parse("image/webp"), shot.contentType())
        val missing = client.get("/api/v1/screenshots/00000000-0000-0000-0000-000000000000.webp") { authed() }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    // Streams run on the real CIO engine: testApplication never hands a streaming response to the client
    // until the handler finishes, so an open SSE stream hangs it.
    private fun withServer(block: (port: Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = AuditServer(deps, port)
        assertTrue(server.start())
        try { block(port) } finally { server.stop() }
    }

    private class Raw(val socket: Socket, val reader: BufferedReader) {
        /** Next payload line; skips blank lines and chunked-encoding size lines. */
        fun line(): String {
            while (true) {
                val l = reader.readLine() ?: error("stream closed")
                if (l.isNotEmpty() && !l.matches(Regex("[0-9a-fA-F]+"))) return l
            }
        }
    }

    private fun send(port: Int, method: String, path: String, headers: String = "Authorization: Bearer t0k\r\n"): Raw {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        socket.getOutputStream().write(
            "$method $path HTTP/1.1\r\nHost: localhost\r\n$headers\r\n".toByteArray()
        )
        socket.getOutputStream().flush()
        return Raw(socket, BufferedReader(InputStreamReader(socket.getInputStream())))
    }

    /** Opens the event stream and returns it positioned after the response headers. */
    private fun openStream(port: Int): Raw = send(port, "GET", "/api/v1/events").also {
        assertTrue(it.line().contains("200"))
        while (it.reader.readLine()!!.isNotEmpty()) { /* skip headers */ }
    }

    @Test fun `recordReport emits an issue event on the stream`() = withServer { port ->
        val stream = openStream(port)
        stream.socket.use {
            assertEquals(": connected", stream.line())
            deps.recordReport("p", "S", 1, null, listOf(issue))
            assertEquals("event: issue", stream.line())
            assertTrue(stream.line().startsWith("data: {"))
        }
    }

    @Test fun `delete clears the store and emits clear`() = withServer { port ->
        deps.recordReport("p", "S", 1, null, listOf(issue))
        val stream = openStream(port)
        stream.socket.use {
            assertEquals(": connected", stream.line())
            send(port, "DELETE", "/api/v1/issues").socket.use { d ->
                assertTrue(Raw(d, BufferedReader(InputStreamReader(d.getInputStream()))).line().contains("204"))
            }
            assertTrue(store.issues().isEmpty())
            assertEquals("event: clear", stream.line())
        }
    }

    @Test fun `dropped client releases its subscription`() = withServer { port ->
        val stream = openStream(port)
        assertEquals(": connected", stream.line())
        assertEquals(1, deps.events.subscriptionCount.value)
        stream.socket.close()
        // Measured ~4 beats: the first writes after the FIN are absorbed before the RST makes one fail.
        val deadline = System.currentTimeMillis() + deps.heartbeatMs * 8
        while (deps.events.subscriptionCount.value > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, deps.events.subscriptionCount.value)
    }

    @Test fun `post control validates the body`() = serve {
        val before = control.get()
        for (body in listOf("not json", """{"auditing":"yes"}""", """{"targetPackage":null,"auditing":true}""")) {
            assertEquals(body, HttpStatusCode.BadRequest, client.post("/api/v1/control") { authed(); setBody(body) }.status)
            assertEquals(before, control.get())
        }
        val ok = client.post("/api/v1/control") { authed(); setBody("""{"targetPackage":"com.ex","auditing":true}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(Control("com.ex", true), control.get())
    }

    @Test fun `apps lists installed apps`() = serve {
        assertEquals("""[{"appName":"Ex","packageName":"com.ex"}]""", client.get("/api/v1/apps") { authed() }.bodyAsText())
    }

    @Test fun `a throwing handler is a 500 and the server keeps serving`() = serve(deps(apps = { error("boom") })) {
        assertEquals(HttpStatusCode.InternalServerError, client.get("/api/v1/apps") { authed() }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/issues") { authed() }.status)
    }

    @Test fun `static serves index at root and refuses traversal`() = serve {
        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertEquals("<h1>dash</h1>", root.bodyAsText())
        val js = client.get("/assets/a.js")
        assertEquals(HttpStatusCode.OK, js.status)
        assertTrue(js.contentType().toString(), js.contentType()!!.match(ContentType.Application.JavaScript) ||
            js.contentType()!!.match(ContentType.Text.JavaScript))
        for (path in listOf("/../audit.db", "/assets/../../x", "/nope.js")) {
            assertEquals(path, HttpStatusCode.NotFound, client.get(path).status)
        }
    }

    @Test fun `a failed start leaves no uncaught exception`() {
        val port = ServerSocket(0).use { it.localPort }
        val first = AuditServer(deps, port)
        val second = AuditServer(deps, port)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> seen.add(t) }
        try {
            assertTrue(first.start())
            assertFalse(second.start())
            Thread.sleep(1_500) // the CIO accept job fails on its own thread, after start() returned
            assertEquals(emptyList<Throwable>(), seen.toList())
            // Contained by our handler, not merely swallowed.
            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().any { it.msg == "server coroutine failed" })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            second.stop(); first.stop()
        }
    }

    @Test fun `start returns false when the port is taken`() {
        val port = ServerSocket(0).use { it.localPort }
        val first = AuditServer(deps, port)
        val second = AuditServer(deps, port)
        try {
            assertTrue(first.start())
            assertFalse(second.start())
        } finally {
            second.stop(); first.stop()
        }
    }
}
