# A11y Auditor: live on-device issues, status fix, user-app list

## Context

Phase 2 (embedded server + browser dashboard) works, but using it on the phone means leaving the target app for Chrome to see findings. The user wants findings visible while navigating, a reliable service status, a cleaner target list, and a better Android home screen. Phase 2 branch `feature/embedded-server` is unmerged; its token risk N1 is accepted for now and goes on the future-work list.

Decided by the user:
- Issues show live inside the target app: floating bubble with a count; tap opens a panel over the target app with highlight boxes on elements.
- Issues also appear in the Auditor app after an audit (user agreed to keep this in the "while navigating too" option).
- UI focus: the Android app home screen only. No dashboard redesign.
- Target list shows only user-installed apps.
- Token risk N1 stays as is for now and is recorded for later.

Agreed after pushback:
- Browser dashboard stays, for laptop triage and export.
- Bubble shows **current-screen** count from the latest scan, never a running total: there is no dedup until Phase 3, and rescans repeat rows (9 became 18 in testing).

`impeccable` is not an available skill; use `frontend-design:frontend-design` guidance for the home screen.

## Goals

1. Status always tells the truth about whether the service is running.
2. Bubble + panel let the user see this screen's issues without leaving the target app.
3. After Stop, the Auditor app lists issues by screen with screenshot, highlight, and fix.
4. Target list contains user-installed apps; manual package entry still covers everything else.
5. Home screen has clear hierarchy and passes this project's own audit.

## Non-goals and anti-goals

- No dedup, statuses, or fixed-detection (Phase 3).
- No removal of web dashboard or server. No Compose migration: stay on Views + `viewBinding` + Material.
- Anti-goal: the overlay must not create issues, re-trigger scans, cover screenshots, or block taps on the target app outside the bubble and open panel.
- Anti-goal: panel must not inflate stored rows by causing rescans.

## Design

### 1. Service status (`MainActivity.kt`, `AuditorAccessibilityService.kt`)

- Likely cause, to confirm first: `isAccessibilityServiceEnabled()` compares only the full component string, and the Pixel 6 setting has been seen in short form (`pkg/.Class`). Also refresh happens only on resume, click or prefs change.
- Add companion `serviceBound = MutableStateFlow(false)`, set in `onServiceConnected` and `stopServer()` path. It is what the UI trusts, and `MainActivity` collects it. Keep separate from `serverStatus`.
- Enabled-but-not-bound (crashed, or suppressed by UIAutomation) gets its own text: service enabled but not running. Replace the string comparison with `ComponentName.unflattenFromString` for this check.
- Unit test the name comparison for both forms.

### 2. User apps (`AppListAdapter.kt`)

- In `launchableApps`, keep packages where `ApplicationInfo.FLAG_SYSTEM == 0`. This also hides updated system apps such as Chrome.
- Keep `manualPackageInput`; add one hint line that system apps can be typed there.
- `/api/v1/apps` keeps using `launchableApps`; dashboard falls back to package name for a manually entered target. Heuristic: some OEM preinstalled apps may still appear.

### 3. Live scan result (`AuditorAccessibilityService.kt`, new `LiveScan.kt`)

- `data class LiveIssue(severity, wcagSc, wcagLevel, elementDescription, description, fix, framework, bounds)` from `AuditIssue`; reuse `AuditIssue` directly if it fits.
- `MutableStateFlow<LiveScan?>` holding screen label and issues of the latest pass, **including zero issues**. Today `perWindow.isEmpty()` returns early; publish before that return, and clear on Stop.
- Published from `auditWindows` before the screenshot, so count does not wait on capture. `AuditStore` rows stay unchanged.
- Add an `is_auditing` re-check at the top of `runAudit`, and remove pending callbacks on Stop, so a queued pass cannot run after Stop.

### 4. Overlay (new `AuditOverlay.kt`, owned by the service)

- Window type `TYPE_ACCESSIBILITY_OVERLAY` through the service's `WindowManager`; no `SYSTEM_ALERT_WINDOW`. Shown only while auditing and only when a target window is on screen; removed on Stop and in `stopServer()`.
- **Bubble:** draggable, count from `LiveScan`, colour by highest severity, 48dp target, labelled for TalkBack ("3 issues on this screen, open"). Position saved in prefs.
- **Panel:** bottom sheet-style window, about 45% height, scrollable list: severity, WCAG SC, element, description, expandable fix with detected framework first. Close button. Selecting an issue draws its box.
- **Highlight:** separate non-touchable overlay window (`FLAG_NOT_TOUCHABLE`) drawing boxes from `bounds` in screen coordinates; correct for insets and rotation by using the window at full-screen layout. Boxes clear when the screen changes.
- While the panel is open: scanning pauses and the panel shows the snapshot it opened with. Closing resumes.
- **Scan loop guard:** ignore `TYPE_WINDOWS_CHANGED` events produced by the overlay's own add/remove/update, and pause scans during those changes. `ScreenNaming.targetWindows` already filters to the target package.
- **Screenshot:** hide bubble and boxes just before `takeScreenshot`, restore in the callback, and skip capture while the panel is open.
- Target app rotation, dialogs, FLAG_SECURE: overlay stays functional; screenshot behaviour unchanged.
- API 26+ supported; the panel works without screenshots.

### 5. Issues screen in the app (new `IssuesActivity.kt`, `IssueListAdapter.kt`, `activity_issues.xml`)

- Reads `AuditStore.issues()` on IO; screenshots via `AuditStore.screenshot()`, no HTTP and no token. Service owns the store today, so expose it through a companion accessor and show "enable service" when absent.
- Grouped by `screen`; each row shows severity, SC, element, thumbnail with highlight, expandable fix using `ApiJson`-stored `suggestedFix` JSON (parse in one small Kotlin helper).
- Entered from the home screen ("View issues (N)") and auto-offered after Stop. Rows repeat as stored: until Phase 3, group identical (element, SC, description) rows in this view for display only, with a count.
- Severity filter only. Search and export stay in the dashboard.

### 6. Home screen (`activity_main.xml`, `item_app.xml`, `MainActivity.kt`, theme)

- Top: one status card combining service + dashboard state with the single next action. Then target picker, then a primary Start/Stop anchored at the bottom. Secondary: View issues, Open dashboard, Copy laptop URL in one row or overflow.
- Search box for the app list. Material components, DayNight theme, contrast ≥ 4.5:1, 48dp targets, labelled controls. Remove the hardcoded "Select target app" and "Pick a target app first" strings.

### 7. Docs and future work

- Plan/spec: correct N1 text (open tab sends the token on retry, so any app binding 8080 while the service is off gets it without a reload). Add **Future: per-run session secret** to the Phase 2 plan.
- Future list also gets deferred minors N2–N6, API 26 smoke run, laptop browser and `npm run dev` checks.
- New spec `docs/superpowers/specs/2026-11-07-live-overlay-design.md` from this document; plan via `superpowers:writing-plans`.

## Order

1. Commit doc corrections. Status fix, then user-app filter (small, shippable alone).
2. Live scan + overlay bubble + panel + highlight.
3. Issues screen.
4. Home screen last, so it includes the new entry points.

Branch: continue on `feature/embedded-server` (builder's call to split). No push or merge without asking.

## Calls made in design (not yet the user's decisions)

- **Overlay in scans:** overlay windows carry a fixed `LayoutParams.title` and scans exclude that title. Home and issues screens are self-audited by typing our own package; panel and bubble are checked with TalkBack, not ATF.
- **Focus:** panel, bubble and highlight use `FLAG_NOT_FOCUSABLE`, so the target stays the active window and `BaseScreenTracker` is undisturbed.
- **API 26–29:** no screenshots, so issue rows are text-only with no placeholder. Panel highlights still work because they use live bounds.
- **Store ownership:** `AuditStore` moves to a process-wide instance in `AuditorApplication`, so issues stay readable with the service off. The service no longer closes it, and `stopServer()` loses `st.close()`. This replaces the companion accessor in section 5.
- **Stop:** keeps stored rows. `N` in "View issues (N)" counts display groups across all stored rows; there is no session id until Phase 3.
- **Clear:** the Issues screen gets Clear with a confirm dialog, using `store.clear()` plus the same SSE `clear` event, so no browser is needed.
- **Leaving the target:** on home, recents or another app, bubble and boxes hide and an open panel closes; bubble returns when a target window is back, with scanning resumed.
- **Bubble position:** one saved value as a fraction of screen size, shared by orientations and targets.

## Left to the builder

Exact panel layout, bubble visuals, drag handling, palette within contrast limits, helper names.

## Verification

- JVM/Robolectric: component-name match both forms; `launchableApps` filter; `LiveScan` publishes zero-issue passes and clears on Stop; no scan runs after Stop; display grouping.
- Pixel 6 `1A221FDF6006H7` (screencap and `input tap`; not `uiautomator dump`, which suppresses the service):
  - Status correct with short and full setting forms, and after toggling the service.
  - Target list has no system apps; typed `com.android.settings` still audits.
  - Bubble count changes per screen and returns to 0 on a clean screen; stored rows do not grow from opening/closing the panel.
  - Screenshots contain no bubble. Taps outside bubble/panel reach the target app. Highlight lines up in portrait and landscape.
  - Stop removes the overlay and no report lands afterward.
  - Issues screen matches dashboard data; port-in-use still shows failure with no FATAL.
- Run the auditor on itself for the home screen, panel and issues screen.
- `npm test -w dashboard`, `testDebugUnitTest assembleDebug`.
