package com.a11yauditor.app

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.decodeURLPart
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

private const val TAG = "A11yAuditor.Server"
private const val SESSION_COOKIE = "a11y_session"

class ServerDeps(
    val store: AuditStore,
    val control: ControlStore,
    val token: String,
    val apps: () -> List<AppEntry>,
    val asset: (String) -> InputStream?,
    /** Idle SSE streams write a comment this often; a failed write is how a gone client is noticed. */
    val heartbeatMs: Long = 15_000,
) {
    /** SSE frames for every connected dashboard; slow subscribers lose the oldest frames. */
    val events = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun recordReport(
        packageName: String, screen: String?, timestamp: Long, screenshot: ByteArray?, issues: List<AuditIssue>,
    ): List<StoredIssue> = store.addReport(packageName, screen, timestamp, screenshot, issues).onEach {
        events.tryEmit(ApiJson.sse("issue", ApiJson.issue(it).toString()))
    }

    fun publishControl() {
        events.tryEmit(ApiJson.sse("control", ApiJson.control(control.get())))
    }

    fun tokenMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    json: String, status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(json, ContentType.Application.Json, status)

fun Application.auditModule(deps: ServerDeps) {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause // server stopping or client gone, not a failure
            Log.e(TAG, "request ${call.request.path()} failed", cause)
            call.respondJson("""{"error":"internal"}""", HttpStatusCode.InternalServerError)
        }
    }

    // Runs before routing and before any body is read. Segments are split and decoded the
    // way routing does, so "//api/v1/x" or "%61pi/v1/x" can't reach an API route unchecked.
    intercept(ApplicationCallPipeline.Plugins) {
        val segments = call.request.path().split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
        val isApi = segments.size >= 2 && segments[0] == "api" && segments[1] == "v1"
        val isAuth = segments == listOf("api", "v1", "auth") && call.request.httpMethod == HttpMethod.Post
        if (isApi && !isAuth && !deps.tokenMatches(call.request.cookies[SESSION_COOKIE])) {
            call.respondJson("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
            finish()
        }
    }

    routing {
        route("/api/v1") {
            post("/auth") {
                val token = try { JSONObject(call.receiveText()).optString("token") } catch (e: JSONException) { null }
                if (!deps.tokenMatches(token)) return@post call.respond(HttpStatusCode.Unauthorized)
                call.response.cookies.append(
                    Cookie(
                        SESSION_COOKIE, deps.token, encoding = CookieEncoding.RAW, path = "/", httpOnly = true,
                        extensions = mapOf("SameSite" to "Strict"),
                    )
                )
                call.respond(HttpStatusCode.NoContent)
            }
            get("/issues") { call.respondJson(ApiJson.issues(deps.store.issues())) }
            delete("/issues") {
                deps.store.clear()
                deps.events.tryEmit(ApiJson.sse("clear", "{}"))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/screenshots/{name}") {
                val file = deps.store.screenshot(call.parameters["name"]!!)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(LocalFileContent(file, ContentType("image", "webp")))
            }
            get("/control") { call.respondJson(ApiJson.control(deps.control.get())) }
            post("/control") {
                val c = ApiJson.parseControl(call.receiveText())
                if (c == null || (c.auditing && c.targetPackage == null)) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                // The service's prefs listener publishes the SSE control event.
                deps.control.set(c)
                call.respondJson(ApiJson.control(c))
            }
            get("/apps") { call.respondJson(ApiJson.apps(deps.apps())) }
            get("/events") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                val heartbeat = flow { while (true) { delay(deps.heartbeatMs); emit(": ping\n\n") } }
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    // Emit from onSubscription so nothing published between connect and collect is lost.
                    merge(deps.events.onSubscription { emit(": connected\n\n") }, heartbeat)
                        .collect { writeStringUtf8(it); flush() }
                }
            }
        }
        get("/{path...}") {
            val segments = call.parameters.getAll("path").orEmpty()
            if (".." in segments) return@get call.respond(HttpStatusCode.NotFound)
            val path = segments.joinToString("/").ifEmpty { "index.html" }
            val stream = deps.asset(path) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondOutputStream(ContentType.defaultForFilePath(path)) { stream.use { it.copyTo(this) } }
        }
    }
}

/** Dashboard server, bound to loopback only. */
class AuditServer(private val deps: ServerDeps, private val port: Int = 8080) {
    private var engine: ApplicationEngine? = null
    private var scope: CoroutineScope? = null

    /** False if the server could not start (e.g. port taken); auditing carries on without it. */
    fun start(): Boolean {
        // CIO's accept job runs on this scope; without a handler its BindException kills the process.
        val s = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "server coroutine failed", t) })
        val e = s.embeddedServer(CIO, host = "127.0.0.1", port = port) { auditModule(deps) }
        return try {
            e.start(wait = false)
            // CIO binds asynchronously; awaiting the connectors surfaces a bind failure here.
            runBlocking { e.resolvedConnectors() }
            engine = e
            scope = s
            true
        } catch (ex: Exception) {
            Log.e(TAG, "server failed to start on port $port", ex)
            e.stop(0, 0)
            s.cancel()
            false
        }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        scope?.cancel()
        scope = null
    }
}
