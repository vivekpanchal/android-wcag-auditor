# A11y Auditor — Runtime WCAG Auditor for Android

[![CI](https://github.com/vivekpanchal/android-wcag-auditor/actions/workflows/ci.yml/badge.svg)](https://github.com/vivekpanchal/android-wcag-auditor/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/vivekpanchal/android-wcag-auditor)](LICENSE)

Standalone accessibility auditor: install one APK, point it at any other app already on the
device, and watch WCAG-mapped accessibility issues stream into a local web dashboard as you
navigate that app. **No changes to the target app's code or build required** — debug or release,
yours or someone else's, doesn't matter.

## How it works

```
┌─────────────────┐   AccessibilityService    ┌──────────────┐
│  Target app      │ ─── events + node tree ─→ │ A11y Auditor  │
│  (any app,       │                            │ (this APK)    │
│  unmodified)      │                            │ ATF checks    │
└─────────────────┘                            │ + screenshot   │
                                                 └──────┬───────┘
                                                        │ HTTP POST (adb reverse)
                                                        ▼
                                          ┌──────────────────────┐
                                          │  Node server :8080    │
                                          │  (/server)            │
                                          └──────────┬───────────┘
                                                      │ WebSocket
                                                      ▼
                                          ┌──────────────────────┐
                                          │  React dashboard      │
                                          │  (/dashboard, :5173)  │
                                          └──────────────────────┘
```

The Auditor app registers an `AccessibilityService`. Once a target package + Start is set — from
the app's own UI, or from the dashboard (see below) — the service watches for window/content-change
events from *that* package only (everything else, including itself, is ignored). On each change it
grabs the current `AccessibilityNodeInfo` tree, runs it through Google's
[Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android)
(ATF), maps each finding to a WCAG 2.1 success criterion (see `auditor-app/.../WcagMapping.kt`),
optionally grabs a screenshot, and POSTs the result to the local server, which fans it out to the
dashboard over a WebSocket.

**Controlling from the dashboard:** `adb reverse` only lets the device reach the server, not the
other way around, so remote control is a poll, not a push. The dashboard POSTs desired
target/auditing state to `POST /control`; the Auditor app's service polls `GET /control` every 3s
and applies whatever it finds. Starting/stopping from the device's own UI pushes to the same
endpoint, so both stay in sync regardless of which one changed it last. The server is the single
source of truth for auditing state, but the app degrades gracefully to device-only operation if
the server isn't reachable (fetches just return nothing, control stays local).

**Device connection status:** the same 3s `/control` poll doubles as a heartbeat — the server marks
the device offline after ~8s of silence (two missed polls) and broadcasts the change over
WebSocket. The dashboard shows a live "device connected/disconnected" indicator and refuses to
start an audit while offline, both in the UI (Start is disabled) and server-side
(`POST /control` with `auditing: true` 409s if no device has been seen recently) — so you can't
accidentally "start" an audit that has no device to run on.

**App picker:** the Auditor app POSTs its installed-apps list to `POST /apps` once on launch
(`MainActivity.onCreate`); the dashboard's target field is an `<input list>` wired to a
`<datalist>` of that list — native browser autocomplete, still lets you type a package name
directly if the app hasn't reported in yet.

## Project layout

- `server/` — Express + `ws` backend. In-memory issue store, REST + WebSocket API.
- `dashboard/` — React + Vite live dashboard.
- `auditor-app/` — standalone Android app (Kotlin). Its own package (`com.a11yauditor.app`),
  installed independently of whatever you're auditing.

## Prerequisites

- Node.js 18+ (for `server/` and `dashboard/`)
- Android Studio (to build `auditor-app/` — it'll fetch/repair the Gradle wrapper on first open)
- A device or emulator running Android 8.0 (API 26) or newer, with `adb` on your `PATH`
  - Screenshots in reported issues require Android 11 (API 30)+; on older devices the Auditor
    still reports every issue, just without a screenshot attached.

## Setup

### 1. Server

```bash
cd server
npm install
npm start          # listens on http://localhost:8080
```

### 2. Dashboard

```bash
cd dashboard
npm install
npm run dev         # http://localhost:5173
```

Open the printed URL in your browser. It'll show "No active session" until issues start arriving.

### 3. Auditor app

Build and install from Android Studio (open `auditor-app/`, hit Run), or from the command line
once the Gradle wrapper is present:

```bash
cd auditor-app
./gradlew installDebug
```

Or install a prebuilt APK directly:

```bash
adb install auditor-app.apk
```

### 4. Enable the accessibility service

On the device: **Settings → Accessibility → A11y Auditor → On**, or tap "Enable Accessibility
Service" inside the app, which jumps straight to that settings screen.

### 5. Connect device to the local server

```bash
adb reverse tcp:8080 tcp:8080
```

This makes the device's `localhost:8080` point at your machine's `localhost:8080`, so the Auditor
app can just POST to `http://localhost:8080/report` without knowing your machine's LAN IP.

### 6. Pick a target and go

Either works — they stay in sync (see "Controlling from the dashboard" above):

**From the phone:**
1. Open the A11y Auditor app.
2. Pick the app you want audited from the list (or type its package name manually), e.g.
   `com.example.myapp`.
3. Tap **Start Auditing**.

**From the dashboard** (`http://localhost:5173`) — no need to touch the phone at all once the
service is enabled:
1. Type the target package name into the control bar at the top.
2. Click **Start Auditing**. The phone picks it up within a few seconds.

Then, either way:
4. Switch to the target app on the device and use it normally — navigate screens, open dialogs,
   scroll lists.
5. Watch issues appear live in the dashboard, grouped by screen, with a running count by severity.
6. Click/tap **Stop Auditing** — from either the dashboard or the app — when done.

### 7. Export a report

In the dashboard: **Export HTML** for a shareable report grouped by screen, or **Export CSV** for
raw data. **Clear Session** wipes the in-memory store to start a fresh audit run.

## WCAG mapping

`auditor-app/app/src/main/java/com/a11yauditor/app/WcagMapping.kt` maps each ATF check class to a
WCAG 2.1 success criterion + level (e.g. `TouchTargetSizeCheck` → 2.5.5 AAA,
`TextContrastCheck` → 1.4.3 AA). Extend it by adding another `Criterion` entry keyed by the ATF
check's simple class name — everything downstream (server, dashboard) already handles arbitrary
SC/level values.

This mapping isn't guessed from check class names — it's cross-checked against ATF 4.1.1's actual
source (class docs + the literal threshold constants it enforces, e.g. `ContrastUtils.
CONTRAST_RATIO_WCAG_NORMAL_TEXT = 4.5`, which is exactly the 1.4.3 AA threshold). Two honest caveats
are documented inline in `WcagMapping.kt`: `TouchTargetSizeCheck` defaults to Android's 48×48dp
Material guideline rather than WCAG's 44×44 CSS px for SC 2.5.5 (a strict superset — passing it
always satisfies 2.5.5, but a failure doesn't automatically prove one), and the contrast checks are
exact matches to the WCAG-defined ratios, not approximations.

## Build status

`auditor-app/` builds clean — `./gradlew assembleDebug` produces
`app/build/outputs/apk/debug/app-debug.apk`. The ATF integration in
`AuditorAccessibilityService.checkHierarchy()` was verified against the real
accessibility-test-framework 4.1.1 classes (via `javap`), not just written against docs — the one
fix that took was adding an explicit `com.google.guava:guava` dependency, since ATF's public API
returns Guava collection types without exposing Guava transitively.

The Gradle wrapper jar itself isn't committed (binary, wasn't practical to generate from this
tool). Opening the project in Android Studio regenerates it automatically on sync; from the
command line, run `gradle wrapper` once with any local Gradle install first.

## Privacy

Everything is local. The Auditor app only reads accessibility-tree data from the one package
you've selected as the target, and only sends data to `localhost` (via `adb reverse`). No external
services, no data leaves the machine.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Same license as the
[Accessibility Test Framework for Android](https://github.com/google/Accessibility-Test-Framework-for-Android)
this project depends on.
