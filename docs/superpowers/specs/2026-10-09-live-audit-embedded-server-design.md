# Live Audit + Embedded Server — Design

Date: 2026-10-09
Status: Approved in brainstorming, pending written-spec review

## 1. Intent

**Goal (user's words):** make the WCAG auditor more robust for Android apps, help apps reach
WCAG compliance faster, and find accessibility issues at a much faster rate.

**Decisions made by the user during brainstorming:**

- No automatic crawler. The user navigates the target app by hand; the auditor keeps analysing
  every screen along the way and keeps the report up to date live.
- In scope: (a) each unique issue reported once, grouped by a recognisable screen; (b) issues
  auto-marked fixed when a revisited screen no longer has them; (c) dialogs, bottom sheets and
  popups audited, not just the active window; (d) fix guidance per issue.
- Move the backend into the APK (embedded HTTP server) and **delete the Node server**.
- Python rewrite rejected: robustness gaps are in the data model, not the language. A future
  Python analysis worker (OCR/ML checks) can sit behind the versioned API if ever needed.

**Assumptions (correct these if wrong):**

- One device per audit session. Combined multi-device reports are not needed.
- Access is localhost-only: the phone's own browser, or a laptop via `adb forward`. Wi-Fi access
  is out of scope.
- Existing `server/data.db` contents are disposable test data; no migration.

**Success criteria:**

- Navigating a screen repeatedly, scrolling, or seeing 30 identical list rows produces one issue
  per (screen, check, element), with an occurrence count. No duplicate rows.
- Fixing an issue in the target app and revisiting the screen flips it to `fixed` without user
  action; scrolling never flips anything to `fixed`.
- Issues inside dialogs, bottom sheets and popup menus are reported under their own screen.
- Every mapped ATF check shows a Views and a Compose fix snippet.
- No laptop server: install APK, enable service, open dashboard.
- No other app on the phone can read audit data without the token.

## 2. Current state (baseline)

- `AuditorAccessibilityService` debounces `typeWindowStateChanged|typeWindowContentChanged`
  (600 ms), runs the ATF `LATEST` preset on `rootInActiveWindow` (main thread), and POSTs any
  ERROR/WARNING results with a screenshot to the Node server via `adb reverse`.
- Screen name is `event.className` (often a random view class).
- No deduplication: every settle re-reports every issue. Scans with zero issues are not sent.
- `suggestedFix` exists end to end (app payload → SQLite → dashboard) but is always `null`.
- Control is a 3 s `GET /control` poll that doubles as a heartbeat.
- Node server: Express + `ws` + `node:sqlite`, ~400 lines, serves the built dashboard.

## 3. Scan model, fingerprints, fixed-detection

**Scan.** On each settle the service builds a `Scan`: the screen key, every ATF issue from all
target windows (§4), and the set of element keys present in the hierarchy. The
`AccessibilityNodeInfo` tree is first converted to plain data classes (`ScanNode`, `Scan`) so all
downstream logic is pure and JVM-testable.

**Screen key** = package + activity + title.

- Activity: className of the last `TYPE_WINDOW_STATE_CHANGED` event that names an Activity or
  Dialog (replaces `event.className` from arbitrary events).
- Title: `AccessibilityWindowInfo.title`, plus pane title on API 28+, when present.
- When neither title is present (common in Compose / single-activity apps), append a coarse
  structural signature: hash of top-level resource IDs and heading texts. Deliberately coarse so
  changing list content does not create new screens.

**Element key**, first match wins:

1. `resourceId` + class
2. class + index path from the nearest ancestor with a resource ID + text or contentDescription

**Issue fingerprint** = hash(screen key, ATF check class name, element key).

- Known fingerprint → update `lastSeen`, increment `occurrences`. No new row.
- List rows sharing a resource ID collapse into one issue ("×30"); one fix fixes them all.
- A screenshot is stored only at first sighting.

**Statuses:** `open`, `fixed`, `regressed`, `ignored`. Each issue has `firstSeen`, `lastSeen`.

| From | Event | To |
|---|---|---|
| (new) | check fires | `open` |
| `open` / `regressed` | scan of same screen: element key present, check does not fire | `fixed` |
| `open` / `regressed` | element key absent from scan (scrolled away, recycled, other tab) | unchanged |
| `fixed` | check fires again | `regressed` |
| any | user clicks Ignore | `ignored` |
| `ignored` | user clicks Un-ignore | `open` |
| `ignored` | check fires again | `ignored` (lastSeen updated) |

The "element must be present" rule is the core safeguard: it prevents scrolling from falsely
fixing everything below the fold.

## 4. Window coverage (dialogs, sheets, popups)

- Scan input switches from `rootInActiveWindow` to `getWindows()` (`flagRetrieveInteractiveWindows`
  is already set). Keep windows whose root `packageName` is the target: activities, dialogs,
  bottom sheets, popup menus, dropdowns. Skip IME, system and accessibility-overlay windows.
- ATF runs once per window, each as its own hierarchy. Before implementing, confirm with `javap`
  whether ATF 4.1.1 offers a multi-window `AccessibilityHierarchyAndroid` builder; use it if
  present, otherwise per-window is correct.
- Add `typeWindowsChanged` to `accessibility_service_config`. These events often have a null
  `packageName`, so the handler checks whether any current window belongs to the target instead
  of filtering on the event's package.
- A dialog window gets its own screen key (activity + dialog title), e.g.
  "Checkout › Confirm payment".
- The screenshot (`takeScreenshot`) already captures the whole display including overlays.

## 5. Fix guidance

- Extend `WcagMapping.Criterion` with `fix: Fix(summary, viewsSnippet, composeSnippet, docUrl)`.
  `WcagMapping.kt` stays the single place to extend per check.
- Framework detection: an issue is tagged Compose if the element has an `AndroidComposeView`
  ancestor; the dashboard shows the matching snippet first.
- ATF's own message (which includes specifics such as measured contrast ratio and colours) stays
  as the issue description; the fix sits beside it.
- Out of scope: per-issue generated fixes (e.g. computing a passing colour).

## 6. Embedded server

**Runtime.** Ktor, CIO engine, inside the accessibility service process. Starts in
`onServiceConnected`, stops in `onUnbind`/`onDestroy`. Binds `127.0.0.1:8080`. If the port is
taken: log, show the error in the app, keep auditing.

**Isolation.** All handlers go through `StatusPages` (exception → 500, never a service crash).
Server start is wrapped in try/catch; auditing works even if the server fails to start. Store
writes run on an IO dispatcher. The ATF run stays where it is today (see the existing threading
note in `checkHierarchy`).

**API** (`/api/v1`):

| Route | Purpose |
|---|---|
| `POST /auth` | exchange token for session cookie |
| `GET /issues?status=` | list issues |
| `PATCH /issues/{id}` | set `ignored` or back to `open` |
| `DELETE /issues` | clear session (issues, screens, screenshots) |
| `GET /screenshots/{id}.webp` | screenshot file |
| `GET /control`, `POST /control` | target package + auditing flag (prefs, direct) |
| `GET /apps` | installed launchable apps from `PackageManager` |
| `GET /events` | SSE stream: `issue`, `status`, `control`, `clear` |

All other paths serve the dashboard from APK assets.

**Storage.**

- `SQLiteOpenHelper` (no Room). Tables `screens(key, label, firstSeen)` and
  `issues(id, fingerprint UNIQUE, screenKey, checkClass, elementKey, status, occurrences,
  firstSeen, lastSeen, severity, wcagSc, wcagLevel, elementDescription, description, framework,
  bounds, screenshotFile)`.
- Screenshots: WebP quality 80 in `filesDir/shots`, 200 MB cap. Over the cap, delete screenshots
  of `fixed`/`ignored` issues first, oldest first, then oldest overall.

**Security.**

- Binding to `127.0.0.1` keeps the network out, but any app on the phone can still connect to
  localhost. Without authentication another app could read screenshots of audited screens
  (banking, OTPs, messages).
- A random token is generated once per install. Every route except static assets and
  `POST /auth` requires the session cookie (HttpOnly, SameSite=Strict) obtained via
  `POST /auth`.
- The app shows **Open dashboard** (opens the phone browser at `http://localhost:8080/?t=<token>`)
  and **Copy laptop URL** (for use after `adb forward tcp:8080 tcp:8080`).

## 7. Dashboard

- API base is relative (`/api/v1`). On load, if `?t=` is present, POST it to `/api/v1/auth`, then
  strip it with `history.replaceState`. Works whether served by the device or by Vite.
- WebSocket replaced by `EventSource('/api/v1/events')`. While the stream is down, show a
  "Device disconnected — reconnecting…" banner (`aria-live="polite"`). Remove the heartbeat-based
  online indicator and the disabled-Start logic.
- Status tabs: Open / Regressed / Fixed / Ignored with counts; default view Open + Regressed.
- Issue card: occurrence badge, fix panel (summary, Views/Compose snippet tabs with detected
  framework first, docs link), **Ignore** button with a specific accessible name
  (e.g. "Ignore: TouchTargetSize on btn_close").
- Remove the localStorage "resolved" checkbox; Ignore is server state.
- Progress strip: "N of M fixed", overall and per screen.
- Export moves client-side: port `server/src/export.js` to `dashboard/src/export.js`;
  screenshots fetched and embedded as data URLs so the HTML report remains one file.
- Dev: `npm run dev` keeps working via Vite `server.proxy` for `/api` to `localhost:8080`.

## 8. Build and removal of the Node server

- Gradle `syncDashboard` Copy task: `dashboard/dist` → generated assets dir, registered with
  `androidComponents { sources.assets.addGeneratedSourceDirectory }`. Fails with
  `Run "npm run build" in dashboard/ first` if `dist` is missing. Gradle does not run npm.
- CI order: dashboard build, then APK build.
- Delete `server/`, plus `ControlSync`, `ReportSender`, `HttpCallbacks` and the `POST /apps`
  push in `MainActivity`. Root `package.json` workspaces reduce to `dashboard`; `npm start`
  removed.
- README: new architecture diagram and setup (install APK → enable service → Open dashboard, or
  `adb forward` + copied URL). Tell users to export before upgrading; old data is not migrated.

## 9. Error handling summary

- Event handler: existing broad try/catch stays; `typeWindowsChanged` with null package is
  handled, not dropped.
- Per-window ATF failure skips that window only, not the whole scan.
- Server: `StatusPages` + guarded start; a server failure never stops auditing.
- Store: write failures are logged and the scan is skipped; no partial status transitions
  (each scan applied in one SQLite transaction).
- Dashboard: SSE reconnect banner; 401 → message to reopen via the app's Open dashboard /
  Copy laptop URL.

## 10. Testing

**JVM unit tests (no device):**

- Element key priority and ancestor-path fallback; screen key with title and with Compose
  signature; fingerprint stability across rescans; list-row collapse.
- Every row of the status-transition table in §3, including "element absent → unchanged".
- Store (Robolectric, test-only): fingerprint upsert, occurrences, screenshot cap eviction order.
- Routes (Ktor `testApplication`): 401 without cookie, cookie after `/auth`, upsert emits SSE
  `issue`, `PATCH` ignore, `DELETE` clear.
- `WcagMappingTest`: every mapped check has a non-empty fix.

**Dashboard (Vitest):** mocked `EventSource`, status tabs and counts, Ignore, reconnect banner,
client-side export.

**Manual device checklist:**

- [ ] Dialog, bottom sheet and popup menu each appear as their own screen with their issues.
- [ ] A 30-row list with one bad row layout yields one issue ×30.
- [ ] Scrolling a long list marks nothing fixed.
- [ ] Fixing an issue in the target app and revisiting marks it fixed; reintroducing it marks
      it regressed.
- [ ] Another app (or `adb shell curl` without cookie) gets 401 from `/api/v1/issues`.
- [ ] Open dashboard works in the phone browser; Copy laptop URL works after `adb forward`.

## 11. Phasing

Each phase ships and is verified on its own.

0. **Review fixes** on the current uncommitted dashboard work: button and resolved-card contrast,
   stale resolved IDs on WS `clear`, resolved count, `distributionSha256Sum`, LF for `gradlew`.
   Commit.
1. **Fix guidance + window coverage** on the current Node stack (`suggestedFix` already flows end
   to end). Includes the `javap` check of ATF's multi-window builder.
2. **Embedded server at feature parity**: Ktor, store, token, dashboard on SSE + relative URLs,
   client-side export. Same issue semantics as today. Then delete the Node server.
3. **Fingerprints, screen identity, fixed-detection**: status tabs, occurrences, progress strip,
   server-side Ignore replacing the checkbox.

## 12. Out of scope

Automatic crawler; Wi-Fi access; multi-device aggregation; CI/headless mode; custom checks beyond
ATF; per-issue generated fixes; moving the ATF run off the main thread.
