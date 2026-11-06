# Phase 2: Embedded Server at Feature Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the backend into the APK (Ktor on `127.0.0.1:8080`, SQLite store, token-gated API, SSE), point the dashboard at it with relative URLs and client-side export, then delete the Node server.

**Architecture:** The accessibility service owns an `AuditStore` (SQLiteOpenHelper + WebP files) and a Ktor CIO server whose routes live in one `Application.auditModule(ServerDeps)` function, tested with Ktor `testApplication` under Robolectric. Reports go service → `ServerDeps.recordReport` → store + SSE `issue` event; control stays in the existing SharedPreferences, so the dashboard and the phone UI write the same keys and a prefs listener in the service emits SSE `control`. The dashboard build is copied into APK assets by a Gradle task and served for every non-API path.

**Tech Stack:** Kotlin 1.9.24, AGP 8.5.2, Ktor 2.3.12 (server-core, server-cio, server-status-pages, server-test-host), Robolectric 4.13, JUnit 4, org.json; React 19 + Vite 8 + Vitest 3.

**Spec:** `docs/superpowers/specs/2026-10-09-live-audit-embedded-server-design.md` — this plan covers §11 phase 2 (§6, §7 minus Phase 3 items, §8, §9 server/dashboard rows). Phase 3 (fingerprints, statuses, tabs, Ignore, progress strip) is out of scope.

## Status (2026-11-06)

- Tasks 1–2: committed (`a99d1db`, `731e568`, `35525bc`).
- Task 3: code written, uncommitted. **`AuditServerTest` hangs** and still contains `DBG` printlns; see Task 3 Step 4.
- Tasks 4–9: not started. Each carries amendments from a re-check against the current code, marked **Amended 2026-11-06**.

## Global Constraints

- **Same issue semantics as today:** every reported issue becomes one row; no dedup, no statuses, no occurrences (Phase 3).
- Android: `minSdk = 26`, `compileSdk = 34`, Kotlin `1.9.24`, JVM target 17. Ktor stays on **2.3.12** — Ktor 3 is built with Kotlin 2 and this module compiles with 1.9.24.
- New runtime deps: `io.ktor:ktor-server-core`, `ktor-server-cio`, `ktor-server-status-pages` (all 2.3.12). New test deps: `io.ktor:ktor-server-test-host:2.3.12`, `org.robolectric:robolectric:4.13`. Remove `okhttp` (no remaining users after Task 4). No new dashboard deps.
- Server binds `127.0.0.1` port `8080` only. Starts in `onServiceConnected`, stops in `onUnbind`/`onDestroy`. A failed start never stops auditing.
- API base `/api/v1`. Auth is `Authorization: Bearer <token>`, kept in `sessionStorage` (`a11y_token`). Every `/api/v1/*` route returns 401 without it; static dashboard paths need no token. No cookie, because cookies have no port isolation (RFC 6265 §8.5) and one would reach any app on another localhost port. No CORS headers, so a cross-origin `Authorization` header fails preflight. Accepted residual risk: an app holding port 8080 while the service is off could read `sessionStorage` if that tab is reloaded. This supersedes the cookie and `POST /api/v1/auth` steps in the tasks below.
- Token: 32 random bytes from `SecureRandom`, URL-safe Base64 without padding, created once per install, prefs key `dashboard_token`. Compare with `MessageDigest.isEqual`.
- Screenshots: WebP lossy quality 80, `filesDir/shots`, cap 200 MB (`200L * 1024 * 1024`), evict oldest first (Phase 3 adds status-aware order).
- Store writes run on `Dispatchers.IO`. ATF stays on the main thread.
- SSE event names: `issue` (data = one issue object, same shape as `GET /issues` items), `control` (data = control object), `clear` (data = `{}`).
- Dashboard: doc links rendered only when they start with `https://`; new UI text contrast ≥ 4.5:1 (SC 1.4.3); `npm run dev` keeps working via Vite proxy.
- Old `server/data.db` is not migrated (spec §1).
- Commits: one per task on branch `feature/embedded-server` cut from `dev`. Confirm with the user before the first commit (the Phase 0–1 plan had a no-commit gate).

## Review Focus

1. **Another app on the phone calls `localhost:8080` without the Bearer token** → 401 on `/api/v1/issues`, `/api/v1/screenshots/*`, `/api/v1/events`, `/api/v1/control`. Test: `api routes require the bearer token`.
2. **Port 8080 already held on the device** (most likely: a leftover `adb reverse tcp:8080 tcp:8080` from the Node setup — adbd owns the device port) → `AuditServer.start()` returns false, the app shows the failure text, auditing still records to the store. Test: Task 3 `start returns false when the port is taken`; README tells users to run `adb reverse --remove tcp:8080` (Task 8).
3. **SSE drops while issues keep arriving** (service restarted, browser backgrounded) → banner shows, stream reconnects, and issues recorded during the gap appear without a page reload. Test: Task 7 `refetches issues when the stream reopens`.
4. **Long session fills storage with screenshots** → once `shots/` exceeds the cap the oldest report's file is deleted and its rows keep all fields except the screenshot. Test: Task 2 `cap evicts the oldest screenshot and nulls its rows`.
5. **Malformed control body** (`not json`, `{"auditing":"yes"}`, start without target) → 400, prefs unchanged, server keeps serving. Test: Task 3 `post control validates the body`.

---

### Task 1: `ApiJson` — wire shapes for issues, control, apps, SSE

**Files:**
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/ApiJson.kt`
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/ApiJsonTest.kt`

`DeviceProtocol.kt` stays until Task 4 deletes it with `DeviceSocket`.

**Interfaces:**
- Produces (all in `ApiJson.kt`):
  - `data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)`
  - `data class StoredIssue(val id: Long, val packageName: String, val screen: String?, val timestamp: Long, val severity: String, val wcagSc: String, val wcagLevel: String, val elementDescription: String, val description: String, val suggestedFix: String?, val bounds: Bounds?, val screenshotFile: String?)`
  - `data class Control(val targetPackage: String?, val auditing: Boolean)`
  - `data class AppEntry(val appName: String, val packageName: String)`
  - `object ApiJson` with:
    - `fun suggestedFix(fix: WcagMapping.Fix?, framework: String): String?` — null when `fix` is null; else the JSON string `{"summary","views","compose","docUrl","framework"}` exactly as `DeviceProtocol` builds it today.
    - `fun issue(i: StoredIssue): JSONObject` — keys `id, packageName, screen, timestamp, severity, wcagSC, wcagLevel, elementDescription, description, suggestedFix, bounds, screenshotUrl`. `suggestedFix`/`bounds`/`screenshotUrl`/`screen` are JSON `null` when absent; `bounds` = `{x,y,width,height}`; `screenshotUrl` = `"/api/v1/screenshots/<screenshotFile>"`.
    - `fun issues(list: List<StoredIssue>): String` (JSON array)
    - `fun control(c: Control): String` — `{"targetPackage": <string|null>, "auditing": <bool>}`
    - `fun apps(list: List<AppEntry>): String` — `[{"appName","packageName"}]`
    - `fun parseControl(body: String): Control?` — null for invalid JSON or non-boolean `auditing`; blank/null `targetPackage` → null.
    - `fun sse(event: String, data: String): String` = `"event: $event\ndata: $data\n\n"`

- [ ] **Step 1: Write the failing tests** (plain JUnit, like `DeviceProtocolTest`)

```kotlin
@Test fun `issue encodes every field and a relative screenshot url`() {
    val i = StoredIssue(7, "com.ex", "Main › Dialog", 42, "serious", "1.4.3", "AA", "TextView t", "Contrast 2.1:1",
        """{"summary":"S"}""", Bounds(1, 2, 3, 4), "abc.webp")
    val j = ApiJson.issue(i)
    assertEquals(7L, j.getLong("id"))
    assertEquals("1.4.3", j.getString("wcagSC"))
    assertEquals("""{"summary":"S"}""", j.getString("suggestedFix"))
    assertEquals(3, j.getJSONObject("bounds").getInt("width"))
    assertEquals("/api/v1/screenshots/abc.webp", j.getString("screenshotUrl"))
}
@Test fun `issue writes JSON null for missing optional fields`() {
    val j = ApiJson.issue(StoredIssue(1, "p", null, 0, "moderate", "4.1.2", "A", "e", "d", null, null, null))
    listOf("screen", "suggestedFix", "bounds", "screenshotUrl").forEach { assertTrue(it, j.isNull(it)) }
}
@Test fun `suggestedFix carries snippets and detected framework`() {
    val s = ApiJson.suggestedFix(WcagMapping.Fix("Add a label", "v", "c", "https://d"), "compose")!!
    val j = JSONObject(s)
    assertEquals("Add a label", j.getString("summary")); assertEquals("compose", j.getString("framework"))
    assertNull(ApiJson.suggestedFix(null, "views"))
}
@Test fun `parseControl accepts valid bodies and rejects bad ones`() {
    assertEquals(Control("com.ex", true), ApiJson.parseControl("""{"targetPackage":"com.ex","auditing":true}"""))
    assertEquals(Control(null, false), ApiJson.parseControl("""{"targetPackage":"","auditing":false}"""))
    assertNull(ApiJson.parseControl("not json"))
    assertNull(ApiJson.parseControl("""{"auditing":"yes"}"""))
    assertNull(ApiJson.parseControl("""{"targetPackage":"x"}"""))
}
@Test fun `control encodes null target as JSON null`() {
    assertTrue(JSONObject(ApiJson.control(Control(null, false))).isNull("targetPackage"))
}
@Test fun `sse frame is one event`() {
    assertEquals("event: clear\ndata: {}\n\n", ApiJson.sse("clear", "{}"))
}
```

- [ ] **Step 2: Run to verify failure**
Run: `cd auditor-app && bash gradlew testDebugUnitTest --tests "*ApiJsonTest"`
Expected: compilation FAIL, `Unresolved reference: StoredIssue`.

- [ ] **Step 3: Implement `ApiJson.kt`** with the interfaces above using `org.json`. Read `auditing` with `json.opt("auditing") as? Boolean`; check `isNull("targetPackage")` before `optString` (org.json returns `"null"` otherwise — see `DeviceProtocol.parseControlMessage`).

- [ ] **Step 4: Run to verify pass** — same command, Expected: PASS.

- [ ] **Step 5: Commit** — `git add` the two files; message `Add ApiJson wire shapes for the embedded server`.

---

### Task 2: `AuditStore` — SQLite rows + WebP screenshot files with a cap

**Files:**
- Modify: `auditor-app/gradle/libs.versions.toml` (add `robolectric = "4.13"`, library `robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }`)
- Modify: `auditor-app/app/build.gradle.kts` (`testImplementation(libs.robolectric)`)
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/AuditStore.kt`
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/AuditStoreTest.kt`

**Interfaces:**
- Consumes: `StoredIssue`, `Bounds`, `ApiJson.suggestedFix` (Task 1); `AuditIssue` (existing; `bounds` is ATF `replacements.Rect`).
- Produces:
  - `class AuditStore(context: Context, dbName: String?, private val shotsDir: File, private val capBytes: Long = 200L * 1024 * 1024) : SQLiteOpenHelper(context, dbName, null, 1)` — `dbName = null` gives an in-memory DB for tests.
  - `fun addReport(packageName: String, screen: String?, timestamp: Long, screenshot: ByteArray?, issues: List<AuditIssue>): List<StoredIssue>` — writes the screenshot (if any) once as `<UUID>.webp`, inserts all rows in one transaction pointing at that file, deletes the file if the transaction throws, then enforces the cap. Throws on DB failure; caller logs.
  - `fun issues(): List<StoredIssue>` ordered by id.
  - `fun clear()` — deletes all rows and every file in `shotsDir`.
  - `fun screenshot(name: String): File?` — null unless `name` matches `^[0-9a-f-]{36}\.webp$` and the file exists.

Schema: `issues(id INTEGER PRIMARY KEY AUTOINCREMENT, packageName TEXT, screen TEXT, timestamp INTEGER, severity TEXT, wcagSc TEXT, wcagLevel TEXT, elementDescription TEXT, description TEXT, suggestedFix TEXT, bx INTEGER, by INTEGER, bw INTEGER, bh INTEGER, screenshotFile TEXT)`. `AUTOINCREMENT` so ids are never reused after `clear()`. `onUpgrade` drops and recreates (data is disposable). `suggestedFix` column = `ApiJson.suggestedFix(issue.fix, issue.framework)`.

Eviction order: `SELECT screenshotFile FROM issues WHERE screenshotFile IS NOT NULL GROUP BY screenshotFile ORDER BY MIN(id)`; delete files and `UPDATE issues SET screenshotFile = NULL WHERE screenshotFile = ?` until the sum of `shotsDir` file sizes ≤ `capBytes`.

- [ ] **Step 1: Write the failing tests** — `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])`; `shotsDir = tmp.newFolder("shots")`, `store = AuditStore(RuntimeEnvironment.getApplication(), null, shotsDir, capBytes = 250)` with a JUnit `TemporaryFolder`. Helper `issue(sc: String) = AuditIssue("serious", sc, "AA", "Button b", "d", WcagMapping.Fix("S", "v", "c", "https://d"), "views", Rect(1, 2, 11, 22))`.

```kotlin
@Test fun `addReport stores every issue with its fields`() {
    val out = store.addReport("com.ex", "Main", 5, null, listOf(issue("1.4.3"), issue("2.5.5")))
    assertEquals(2, out.size)
    assertEquals(out, store.issues())
    assertEquals(Bounds(1, 2, 10, 20), out[0].bounds)
    assertEquals("views", JSONObject(out[0].suggestedFix!!).getString("framework"))
    assertNull(out[0].screenshotFile)
}
@Test fun `one screenshot file is shared by the report's issues`() {
    val out = store.addReport("p", "S", 1, ByteArray(10), listOf(issue("a"), issue("b")))
    assertEquals(out[0].screenshotFile, out[1].screenshotFile)
    assertEquals(10L, store.screenshot(out[0].screenshotFile!!)!!.length())
}
@Test fun `clear removes rows and files and ids keep increasing`() {
    val first = store.addReport("p", "S", 1, ByteArray(10), listOf(issue("a")))
    store.clear()
    assertTrue(store.issues().isEmpty()); assertEquals(0, shotsDir.listFiles()!!.size)
    assertTrue(store.addReport("p", "S", 2, null, listOf(issue("a")))[0].id > first[0].id)
}
@Test fun `cap evicts the oldest screenshot and nulls its rows`() {
    val a = store.addReport("p", "S", 1, ByteArray(100), listOf(issue("a")))
    val b = store.addReport("p", "S", 2, ByteArray(100), listOf(issue("b")))
    val c = store.addReport("p", "S", 3, ByteArray(100), listOf(issue("c")))  // 300 > 250
    val rows = store.issues().associateBy { it.id }
    assertNull(rows[a[0].id]!!.screenshotFile)
    assertEquals("a", rows[a[0].id]!!.wcagSc)
    assertNotNull(rows[b[0].id]!!.screenshotFile); assertNotNull(rows[c[0].id]!!.screenshotFile)
    assertNull(store.screenshot(a[0].screenshotFile!!))
}
@Test fun `screenshot rejects names outside the shots dir`() {
    assertNull(store.screenshot("../audit.db")); assertNull(store.screenshot("x.png"))
}
```

- [ ] **Step 2: Run to verify failure**
Run: `cd auditor-app && bash gradlew testDebugUnitTest --tests "*AuditStoreTest"`
Expected: compilation FAIL, `Unresolved reference: AuditStore`.

- [ ] **Step 3: Implement `AuditStore`** per the interface and schema above. `Bounds` from ATF `Rect`: `Bounds(left, top, width, height)`.

- [ ] **Step 4: Run to verify pass** — same command, PASS. Also run the whole suite: `bash gradlew testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit** — message `Add AuditStore: SQLite issues and capped WebP screenshots`.

---

### Task 3: Ktor server — auth, API routes, SSE, static dashboard

**Files:**
- Modify: `auditor-app/gradle/libs.versions.toml` (`ktor = "2.3.12"`; libraries `ktor-server-core`, `ktor-server-cio`, `ktor-server-status-pages`, `ktor-server-test-host`, group `io.ktor`)
- Modify: `auditor-app/app/build.gradle.kts` (three `implementation`, one `testImplementation`)
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/ControlStore.kt`
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/AuditServer.kt`
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/AuditServerTest.kt`

**Interfaces:**
- Consumes: `AuditStore` (Task 2); `ApiJson`, `Control`, `AppEntry` (Task 1); prefs keys `AuditorAccessibilityService.KEY_TARGET_PACKAGE`, `KEY_IS_AUDITING`, `PREFS_NAME`.
- Produces:
  - `ControlStore.kt`: `class ControlStore(private val prefs: SharedPreferences) { fun get(): Control; fun set(c: Control) }` and `object DashboardToken { const val KEY = "dashboard_token"; @Synchronized fun getOrCreate(prefs: SharedPreferences): String }` (persist with `commit()`).
  - `AuditServer.kt`:
    - `class ServerDeps(val store: AuditStore, val control: ControlStore, val token: String, val apps: () -> List<AppEntry>, val asset: (String) -> InputStream?)` with
      `val events: MutableSharedFlow<String>` (`extraBufferCapacity = 64`, `BufferOverflow.DROP_OLDEST`, holds `ApiJson.sse` frames),
      `fun recordReport(packageName: String, screen: String?, timestamp: Long, screenshot: ByteArray?, issues: List<AuditIssue>): List<StoredIssue>` (store, then `tryEmit` one `issue` frame per row),
      `fun publishControl()` (emits `control` with `control.get()`).
    - `fun Application.auditModule(deps: ServerDeps)`
    - `class AuditServer(private val deps: ServerDeps, private val port: Int = 8080) { fun start(): Boolean; fun stop() }` — `embeddedServer(CIO, host = "127.0.0.1", port = port) { auditModule(deps) }.start(wait = false)` in try/catch; on failure log, stop the engine, return false.

Routes in `auditModule`:

| Route | Behavior |
|---|---|
| `POST /api/v1/auth` | body `{"token": "..."}`; match → 204 + cookie per Global Constraints; else 401 |
| any other `/api/v1/*` | 401 `{"error":"unauthorized"}` unless cookie `a11y_session` equals the token |
| `GET /api/v1/issues` | `ApiJson.issues(store.issues())` |
| `DELETE /api/v1/issues` | `store.clear()`, emit `sse("clear","{}")`, 204 |
| `GET /api/v1/screenshots/{name}` | `store.screenshot(name)` → `image/webp`, else 404 |
| `GET /api/v1/control` / `POST /api/v1/control` | GET → control JSON. POST → `parseControl`; 400 if null or `auditing && targetPackage == null`; else `control.set`, respond control JSON (SSE `control` comes from the service's prefs listener, Task 4) |
| `GET /api/v1/apps` | `ApiJson.apps(deps.apps())` |
| `GET /api/v1/events` | `respondBytesWriter(ContentType.Text.EventStream)`, `Cache-Control: no-cache`; `events.onSubscription { write ": connected\n\n"; flush }.collect { write; flush }` — subscribe before the first write so no event between connect and collect is lost |
| any other path | static: `""` → `index.html`; a segment equal to `..` → 404; `deps.asset(path)` null → 404; content type from `ContentType.defaultForFilePath` |

`StatusPages`: `exception<Throwable>` → log, 500 `{"error":"internal"}`.

- [ ] **Step 1: Write the failing tests** — Robolectric runner (`@Config(sdk = [34])`), real in-memory `AuditStore`, `ControlStore` on `RuntimeEnvironment.getApplication().getSharedPreferences(PREFS_NAME, 0)`, token `"t0k"`, `asset = mapOf("index.html" to "<h1>dash</h1>", "assets/a.js" to "x")` → `ByteArrayInputStream`. Helper `authed(req)` adds header `Cookie: a11y_session=t0k`. Use `testApplication { application { auditModule(deps) } ... }`.

Tests (names and assertions):
- `api routes require the session cookie`: GET `/api/v1/issues`, `/api/v1/control`, `/api/v1/screenshots/x.webp`, `/api/v1/events` without cookie → each `401`.
- `auth rejects a wrong token and sets a strict http-only cookie for the right one`: wrong → 401; right → 204, `Set-Cookie` contains `a11y_session=t0k`, `HttpOnly`, `SameSite=Strict`, `Path=/`.
- `issues returns recorded issues with screenshot urls`: `deps.recordReport("p","S",1,ByteArray(4),listOf(issue))` then authed GET → array length 1, `screenshotUrl` starts with `/api/v1/screenshots/`; GET that URL → 200, `Content-Type` `image/webp`; GET `/api/v1/screenshots/00000000-0000-0000-0000-000000000000.webp` → 404.
- `recordReport emits an issue event on the stream`: `client.prepareGet("/api/v1/events"){ authed }.execute { r -> val ch = r.bodyAsChannel(); assertEquals(": connected", ch.readUTF8Line()); ch.readUTF8Line(); deps.recordReport(...); assertEquals("event: issue", ch.readUTF8Line()); assertTrue(ch.readUTF8Line()!!.startsWith("data: {")) }`.
- `delete clears the store and emits clear`: same stream pattern; DELETE → 204, `store.issues()` empty, stream reads `event: clear`.
- `post control validates the body`: `not json`, `{"auditing":"yes"}`, `{"targetPackage":null,"auditing":true}` → 400 each and `control.get()` unchanged; `{"targetPackage":"com.ex","auditing":true}` → 200 and `control.get() == Control("com.ex", true)`.
- `apps lists installed apps`: body equals `[{"appName":"Ex","packageName":"com.ex"}]`.
- `a throwing handler is a 500 and the server keeps serving`: deps with `apps = { error("boom") }` → GET apps 500; then GET issues 200.
- `static serves index at root and refuses traversal`: `/` → 200 body `<h1>dash</h1>` without cookie; `/assets/a.js` → 200 `application/javascript`-family type; `/../audit.db` and `/assets/../../x` → 404; `/nope.js` → 404.
- `start returns false when the port is taken`: pick a free port via `ServerSocket(0).use { it.localPort }`; `AuditServer(deps, port).start()` → true; second `AuditServer(deps, port).start()` → false; stop both.

- [ ] **Step 2: Run to verify failure**
Run: `cd auditor-app && bash gradlew testDebugUnitTest --tests "*AuditServerTest"`
Expected: compilation FAIL, `Unresolved reference: auditModule`.

- [ ] **Step 3: Implement `ControlStore.kt` and `AuditServer.kt`** per the table. Parse request bodies with `call.receiveText()` + `org.json` (no content-negotiation plugin). Run the auth check before reading any body.

`ServerDeps` also takes `heartbeatMs: Long = 15_000` (added during implementation): an idle SSE stream writes `: ping\n\n` that often, because a failed write is how a gone client is noticed.

- [ ] **Step 4: Fix the hanging stream tests (Amended 2026-11-06)**

Observed: the full run produced no result in 12 minutes. A thread dump shows `SDK 34 Main Thread` parked in `testApplication` (`TestApplication.kt:329`) under `delete clears the store and emits clear`, every other thread idle, and none inside `AuditStore`.

Hypothesis, not yet confirmed: `TestApplicationEngine` does not fail writes after the client drops a streaming response. The heartbeat then fills the response channel and suspends forever, so `testApplication` teardown never completes. This would be a test-harness problem, not a production bug.

1. Confirm: `bash gradlew testDebugUnitTest --tests "*AuditServerTest*delete*" -i --console=plain`. Printing `DBG execute done` and then hanging confirms the hypothesis. Stopping at an earlier `DBG` line refutes it: diagnose from that line instead and skip items 3–4.
2. Remove the four `System.err.println("DBG …")` calls from `AuditServerTest.kt`.
3. Add a whole-run limit in `app/build.gradle.kts`, so a hang fails instead of blocking CI: `android { testOptions { unitTests.all { it.timeout.set(java.time.Duration.ofMinutes(5)) } } }`.
4. Move the two stream tests to the real CIO engine: `AuditServer(deps, freePort).start()` plus a raw `java.net.Socket` with `soTimeout = 5000`, writing `GET /api/v1/events` with the cookie and reading lines through a `BufferedReader` past the response headers. Assertions stay the same. `DELETE` goes over a second socket. Stop the server in `finally`. Add:
   - `dropped client releases its subscription`: after the socket closes, `deps.events.subscriptionCount.value` reaches 0 within `heartbeatMs * 3`.

- [ ] **Step 5: Run to verify pass** — `bash gradlew testDebugUnitTest --tests "*AuditServerTest"` → PASS; then `bash gradlew testDebugUnitTest` → PASS, no `DBG` in `git grep DBG -- auditor-app`.

- [ ] **Step 6: Commit** — message `Add embedded Ktor server with token auth, SSE and static dashboard`.

---

### Task 4: Wire the server into the service and app UI; remove the socket client

**Files:**
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/AuditorAccessibilityService.kt`
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/MainActivity.kt`
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/AppListAdapter.kt` (move app loading here)
- Modify: `auditor-app/app/src/main/res/layout/activity_main.xml`, `res/values/strings.xml`, `AndroidManifest.xml`
- Modify: `auditor-app/app/build.gradle.kts`, `gradle/libs.versions.toml` (remove okhttp)
- Delete: `DeviceSocket.kt`, `DeviceProtocol.kt`, `ControlSync.kt`, `HttpCallbacks.kt`, `SettingsActivity.kt`, `res/layout/activity_settings.xml`, `src/test/.../DeviceProtocolTest.kt`

`SettingsActivity` only toggled the device WebSocket, which no longer exists; it goes, along with its manifest entry and `settings_*`, `btn_connect`, `btn_disconnect`, `btn_settings`, `toast_started_while_disconnected`, `label_connection_*` strings.

**Interfaces:**
- Consumes: `AuditStore`, `ServerDeps`, `AuditServer`, `ControlStore`, `DashboardToken`, `AppEntry` (Tasks 1–3).
- Produces:
  - `fun loadInstalledApps(pm: PackageManager, ownPackage: String): List<InstalledAppInfo>` in `AppListAdapter.kt` (body moved from `MainActivity.loadInstalledApps`).
  - In `AuditorAccessibilityService.Companion`: `enum class ServerStatus { Stopped, Running, Failed }`, `val serverStatus = MutableStateFlow(ServerStatus.Stopped)`, `const val DASHBOARD_URL = "http://localhost:8080/"`. Remove `deviceSocketConnected`, `KEY_SOCKET_ENABLED`, `connectDeviceSocket`, `disconnectDeviceSocket`, `onControl`, `onConnectionChange`, and the `DeviceSocketListener` supertype.

Service behavior:
- `onServiceConnected`: create `AuditStore(this, "audit.db", File(filesDir, "shots"))`, `ServerDeps(store, ControlStore(prefs), DashboardToken.getOrCreate(prefs), apps = { loadInstalledApps(packageManager, packageName).map { AppEntry(it.appName, it.packageName) } }, asset = { p -> try { assets.open("dashboard/$p") } catch (e: IOException) { null } })`; register a prefs listener held in a field; on `serviceScope` (IO) call `AuditServer(deps).start()` and set `serverStatus` to `Running` or `Failed`.
- Prefs listener, for `KEY_IS_AUDITING` or `KEY_TARGET_PACKAGE`: `mainHandler.post { baseScreen.reset() }`; if `KEY_IS_AUDITING` is now true, `sessionIssueCount.value = 0`; `deps.publishControl()`. Wrap in try/catch like `onControl` was.
- Report: in the `captureScreenshot` callback, per window, `sessionIssueCount.value += issues.size` then `serviceScope.launch { try { deps.recordReport(targetPackage, label, System.currentTimeMillis(), shot, issues) } catch (e: Exception) { Log.e(...) } }`.
- `captureScreenshot` compresses `Bitmap.CompressFormat.WEBP_LOSSY` at quality 80 (only reached on API 30+, where `WEBP_LOSSY` exists). Rename `png` → `shot`.
- `onUnbind`/`onDestroy`: stop the server, unregister the listener, close the store, `serverStatus.value = Stopped`.
- Update the class KDoc: findings go to the embedded server, not a WebSocket.

MainActivity:
- Remove `ControlSync`, `pushAppList`, `pushControl`, the disconnected toast, the settings button handler; use `loadInstalledApps(packageManager, packageName)`.
- Layout: replace `settingsButton` with `openDashboardButton` ("Open dashboard") and `copyUrlButton` ("Copy laptop URL").
- Open dashboard: `Intent(ACTION_VIEW, Uri.parse("$DASHBOARD_URL?t=$token"))`.
- Copy laptop URL: clip the same URL; on API 33+ set `ClipDescription.EXTRA_IS_SENSITIVE` (the URL carries the token); toast `Copied. On your laptop run: adb forward tcp:8080 tcp:8080`.
- `connectionStatusText` collects `serverStatus`: Running → `Dashboard: running at localhost:8080`; Failed → `Dashboard failed to start: port 8080 is in use (run "adb reverse --remove tcp:8080"). Auditing still works.`; Stopped → `Dashboard: off — enable the accessibility service`. Colors: Running `status_connected`, else `status_disconnected`.

**Amended 2026-11-06:**

- **Do not stop the server on the main thread.** `AuditServer.stop()` blocks up to 1.5 s, and `onUnbind` and `onDestroy` both run on main. The start coroutine can also still be running, which would leave an orphaned server holding the port. Use one idempotent teardown, called from both:

```kotlin
private fun stopServer() {
    val s = server ?: return
    server = null
    prefs.unregisterOnSharedPreferenceChangeListener(controlListener)
    serverStatus.value = ServerStatus.Stopped
    val job = startJob; val st = store
    // Outlives serviceScope, which onDestroy cancels. Joining first means stop() sees the started engine.
    thread(name = "audit-server-stop") { runBlocking { job?.join() }; s.stop(); st.close() }
}
```

  The start coroutine sets `serverStatus` only if `server === s` still holds, so a late result cannot overwrite `Stopped`. `onServiceConnected` calls `stopServer()` first, in case it runs twice.
- **Do not load icons for `/api/v1/apps`.** `loadInstalledApps` decodes every app's icon, which is wasted on each dashboard load. In `AppListAdapter.kt`, add `fun launchableApps(pm: PackageManager, ownPackage: String): List<ActivityInfo>` holding the query, filter, distinct and sort. `loadInstalledApps` maps that to `InstalledAppInfo`; the service's `apps` lambda maps it to `AppEntry(it.loadLabel(pm).toString(), it.packageName)`.
- **Keep the phone UI live.** `MainActivity` only refreshes on resume or click, so Task 9's "Start from the dashboard → the phone app shows Stop" would fail with a laptop dashboard. Hold a prefs listener in a field (prefs keep only a weak reference), register in `onStart`, unregister in `onStop`, and call `refreshStatus()` from it.
- `openDashboardButton.isEnabled = status == Running` in the `serverStatus` collector. Catch `ActivityNotFoundException` on open and toast `No browser found`.
- Remove `instance` from the companion: `SettingsActivity` was its only reason to exist. Remove `serviceRunning` too if the Step 2 grep shows no other reader.
- Keep `android.permission.INTERNET` in the manifest: the server needs it to open a socket, even though okhttp goes.
- Reword the stale comments the Step 2 grep will flag: `captureScreenshot` (`DeviceSocket.sendReport`) and `app/build.gradle.kts` (`DeviceProtocolTest` becomes `ApiJsonTest`).
- New text goes in `strings.xml`. Remove `label_connection_connected` and `label_connection_disconnected`.

- [ ] **Step 1: Make the changes above.**

- [ ] **Step 2: Verify nothing references removed code**
Run: `git grep -nE "DeviceSocket|DeviceProtocol|ControlSync|loggingCallback|SettingsActivity|okhttp|deviceSocketConnected" -- auditor-app`
Expected: no output.

- [ ] **Step 3: Build and test**
Run: `cd auditor-app && bash gradlew testDebugUnitTest assembleDebug` (needs `dashboard/dist` only after Task 5; until then `assembleDebug` succeeds without assets)
Expected: BUILD SUCCESSFUL. If `mergeDebugJavaResource` fails on a duplicate `META-INF/...` file from the Ktor jars, add that exact path to `android { packaging { resources { excludes += ... } } }` and rerun.

- [ ] **Step 4: Smoke-test on a device now (Amended 2026-11-06)**

Ktor CIO has only run on the desktop JVM so far. Finding an ART failure here is cheaper than at Task 9. A scan of the Ktor jars found one `java.lang.management` reference, in `IntellijIdeaDebugDetector`; whether it is safe on Android is unverified.

Run `adb reverse --remove-all`, `bash gradlew installDebug`, then enable the service.
Expected: the app shows `Dashboard: running at localhost:8080`; `adb shell curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v1/issues` prints `401`; `adb logcat -d | grep -E "NoClassDefFoundError|A11yAuditor.Server"` shows no error.

- [ ] **Step 5: Commit** — message `Run the dashboard server inside the accessibility service; drop the socket client`.

---

### Task 5: Package the dashboard build into the APK; CI order

**Files:**
- Modify: `auditor-app/app/build.gradle.kts`
- Modify: `.github/workflows/ci.yml` (android job)

**Interfaces:**
- Produces: APK assets under `dashboard/` (`dashboard/index.html`, `dashboard/assets/*`), read by the `asset` lambda from Task 4.

- [ ] **Step 1: Add the sync task** (AGP's generated-assets API; Gradle never runs npm). Add `import javax.inject.Inject` at the top of the file:

```kotlin
abstract class SyncDashboard : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dist: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction fun sync() {
        val dir = dist.singleFile
        if (!File(dir, "index.html").isFile) throw GradleException("Run \"npm run build\" in dashboard/ first")
        fs.sync { from(dir); into(outputDir.dir("dashboard")) }
    }
}

val syncDashboard = tasks.register<SyncDashboard>("syncDashboard") {
    dist.from(rootProject.layout.projectDirectory.dir("../dashboard/dist"))
}
androidComponents {
    onVariants { it.sources.assets?.addGeneratedSourceDirectory(syncDashboard, SyncDashboard::outputDir) }
}
```

- [ ] **Step 2: Verify the missing-build failure**
Run (with `dashboard/dist` absent): `cd auditor-app && bash gradlew assembleDebug`
Expected: FAIL with `Run "npm run build" in dashboard/ first`.

- [ ] **Step 3: Verify packaging**
Run: `npm install && npm run build` (repo root), then `cd auditor-app && bash gradlew assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/dashboard/index.html`
Expected: one matching line.

- [ ] **Step 4: CI** — in the `android` job add, before the Gradle step: `actions/setup-node@v4` (node 22), and `npm install` + `npm run build` with `working-directory: .` (the job default is `auditor-app`).

- [ ] **Step 5: Commit** — message `Bundle the dashboard build into APK assets`.

---

### Task 6: Client-side export

**Files:**
- Create: `dashboard/src/export.js`
- Test: `dashboard/src/export.test.js`

**Interfaces:**
- Consumes: `parseFix` from `dashboard/src/logic.js` (do not copy it).
- Produces:
  - `export function toCsv(issues): string` — same header and rows as `server/src/export.js` today.
  - `export async function toHtml(issues, fetchDataUrl = defaultFetchDataUrl): Promise<string>` — same markup as `server/src/export.js` `toHtml`, except the image `src` is the data URL for `issue.screenshotUrl`; each distinct URL is fetched once; a failed or missing fetch omits the image.
  - `export function download(filename, text, type)` — `Blob` + temporary `<a download>`, then `URL.revokeObjectURL`.
  - `defaultFetchDataUrl(url)`: `fetch(url)` → blob → `FileReader.readAsDataURL`; resolves `null` on any failure.

- [ ] **Step 1: Write the failing tests** — port the five `node:test` cases in `server/src/export.test.js` to Vitest (`await toHtml(...)`), plus:

```js
it('embeds each screenshot once as a data URL', async () => {
  const f = vi.fn(async () => 'data:image/webp;base64,AAA');
  const html = await toHtml([{ ...base, screenshotUrl: '/api/v1/screenshots/a.webp' }, { ...base, id: 2, screenshotUrl: '/api/v1/screenshots/a.webp' }], f);
  expect(f).toHaveBeenCalledTimes(1);
  expect(html).toContain('src="data:image/webp;base64,AAA"');
});
it('omits the image when the screenshot cannot be fetched', async () => {
  const html = await toHtml([{ ...base, screenshotUrl: '/x.webp' }], async () => null);
  expect(html).not.toContain('<img');
});
```

- [ ] **Step 2: Run to verify failure** — `npm test -w dashboard -- export` → FAIL, cannot resolve `./export`.
- [ ] **Step 3: Implement `export.js`.**
- [ ] **Step 4: Run to verify pass** — same command → PASS.
- [ ] **Step 5: Commit** — message `Move report export into the dashboard`.

---

### Task 7: Dashboard on the embedded API — auth, SSE, relative URLs

**Files:**
- Create: `dashboard/src/api.js`
- Modify: `dashboard/src/App.jsx`, `dashboard/src/App.css` (banner), `dashboard/vite.config.js`
- Test: `dashboard/src/App.test.jsx`

**Interfaces:**
- Consumes: `toCsv`, `toHtml`, `download` (Task 6); API routes and SSE events (Task 3).
- Produces (`api.js`):
  - `export const API = '/api/v1'`
  - `export class AuthError extends Error {}`
  - `export async function apiFetch(path, init)` — `fetch(API + path, init)`; throws `AuthError` on 401; returns the `Response`.
  - `export async function authFromUrl()` — if `?t=` is present: POST `{token}` to `${API}/auth`, then `history.replaceState(null, '', location.pathname)` whether or not it succeeded.

App behavior:
- Mount: `await authFromUrl()`, then load `/issues`, `/control`, `/apps`; any `AuthError` sets `authFailed`.
- `new EventSource(`${API}/events`)`: `open` → `streamUp = true` and refetch `/issues` + `/control`; `error` → `streamUp = false`, and if `readyState === EventSource.CLOSED` (browser gave up, e.g. 401) recreate after 2000 ms. Listeners: `issue` (append, dedup by id), `clear` (empty), `control` (set). Close on unmount.
- Banner: always render `<div role="status" aria-live="polite" className="stream-banner">`; its text is `Device disconnected — reconnecting…` while `!streamUp`, empty otherwise.
- `authFailed` → replace the main content with: `Not signed in to this device's dashboard. Reopen it with "Open dashboard" in the A11y Auditor app, or use "Copy laptop URL".`
- Remove `SERVER`, `WS_URL`, `deviceOnline`, the `/device` fetch, the "server live"/"device connected" header spans, and device-related control-status lines. Start is disabled only for `controlBusy || (!control.auditing && !control.targetPackage)`.
- Export HTML / Export CSV become `<button>`s calling `download('a11y-report.html', await toHtml(issues), 'text/html')` and `download('a11y-report.csv', toCsv(issues), 'text/csv')`.
- `ScreenshotWithHighlight` takes `src` instead of `screenshot`; cards and lightbox pass `issue.screenshotUrl`.
- Vite: `server: { proxy: { '/api': 'http://localhost:8080' } }`.

**Amended 2026-11-06:**

- **Order:** create the `EventSource` only after `authFromUrl()` resolves. Otherwise the first connect gets 401 and the banner flashes for 2 s on every open.
- `streamUp` starts `true`, so the banner does not flash before the first `open`.
- **StrictMode** (`main.jsx`) runs the effect twice: use a `cancelled` flag so an effect that was cleaned up during `await authFromUrl()` creates no stream, and clear the retry timer on cleanup.
- Do not recreate the stream once `authFailed` is set. A 401 would otherwise retry every 2 s forever. Hold the flag in a ref, since the effect closure sees stale state.
- **Refetch race on `open`:** a plain `setIssues(response)` can drop an issue that arrived while the request was in flight, and a response older than a `clear` can bring cleared rows back. Keep a `streamedRef` array (reset when the refetch starts, appended by `issue`) and a `clearGenRef` counter (incremented by `clear`, which also empties `streamedRef`). Discard a response whose generation changed; otherwise set `response` merged with `streamedRef`, deduplicated by id.
- `toggleAuditing` guard becomes `if (nextAuditing && !control.targetPackage) return`.
- `control-status` text: not auditing with a target → `Not auditing — hit Start when ready.`; not auditing without one → `Pick a target app on your phone, then hit Start.`; auditing → `Auditing <code>{control.targetPackage}</code>`.
- Delete the `.ws-on`, `.ws-off` and `.status-dot` rules from `App.css`: the header spans were their only users.

- [ ] **Step 1: Rewrite the App-level tests** — replace `FakeWebSocket` with a `FakeEventSource` (`instances`, `readyState`, `addEventListener`, `onopen`/`onerror`, `close`, helper `emit(type, data)`), stub `fetch` keyed on `/api/v1/...` paths. Keep the logic/`parseFix`/fix-panel tests, driven by `emit('issue', …)` instead of WS messages. Tests:
  - `appends issue events and dedups by id`
  - `clear event empties the list`
  - `control event updates the target display`
  - `enables Start once a target is set, with no device gate`
  - `shows the reconnect banner while the stream is down and clears it on open` — `getByRole('status')` text toggles.
  - `refetches issues when the stream reopens` — second `open` after `error` triggers a second `GET /api/v1/issues`, and an issue only in that response renders.
  - `posts the ?t= token to /api/v1/auth and strips it from the URL` — start at `/?t=abc`; expect `fetch('/api/v1/auth', { method: 'POST', body: '{"token":"abc"}', … })` and `location.search === ''`.
  - `shows the sign-in message on 401` — and `FakeEventSource.instances` does not grow after 2000 ms with the stream `CLOSED` (fake timers).
  - `keeps an issue that arrives during the refetch` — hold the `/issues` response, `emit('issue', x)`, resolve without `x`, and `x` stays rendered.
  - `a clear during the refetch discards the stale response` — hold the response, `emit('clear', {})`, resolve with rows, and the list stays empty.
  - `connects the stream only after auth` — at `/?t=abc`, no `FakeEventSource` exists until the `/api/v1/auth` promise resolves.
  - `renders screenshots from screenshotUrl` — `img.src` ends with `/api/v1/screenshots/a.webp`.
  - `ScreenshotWithHighlight` tests updated for `src`.
- [ ] **Step 2: Run to verify failure** — `npm test -w dashboard` → the new tests FAIL.
- [ ] **Step 3: Implement `api.js` and the App changes.** Banner text color must reach 4.5:1 against its background in both themes; add a case to `contrast.test.js` for the colors you pick.
- [ ] **Step 4: Run to verify pass** — `npm test -w dashboard` and `npm run lint -w dashboard` → PASS, no lint errors.
- [ ] **Step 5: Commit** — message `Point the dashboard at the embedded API over SSE`.

---

### Task 8: Delete the Node server; README and CI

**Files:**
- Delete: `server/` (whole directory)
- Modify: `package.json` (root), `package-lock.json` (regenerate), `.github/workflows/ci.yml` (`app` job), `README.md`

- [ ] **Step 1: Root `package.json`** — `workspaces: ["dashboard"]`; scripts `build`, `dev`, `test` (`npm test -w dashboard`); remove `start`. Run `npm install` to regenerate the lockfile.
- [ ] **Step 2: CI `app` job** — steps: `npm install`, `npm test`, `npm run build`. Remove the `npm start` smoke test.
- [ ] **Step 3: README** — replace the architecture diagram (target app → A11y Auditor APK with ATF + Ktor + SQLite → phone browser, or laptop via `adb forward`), and the setup with: `npm install && npm run build` → `cd auditor-app && ./gradlew installDebug` → enable the service → **Open dashboard**, or `adb forward tcp:8080 tcp:8080` + **Copy laptop URL**. Include: run `adb reverse --remove tcp:8080` if upgrading from the Node setup; export reports before upgrading (old data is not migrated); `npm run dev` proxies `/api` to `localhost:8080` (needs `adb forward`). Remove all `/ws/device`, `POST /control` 409 and device-online text.
- [ ] **Step 4: Verify**
Run: `git grep -nE "server/|ws/device|adb reverse tcp:8080 tcp:8080|npm start" -- . ':!docs'`
Expected: only the README's `adb reverse --remove` line. Then `npm test` and `cd auditor-app && bash gradlew testDebugUnitTest assembleDebug` → both pass.
- [ ] **Step 5: Commit** — message `Remove the Node server`.

---

### Task 9: On-device verification

**Files:** none unless a check fails (fix in the owning task's files and rerun its tests).

Needs an authorized device or emulator. Run `adb devices -l` first; if more than one is attached, ask the user which to use. Drive the browser and app checks with ARTEMIS (`mobile_run_task`, `device_serial` set); run the `adb` checks directly.

- [ ] **Step 1: Install** — `adb reverse --remove-all; npm run build && cd auditor-app && ./gradlew installDebug`; enable the service. App shows `Dashboard: running at localhost:8080`.
- [ ] **Step 2: Checklist** (record pass/fail with notes):
  - [ ] **Open dashboard** opens the phone browser; the URL bar no longer shows `?t=` after load.
  - [ ] `adb forward tcp:8080 tcp:8080` + **Copy laptop URL** pasted on the laptop loads the dashboard.
  - [ ] `adb shell curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/v1/issues` prints `401` (Review Focus 1).
  - [ ] Start from the dashboard → the phone app shows Stop; Stop on the phone → the dashboard shows Start within a second.
  - [ ] Navigating the target app streams issues with screenshots and highlight boxes.
  - [ ] Toggle the accessibility service off and on with the dashboard open: banner appears, then clears, and issues found meanwhile show up (Review Focus 3).
  - [ ] Export HTML opens offline with screenshots embedded; Export CSV opens in a spreadsheet.
  - [ ] Clear Session empties the dashboard and `adb shell run-as com.a11yauditor.app ls files/shots` is empty.
  - [ ] With `adb reverse tcp:8080 tcp:8080` set before enabling the service, the app shows the port-in-use message and auditing still counts issues (Review Focus 2).
  - [ ] Phase 1 checks still pass: dialog/sheet/popup issues grouped as `Activity › Title`; "How to fix" panel with the detected framework first.
- [ ] **Step 3: Report** — list each item as pass/fail.
