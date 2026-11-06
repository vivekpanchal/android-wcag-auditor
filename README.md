# A11y Auditor — Runtime WCAG Auditor for Android

[![CI](https://github.com/vivekpanchal/android-wcag-auditor/actions/workflows/ci.yml/badge.svg)](https://github.com/vivekpanchal/android-wcag-auditor/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/vivekpanchal/android-wcag-auditor)](LICENSE)

Standalone accessibility auditor: install one APK, point it at any other app already on the
device, and watch WCAG-mapped accessibility issues stream into a local web dashboard as you
navigate that app. **No changes to the target app's code or build required** — debug or release,
yours or someone else's, doesn't matter.

## How it works

```
┌─────────────────┐   AccessibilityService    ┌─────────────────────────┐
│  Target app      │ ─── events + node tree ─→ │ A11y Auditor APK         │
│  (any app,       │                            │ ATF checks + screenshot  │
│  unmodified)      │                            │ Ktor server + SQLite     │
└─────────────────┘                            │ serves API + dashboard   │
                                                 └───────────┬─────────────┘
                                                             │ http://localhost:8080
                                       ┌─────────────────────┴───────────────┐
                                       ▼                                     ▼
                              Phone browser                    Laptop browser (adb forward)
```

The Auditor app registers an `AccessibilityService`. Once a target package + Start is set, the
service watches for window/content-change events from *that* package only (everything else,
including itself, is ignored). On each change it grabs every window that belongs to the target app
(the activity plus any dialog, bottom sheet or popup on top of it), runs each through Google's
[Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android)
(ATF), labels dialog/popup issues as `Activity › Title`, maps each finding to a WCAG 2.1 success
criterion (see `auditor-app/.../WcagMapping.kt`), optionally grabs a screenshot, and stores the
result in the app's own SQLite database. An embedded Ktor server in the same APK serves the
built React dashboard and the API, and streams new issues to the dashboard over SSE. Everything
runs on the phone; no separate backend is needed.

**Picking a target:** choose the target app in the Auditor app's list (icon, name and package for
each launchable app), or type a package name manually. Start and Stop work from the app or the
dashboard.

## Project layout

- `dashboard/` — React + Vite dashboard, bundled into the APK assets.
- `auditor-app/` — standalone Android app (Kotlin). Its own package (`com.a11yauditor.app`),
  installed independently of whatever you're auditing.

## Prerequisites

- Node.js 20.19+ (for `dashboard/`, covers Vite 8 and jsdom)
- Android Studio (to build `auditor-app/` — it'll fetch/repair the Gradle wrapper on first open)
- A device or emulator running Android 8.0 (API 26) or newer, with `adb` on your `PATH`
  - Screenshots in reported issues require Android 11 (API 30)+; on older devices the Auditor
    still reports every issue, just without a screenshot attached.

## Setup

### 1. Build the dashboard and install the app

From the repo root, then `auditor-app/`:

```bash
npm install && npm run build
cd auditor-app && ./gradlew installDebug
```

The build copies `dashboard/dist` into the APK assets. Or install a prebuilt APK directly:

```bash
adb install auditor-app.apk
```

Upgrading from the old Node-server setup? Run `adb reverse --remove tcp:8080` first so the old
reverse tunnel doesn't claim port 8080. Export your reports before upgrading: old session data
is not migrated.

### 2. Enable the accessibility service

On the device: **Settings → Accessibility → A11y Auditor → On**, or tap "Enable Accessibility
Service" inside the app, which jumps straight to that settings screen.

### 3. Open the dashboard

Either:

- On the phone, tap **Open dashboard** in the Auditor app (opens `http://localhost:8080` in the
  phone's browser), or
- On the laptop, run `adb forward tcp:8080 tcp:8080`, then tap **Copy laptop URL** in the app
  and open that URL in your laptop browser.

Sign-in lasts for that browser tab. A new tab needs the app's URL again.

### 4. Pick a target and go

1. Open the A11y Auditor app and pick the app you want audited from the list, or type its package
   name, e.g. `com.example.myapp`.
2. Tap **Start Auditing** — or start from the dashboard.
3. Switch to the target app and use it normally — navigate screens, open dialogs, scroll lists.
4. Watch issues appear live in the dashboard, grouped by screen, with a running count by severity.
5. Tap **Stop Auditing** — from either the dashboard or the app — when done.

### 5. Export a report

In the dashboard: **Export HTML** for a shareable report grouped by screen, or **Export CSV** for
raw data. **Clear Session** wipes the stored session to start a fresh audit run.

When ATF reports the failing element's bounds, both the dashboard and the HTML export draw a
highlighted box over it on the screenshot (click a thumbnail to enlarge).

### Developing the dashboard

`npm run dev` starts the Vite dev server on `:5173` with hot-module-reload. It proxies `/api` to
`localhost:8080`, so run `adb forward tcp:8080 tcp:8080` first, then open
`http://localhost:5173/?t=<token>` using the token from **Copy laptop URL**. `npm test` runs the
dashboard tests.

## WCAG mapping

`auditor-app/app/src/main/java/com/a11yauditor/app/WcagMapping.kt` maps each ATF check class to a
WCAG 2.1 success criterion + level (e.g. `TouchTargetSizeCheck` → 2.5.5 AAA,
`TextContrastCheck` → 1.4.3 AA). Extend it by adding another `Criterion` entry keyed by the ATF
check's simple class name — everything downstream (dashboard) already handles arbitrary
SC/level values.

Each mapped check also carries fix guidance (`WcagMapping.Fix`): a one-line summary, a Views
snippet, a Compose snippet and a docs link. The service detects whether the flagged element sits
under a Compose host and the dashboard shows that framework's snippet first.

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

The Gradle wrapper is committed (`auditor-app/gradlew`, pinned by `distributionSha256Sum`), so
`./gradlew` works from a fresh clone with only a JDK installed.

## Privacy

Everything is local. The Auditor app only reads accessibility-tree data from the one package
you've selected as the target, and stores it on the device, serving it only on `localhost`. No external
services, no data leaves the device unless you export a report.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Same license as the
[Accessibility Test Framework for Android](https://github.com/google/Accessibility-Test-Framework-for-Android)
this project depends on.
