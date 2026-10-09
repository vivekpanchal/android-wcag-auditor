# Phase 0–1: Review Fixes, Fix Guidance, Dialog/Popup Coverage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the code-review findings on the current dashboard/Gradle work (Phase 0), then ship per-check fix guidance and dialog/bottom-sheet/popup coverage on the existing Node stack (Phase 1).

**Architecture:** Phase 0 is local fixes in `dashboard/` and `auditor-app/` build files. Phase 1 adds a `Fix` to every `WcagMapping` entry, detects Views vs Compose per flagged element, and sends the fix as a JSON string in the existing `suggestedFix` field (already stored and broadcast end to end by the Node server). The accessibility service switches from `rootInActiveWindow` to every target-app window from `getWindows()`, labelling dialogs/popups as `Activity › Title`. Window selection and labelling live in a pure, JVM-tested `ScreenNaming` object.

**Tech Stack:** Kotlin (Android, minSdk 26, ATF 4.1.1, JUnit 4), React 19 + Vite + Vitest + Testing Library, Node 22 (`node:test`), Gradle wrapper 8.14.5.

**Spec:** `docs/superpowers/specs/2026-10-09-live-audit-embedded-server-design.md` (this plan covers §11 phases 0 and 1; phases 2 and 3 get their own plans).

## Global Constraints

- **Commit gate (user instruction, 2026-10-09): do NOT run `git commit` (or `git add`).** Every "Commit" step below is skipped: leave changes in the working tree and list the changed files in the task report. Only lift this when the user explicitly says commits are allowed.
- The working tree already holds the user's uncommitted dashboard and Gradle changes. Build on them; never revert, stash or reset them.
- No new runtime dependencies in this plan (app or dashboard or server).
- Android: `minSdk = 26`, `compileSdk = 34`, ATF `4.1.1`, Kotlin `1.9.24`, JVM target 17.
- Server API shape is unchanged. `suggestedFix` stays a string column; Phase 1 puts a JSON string in it: `{"summary","views","compose","docUrl","framework"}` with `framework` ∈ `"views" | "compose"`.
- Doc links are rendered only when they start with `https://`.
- Text contrast in the dashboard's own UI: ≥ 4.5:1 for normal text (SC 1.4.3).

## Review Focus

1. **Legacy rows:** issues already in `server/data.db` hold `suggestedFix` as plain text (or null). Expected: shown as the fix summary, never a JSON parse crash. Tests: Task 6 (`parseFix` + export).
2. **Hostile doc link:** anything on localhost can POST `/report`, so `docUrl` may be `javascript:…`. Expected: link not rendered in dashboard or HTML export. Tests: Task 6.
3. **IDs reused after a clear done while the dashboard was closed:** server resets `nextId` to 1, so old "resolved" IDs would hide new issues. Expected: new issues never start resolved. Test: Task 1 (`resolvedKey` includes timestamp).
4. **`TYPE_WINDOWS_CHANGED` with null `packageName`:** a popup opening must still trigger a scan. Test: Task 7 (`shouldScan`).
5. **ATF throws on one window** (e.g. popup mid-dismiss): other windows' issues still reported. Per-window try/catch in Task 7; verified on device in Task 8 (needs a real window stack).

---

### Task 1: Resolved-state fixes (stale IDs, count, accessible name)

**Files:**
- Modify: `dashboard/src/logic.js` (append `resolvedKey`)
- Modify: `dashboard/src/App.jsx` (resolved state handling, `IssueCard`, stat tile, comment wording)
- Test: `dashboard/src/App.test.jsx` (append)

**Interfaces:**
- Produces: `resolvedKey(issue): string` in `logic.js` — `"<id>:<timestamp>"`.

- [ ] **Step 1: Write the failing tests** — append to `dashboard/src/App.test.jsx`:

```jsx
import { resolvedKey } from './logic';

describe('resolvedKey', () => {
  it('combines id and timestamp so a reused id is a different key', () => {
    expect(resolvedKey({ id: 1, timestamp: 1000 })).toBe('1:1000');
    expect(resolvedKey({ id: 1, timestamp: 2000 })).not.toBe(resolvedKey({ id: 1, timestamp: 1000 }));
  });
});

describe('App resolved state', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    localStorage.clear();
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal(
      'fetch',
      mockFetchJson({
        '/issues': [],
        '/control': { targetPackage: null, auditing: false },
        '/apps': [],
        '/device': { online: false },
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const send = (ws, msg) => act(() => { ws.onmessage({ data: JSON.stringify(msg) }); });
  const issue = (id, timestamp) => ({ id, timestamp, severity: 'serious', elementDescription: `El ${id}`, description: 'd' });

  it('does not treat a new issue with a reused id as resolved', async () => {
    localStorage.setItem('a11y-auditor-resolved', JSON.stringify(['1:1000']));
    render(<App />);
    await act(async () => {});
    send(FakeWebSocket.instances[0], { type: 'issues', issues: [issue(1, 2000)] });

    expect(screen.getByRole('checkbox', { name: 'Mark resolved' })).not.toBeChecked();
    expect(document.querySelector('.stat-resolved .stat-value').textContent).toBe('0/1');
  });

  it('resets resolved state on a WebSocket "clear" from elsewhere', async () => {
    render(<App />);
    await act(async () => {});
    const ws = FakeWebSocket.instances[0];
    send(ws, { type: 'issues', issues: [issue(1, 1000)] });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Mark resolved' }));
    expect(document.querySelector('.stat-resolved .stat-value').textContent).toBe('1/1');

    send(ws, { type: 'clear' });
    expect(document.querySelector('.stat-resolved .stat-value').textContent).toBe('0/0');
    expect(JSON.parse(localStorage.getItem('a11y-auditor-resolved'))).toEqual([]);
  });

  it('links each resolve checkbox to its issue element for screen readers', async () => {
    render(<App />);
    await act(async () => {});
    send(FakeWebSocket.instances[0], { type: 'issues', issues: [issue(7, 1000)] });

    expect(screen.getByRole('checkbox', { name: 'Mark resolved' })).toHaveAccessibleDescription('El 7');
  });
});
```

Move the new `import { resolvedKey } from './logic';` up into the existing `import { severityRank, matchApps, filterIssues, groupIssues } from './logic';` line instead of a second import.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd dashboard && npx vitest run src/App.test.jsx`
Expected: FAIL — `resolvedKey is not a function` / not exported, and the resolved-state assertions fail.

- [ ] **Step 3: Implement**

Append to `dashboard/src/logic.js`:

```js
// Key for the per-browser "resolved" set. The server restarts ids at 1 after
// a clear, so id alone would mark brand-new issues as already resolved.
export function resolvedKey(issue) {
  return `${issue.id}:${issue.timestamp}`;
}
```

In `dashboard/src/App.jsx`:

1. Import: `import { matchApps, filterIssues, groupIssues, resolvedKey } from './logic';`
2. In `saveResolved`, replace the comment `// ponytail: best-effort persistence, ignore quota/private-mode failures` with `// Best-effort persistence: ignore quota/private-mode failures.`
3. Replace `IssueCard`'s element paragraph and resolve label with:

```jsx
      <p className="issue-element" id={`issue-${issue.id}-element`}><strong>{issue.elementDescription}</strong></p>
```

```jsx
      <label className="issue-resolve">
        <input
          type="checkbox"
          checked={resolved}
          onChange={() => onToggleResolved(resolvedKey(issue))}
          aria-describedby={`issue-${issue.id}-element`}
        />
        {resolved ? 'Resolved' : 'Mark resolved'}
      </label>
```

4. Replace the `toggleResolved` function with a pure updater plus an effect that persists:

```jsx
  useEffect(() => saveResolved(resolvedIds), [resolvedIds]);

  const toggleResolved = (key) => {
    setResolvedIds((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };
```

5. In the WebSocket handler replace `} else if (msg.type === 'clear') setIssues([]);` with:

```jsx
        } else if (msg.type === 'clear') {
          setIssues([]);
          setResolvedIds(new Set());
        }
```

6. Add after `severityCounts`:

```jsx
  const resolvedCount = useMemo(
    () => issues.filter((i) => resolvedIds.has(resolvedKey(i))).length,
    [issues, resolvedIds]
  );
```

7. In `filtered`, use `!resolvedIds.has(resolvedKey(i))` instead of `!resolvedIds.has(i.id)`.
8. In `clearSession`, delete the `saveResolved(new Set());` line (the effect persists it).
9. Stat tile: `<span className="stat-value">{resolvedCount}/{issues.length}</span>`.
10. `IssueCard` usage: `resolved={resolvedIds.has(resolvedKey(issue))}`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd dashboard && npx vitest run`
Expected: PASS, all tests (19 existing + 4 new).

- [ ] **Step 5: Commit** — SKIPPED (commit gate). Report changed files: `dashboard/src/logic.js`, `dashboard/src/App.jsx`, `dashboard/src/App.test.jsx`.

---

### Task 2: Dashboard contrast fixes

**Files:**
- Modify: `dashboard/src/index.css` (new tokens)
- Modify: `dashboard/src/App.css` (`.btn-primary`, `.btn-primary.btn-danger`, `.issue-card.resolved`, hover shadows)
- Test: `dashboard/src/contrast.test.js` (create)

**Interfaces:**
- Produces CSS tokens: `--accent-strong` (button fill), `--danger-strong` (danger button fill), `--link` (link text, per theme). Task 6 uses `--link`.

- [ ] **Step 1: Write the failing test** — create `dashboard/src/contrast.test.js`:

```js
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

const indexCss = readFileSync(new URL('./index.css', import.meta.url), 'utf8');
const appCss = readFileSync(new URL('./App.css', import.meta.url), 'utf8');

function block(selector) {
  const start = indexCss.indexOf(selector);
  if (start < 0) throw new Error(`selector ${selector} not found`);
  return indexCss.slice(start, indexCss.indexOf('}', start));
}
const light = block(':root {');
const dark = block(':root[data-theme="dark"] {');

// Tokens not redefined in the dark block inherit the light value.
function token(name, theme) {
  const re = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`);
  const m = theme.match(re) || light.match(re);
  if (!m) throw new Error(`token --${name} not found`);
  return m[1];
}

function luminance(hex) {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

describe('dashboard colour contrast (SC 1.4.3)', () => {
  for (const [name, theme] of [['light', light], ['dark', dark]]) {
    it(`white button text on primary fill passes in ${name}`, () => {
      expect(contrast('#ffffff', token('accent-strong', theme))).toBeGreaterThanOrEqual(4.5);
    });
    it(`white button text on danger fill passes in ${name}`, () => {
      expect(contrast('#ffffff', token('danger-strong', theme))).toBeGreaterThanOrEqual(4.5);
    });
    it(`link text on card surface passes in ${name}`, () => {
      expect(contrast(token('link', theme), token('surface-1', theme))).toBeGreaterThanOrEqual(4.5);
    });
  }

  it('filled buttons use the strong tokens and never brighten on hover', () => {
    expect(appCss).toMatch(/\.btn-primary\s*\{[^}]*background:\s*var\(--accent-strong\)/);
    expect(appCss).toMatch(/\.btn-primary\.btn-danger\s*\{[^}]*background:\s*var\(--danger-strong\)/);
    expect(appCss).not.toMatch(/brightness\(1\.\d+\)/);
  });

  it('resolved cards keep full-contrast text (no opacity fade)', () => {
    expect(appCss).not.toMatch(/\.issue-card\.resolved\s*\{[^}]*opacity/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd dashboard && npx vitest run src/contrast.test.js`
Expected: FAIL — `token --accent-strong not found`, and the CSS rule assertions fail.

- [ ] **Step 3: Implement**

`dashboard/src/index.css` — in the light `:root {` block, after `--accent-tint: #e7f0fb;` add:

```css
  --accent-strong:  #1f63b5;   /* filled-button background, white text 5.9:1 */
  --danger-strong:  #b42c2c;   /* filled danger-button background, white text 6.2:1 */
  --link:           #1f63b5;   /* link text on surfaces */
```

In **both** dark blocks (the `@media (prefers-color-scheme: dark)` one and `:root[data-theme="dark"]`), after `--accent-tint: #16283e;` add:

```css
    --link:           #6aa6ee;
```

(Use the block's existing indentation. `--accent-strong` / `--danger-strong` are not redefined in dark: white text stays ≥ 4.5:1 on them.)

`dashboard/src/App.css`:

Replace the `.btn-primary`, `.btn-primary:hover` and `.btn-primary.btn-danger` rules with:

```css
.btn-primary {
  border-color: var(--accent-strong);
  background: var(--accent-strong);
  color: #fff;
  font-weight: 600;
}

.btn-primary:hover {
  border-color: var(--accent-strong);
  filter: brightness(0.92);
}

.btn-primary.btn-danger {
  border-color: var(--danger-strong);
  background: var(--danger-strong);
  color: #fff;
}
```

Replace `.issue-card.resolved { opacity: 0.55; }` with:

```css
/* Resolved is still readable content, so no opacity fade (would drop text below 4.5:1). */
.issue-card.resolved {
  background: var(--page-plane);
  border-style: dashed;
}

.issue-card.resolved .issue-element {
  text-decoration: line-through;
}
```

Delete the `.stat-tile:hover { … }` and `.issue-card:hover { … }` rules (non-interactive elements should not look clickable).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd dashboard && npx vitest run`
Expected: PASS, all tests.

- [ ] **Step 5: Commit** — SKIPPED (commit gate). Report changed files: `dashboard/src/index.css`, `dashboard/src/App.css`, `dashboard/src/contrast.test.js`.

---

### Task 3: Gradle wrapper hygiene and CI

**Files:**
- Modify: `auditor-app/gradle/wrapper/gradle-wrapper.properties`
- Create: `.gitattributes`
- Modify: `.github/workflows/ci.yml` (android job)
- Modify: `README.md:182-184` (wrapper paragraph)

**Interfaces:** none (build-only).

- [ ] **Step 1: Fetch and verify the official distribution checksum**

Run: `curl -s https://services.gradle.org/distributions/gradle-8.14.5-bin.zip.sha256`
Expected: `6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854` (value reported by the code review). If it differs, use the value curl returned.

- [ ] **Step 2: Pin it** — add this line to `auditor-app/gradle/wrapper/gradle-wrapper.properties` after `distributionUrl=…`:

```properties
distributionSha256Sum=6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854
```

- [ ] **Step 3: Create `.gitattributes`** at the repo root so `gradlew` keeps LF on Windows checkouts (CRLF breaks it on Linux/macOS CI):

```gitattributes
auditor-app/gradlew text eol=lf
*.bat text eol=crlf
*.jar binary
```

- [ ] **Step 4: Run the Android unit tests through the wrapper**

Run: `cd auditor-app && ./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, `WcagMappingTest` passes. First run downloads Gradle 8.14.5 and the JDK 21 daemon toolchain (`gradle-daemon-jvm.properties`).

If it fails with a Kotlin Gradle plugin compatibility error (KGP 1.9.24 vs Gradle 8.14.5): change `distributionUrl` to `https\://services.gradle.org/distributions/gradle-8.7-bin.zip` (the version CI already uses and AGP 8.5.2's minimum), replace `distributionSha256Sum` with the output of `curl -s https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256`, and rerun. Report which version was kept.

- [ ] **Step 5: Switch CI to the wrapper** — in `.github/workflows/ci.yml`, replace the android job's last two steps (the comment, `setup-gradle` with `gradle-version: 8.7`, and `run: gradle testDebugUnitTest assembleDebug`) with:

```yaml
      # Caching only; the version comes from the committed wrapper.
      - uses: gradle/actions/setup-gradle@v4
      # `bash gradlew` so the script runs even if its exec bit was lost on a Windows commit.
      - run: bash gradlew testDebugUnitTest assembleDebug
```

- [ ] **Step 6: Update README** — replace the paragraph at `README.md:182-184` ("The Gradle wrapper jar itself isn't committed …") with:

```markdown
The Gradle wrapper is committed (`auditor-app/gradlew`, pinned by `distributionSha256Sum`), so
`./gradlew` works from a fresh clone with only a JDK installed.
```

- [ ] **Step 7: Commit** — SKIPPED (commit gate). Report changed files. Note for the user: when committing later, `auditor-app/gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and `gradle/gradle-daemon-jvm.properties` are currently untracked and must be added with them.

---

### Task 4: Fix guidance data in `WcagMapping`

**Files:**
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/WcagMapping.kt`
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/WcagMappingTest.kt`

**Interfaces:**
- Produces:
  - `data class WcagMapping.Fix(val summary: String, val views: String, val compose: String, val docUrl: String)`
  - `data class WcagMapping.Criterion(val sc: String, val level: String, val title: String, val fix: Fix? = null)`
  - `val WcagMapping.checkClasses: Set<String>`
  - `forCheckClass(simpleClassName: String): Criterion` (unchanged signature; mapped entries now carry `fix`, `UNKNOWN.fix == null`)

- [ ] **Step 1: Write the failing tests** — in `WcagMappingTest.kt`:

Change imports to:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
```

In `known check classes map to their documented WCAG criterion`, replace the loop body with (criteria now carry a fix; this test pins only SC/level/title):

```kotlin
        for ((checkClass, criterion) in expected) {
            assertEquals(checkClass, criterion, WcagMapping.forCheckClass(checkClass).copy(fix = null))
        }
```

Add tests:

```kotlin
    @Test
    fun `every mapped check has complete fix guidance`() {
        assertEquals(14, WcagMapping.checkClasses.size)
        for (checkClass in WcagMapping.checkClasses) {
            val fix = WcagMapping.forCheckClass(checkClass).fix
            assertNotNull("$checkClass has no fix", fix)
            listOf(fix!!.summary, fix.views, fix.compose, fix.docUrl).forEach {
                assertTrue("$checkClass has a blank fix field", it.isNotBlank())
            }
            assertTrue("$checkClass docUrl must be https", fix.docUrl.startsWith("https://"))
        }
    }

    @Test
    fun `unmapped check has no fix`() {
        assertNull(WcagMapping.forCheckClass("SomeCompletelyMadeUpCheckName").fix)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd auditor-app && ./gradlew testDebugUnitTest --tests "com.a11yauditor.app.WcagMappingTest"`
Expected: compile FAIL — `Unresolved reference: fix` / `checkClasses`.

- [ ] **Step 3: Implement** — in `WcagMapping.kt`:

Add one paragraph to the class KDoc, before the closing `*/`:

```kotlin
 *
 * Each entry also carries a [Fix]: generic, per-check remediation with a
 * Views and a Compose snippet. ATF's own result message (which has the
 * specifics, e.g. the measured contrast ratio) stays the issue description;
 * the fix sits beside it. `WcagMappingTest` fails if any check lacks one.
```

Replace `data class Criterion(...)` with:

```kotlin
    data class Fix(val summary: String, val views: String, val compose: String, val docUrl: String)

    data class Criterion(val sc: String, val level: String, val title: String, val fix: Fix? = null)
```

Add constants under `UNKNOWN`:

```kotlin
    private const val DOC_GUIDE = "https://developer.android.com/guide/topics/ui/accessibility/apps"
    private const val DOC_LABELS = "https://support.google.com/accessibility/android/answer/7158690"
    private const val DOC_TOUCH = "https://support.google.com/accessibility/android/answer/7101858"
    private const val DOC_CONTRAST = "https://support.google.com/accessibility/android/answer/7158390"
```

Replace the `byCheckClass` map's entries (keep every existing comment line exactly as it is above its entry; only the `"X" to Criterion(...)` lines change):

```kotlin
        "SpeakableTextPresentCheck" to Criterion("1.1.1", "A", "Non-text Content", Fix(
            "Give the element an accessible name, or hide it from accessibility services if it is decorative.",
            "android:contentDescription=\"@string/close\"\n<!-- decorative: android:importantForAccessibility=\"no\" -->",
            "Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))\n// decorative: contentDescription = null",
            DOC_LABELS,
        )),
        "EditableContentDescCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Remove contentDescription from the editable field; label it with a hint or a separate label instead.",
            "<TextView android:labelFor=\"@id/email\" android:text=\"@string/email\" />\n<EditText android:id=\"@+id/email\" android:hint=\"@string/email\" />",
            "TextField(value, onValueChange, label = { Text(stringResource(R.string.email)) })",
            DOC_LABELS,
        )),
        "TouchTargetSizeCheck" to Criterion("2.5.5", "AAA", "Target Size", Fix(
            "Make the touch target at least 48x48dp (padding counts).",
            "android:minWidth=\"48dp\"\nandroid:minHeight=\"48dp\"",
            "IconButton(onClick) { … } // already 48dp\nModifier.minimumInteractiveComponentSize()",
            DOC_TOUCH,
        )),
        "TextContrastCheck" to Criterion("1.4.3", "AA", "Contrast (Minimum)", Fix(
            "Use text and background colours with at least 4.5:1 contrast (3:1 for 18sp+, or 14sp+ bold).",
            "android:textColor=\"@color/on_surface\" <!-- verify 4.5:1 against the background -->",
            "Text(text, color = MaterialTheme.colorScheme.onSurface)",
            DOC_CONTRAST,
        )),
        "ImageContrastCheck" to Criterion("1.4.11", "AA", "Non-text Contrast", Fix(
            "Give icons and meaningful graphics at least 3:1 contrast against their background.",
            "app:tint=\"@color/on_surface\"",
            "Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface)",
            DOC_CONTRAST,
        )),
        "DuplicateSpeakableTextCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Give elements with different actions different accessible names, e.g. include the item name.",
            "view.contentDescription = getString(R.string.delete_item, item.name) // \"Delete %s\"",
            "Modifier.semantics { contentDescription = \"Delete ${'$'}{item.name}\" }",
            DOC_LABELS,
        )),
        "DuplicateClickableBoundsCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Make only one of the overlapping views clickable, normally the outer container.",
            "<!-- keep clickable on the container, remove it from the child -->\nandroid:clickable=\"false\"",
            "Row(Modifier.clickable(onClick = onOpen)) { Text(title) } // no clickable on the child",
            DOC_GUIDE,
        )),
        "ClassNameCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Report a standard role for the custom view so assistive technology knows what it is.",
            "override fun getAccessibilityClassName(): CharSequence = Button::class.java.name",
            "Modifier.semantics { role = Role.Button }",
            DOC_GUIDE,
        )),
        "ClickableSpanCheck" to Criterion("2.1.1", "A", "Keyboard", Fix(
            "Use URLSpan for links, or expose ClickableSpans so accessibility services can activate them.",
            "ViewCompat.enableAccessibleClickableSpanSupport(textView)\n// or: SpannableString with URLSpan(url)",
            "buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(\"Terms\") } }",
            DOC_GUIDE,
        )),
        "RedundantDescriptionCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Remove role or state words (\"button\", \"selected\") from the label; the role already conveys them.",
            "android:contentDescription=\"@string/submit\" <!-- \"Submit\", not \"Submit button\" -->",
            "Modifier.semantics { contentDescription = \"Submit\" } // not \"Submit button\"",
            DOC_LABELS,
        )),
        "TraversalOrderCheck" to Criterion("2.4.3", "A", "Focus Order", Fix(
            "Remove conflicting traversalBefore/After attributes; order the layout to match the reading order.",
            "<!-- remove, or make consistent: -->\nandroid:accessibilityTraversalBefore=\"@id/next\"",
            "Modifier.semantics { isTraversalGroup = true } // on the parent\nModifier.semantics { traversalIndex = 1f } // on children",
            DOC_GUIDE,
        )),
        "LinkPurposeUnclearCheck" to Criterion("2.4.4", "A", "Link Purpose (In Context)", Fix(
            "Make the link text describe its destination, not \"click here\" or \"more\".",
            "<string name=\"privacy_link\">Read the privacy policy</string>",
            "withLink(LinkAnnotation.Url(url)) { append(\"Read the privacy policy\") }",
            DOC_GUIDE,
        )),
        "TextSizeCheck" to Criterion("1.4.4", "AA", "Resize Text", Fix(
            "Size text in sp and let its container grow (no fixed height) so it scales with the font-size setting.",
            "android:textSize=\"16sp\"\nandroid:layout_height=\"wrap_content\"",
            "Text(text, fontSize = 16.sp, modifier = Modifier.heightIn(min = 48.dp)) // not Modifier.height(…)",
            DOC_GUIDE,
        )),
        "UnexposedTextCheck" to Criterion("1.1.1", "A", "Non-text Content", Fix(
            "Use real text instead of text baked into an image, or put the image's text in its accessible name.",
            "android:contentDescription=\"@string/sale_banner\" <!-- \"Summer sale: 50% off\" -->",
            "Image(painter, contentDescription = \"Summer sale: 50% off\")",
            DOC_LABELS,
        )),
```

Add below `forCheckClass`:

```kotlin
    val checkClasses: Set<String> get() = byCheckClass.keys
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd auditor-app && ./gradlew testDebugUnitTest --tests "com.a11yauditor.app.WcagMappingTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Verify every doc URL resolves**

Run:

```bash
for u in https://developer.android.com/guide/topics/ui/accessibility/apps \
         https://support.google.com/accessibility/android/answer/7158690 \
         https://support.google.com/accessibility/android/answer/7101858 \
         https://support.google.com/accessibility/android/answer/7158390; do
  echo "$(curl -s -o /dev/null -w '%{http_code}' -L "$u") $u"
done
```

Expected: `200` for each. Open each Google support page and check its title matches its constant (content labels / touch target / colour contrast). Any URL that fails or has the wrong topic: replace that constant's value with `DOC_GUIDE`'s URL.

- [ ] **Step 6: Commit** — SKIPPED (commit gate). Report changed files.

---

### Task 5: Detect Views vs Compose and send the fix from the device

**Files:**
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/Framework.kt`
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/ReportSender.kt` (`AuditIssue`, JSON body)
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/AuditorAccessibilityService.kt` (`checkHierarchy`)
- Modify: `README.md` (WCAG mapping section)
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/FrameworkTest.kt` (create)

**Interfaces:**
- Consumes: `WcagMapping.Fix`, `Criterion.fix` (Task 4).
- Produces:
  - `fun frameworkFor(ancestorClassNames: Sequence<CharSequence?>): String` → `"compose"` or `"views"`.
  - `AuditIssue(severity, wcagSc, wcagLevel, elementDescription, description, fix: WcagMapping.Fix?, framework: String, bounds: Rect? = null)` — replaces the `suggestedFix: String?` field.
  - Report JSON: `suggestedFix` = `JSONObject{summary, views, compose, docUrl, framework}.toString()` when `fix != null`.

- [ ] **Step 1: Verify the ATF parent API with `javap`**

Run:

```bash
JAR=$(find ~/.gradle/caches/modules-2/files-2.1/com.google.android.apps.common.testing.accessibility.framework -name 'accessibility-test-framework-4.1.1.jar' | head -1)
javap -cp "$JAR" com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement | grep -E 'getParentView|getClassName'
```

Expected: lines containing `getParentView()` returning `ViewHierarchyElement` and `getClassName()` returning `java.lang.CharSequence`. If the parent getter has a different name, use that name in Step 5 (`it.parentView` → the Kotlin property for it) and note it in the task report.

- [ ] **Step 2: Write the failing test** — create `FrameworkTest.kt`:

```kotlin
package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameworkTest {

    @Test
    fun `element under an AndroidComposeView is compose`() {
        val chain = sequenceOf("android.view.View", "androidx.compose.ui.platform.AndroidComposeView", "android.widget.FrameLayout")
        assertEquals("compose", frameworkFor(chain))
    }

    @Test
    fun `element under a ComposeView host is compose`() {
        assertEquals("compose", frameworkFor(sequenceOf("android.view.View", "androidx.compose.ui.platform.ComposeView")))
    }

    @Test
    fun `plain view hierarchy is views, null class names ignored`() {
        assertEquals("views", frameworkFor(sequenceOf("android.widget.Button", null, "android.widget.LinearLayout")))
    }

    @Test
    fun `empty ancestry is views`() {
        assertEquals("views", frameworkFor(emptySequence()))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd auditor-app && ./gradlew testDebugUnitTest --tests "com.a11yauditor.app.FrameworkTest"`
Expected: compile FAIL — `Unresolved reference: frameworkFor`.

- [ ] **Step 4: Implement `Framework.kt`**

```kotlin
package com.a11yauditor.app

/**
 * "compose" if the element or any ancestor is a Compose host view
 * (AndroidComposeView, or the ComposeView wrapping it), else "views".
 * Takes class names rather than ATF elements so it is JVM-testable.
 */
fun frameworkFor(ancestorClassNames: Sequence<CharSequence?>): String =
    if (ancestorClassNames.any { it?.contains("ComposeView") == true }) "compose" else "views"
```

- [ ] **Step 5: Wire it into the device code**

`ReportSender.kt` — in `AuditIssue`, replace `val suggestedFix: String?,` with:

```kotlin
    val fix: WcagMapping.Fix?,
    // "views" or "compose", so the dashboard shows the matching fix snippet first.
    val framework: String,
```

and replace `issue.suggestedFix?.let { put("suggestedFix", it) }` with:

```kotlin
                        // Sent as a JSON string in the existing suggestedFix field, so the
                        // server stores it unchanged; the dashboard parses it.
                        issue.fix?.let { fix ->
                            put("suggestedFix", JSONObject().apply {
                                put("summary", fix.summary)
                                put("views", fix.views)
                                put("compose", fix.compose)
                                put("docUrl", fix.docUrl)
                                put("framework", issue.framework)
                            }.toString())
                        }
```

`AuditorAccessibilityService.kt` — in `checkHierarchy`'s `AuditIssue(...)` call, replace `suggestedFix = null,` with:

```kotlin
                    fix = criterion.fix,
                    framework = frameworkFor(generateSequence(element) { it.parentView }.map { it.className }),
```

- [ ] **Step 6: Run all unit tests and build**

Run: `cd auditor-app && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: README** — in the "## WCAG mapping" section, after the first paragraph, add:

```markdown
Each mapped check also carries fix guidance (`WcagMapping.Fix`): a one-line summary, a Views
snippet, a Compose snippet and a docs link. The service detects whether the flagged element sits
under a Compose host and the dashboard shows that framework's snippet first.
```

- [ ] **Step 8: Commit** — SKIPPED (commit gate). Report changed files.

---

### Task 6: Show fix guidance in the dashboard and the exports

**Files:**
- Modify: `dashboard/src/logic.js` (append `parseFix`)
- Modify: `dashboard/src/App.jsx` (`FixPanel`, `IssueCard`)
- Modify: `dashboard/src/App.css` (`.issue-fix` styles)
- Modify: `server/src/export.js` (HTML + CSV fix rendering)
- Test: `dashboard/src/App.test.jsx` (append), `server/src/export.test.js` (create)

**Interfaces:**
- Consumes: `suggestedFix` JSON string contract (Task 5); `--link` token (Task 2).
- Produces: `parseFix(raw): {summary, views?, compose?, docUrl?, framework?} | null` in `dashboard/src/logic.js`. (`server/src/export.js` gets its own copy; the server is deleted in Phase 2 and its export moves into the dashboard, which will then reuse `logic.js`'s.)

- [ ] **Step 1: Write the failing dashboard tests** — append to `dashboard/src/App.test.jsx` (add `parseFix` to the existing `./logic` import):

```jsx
describe('parseFix', () => {
  it('parses the structured fix JSON from the device', () => {
    const raw = JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'compose' });
    expect(parseFix(raw)).toEqual({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'compose' });
  });

  it('treats legacy plain text as the summary', () => {
    expect(parseFix('Add a label')).toEqual({ summary: 'Add a label' });
  });

  it('returns null for missing fixes', () => {
    expect(parseFix(null)).toBeNull();
    expect(parseFix('')).toBeNull();
  });
});

describe('App fix guidance', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
    vi.stubGlobal(
      'fetch',
      mockFetchJson({
        '/issues': [],
        '/control': { targetPackage: null, auditing: false },
        '/apps': [],
        '/device': { online: false },
      })
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const showIssue = async (suggestedFix) => {
    render(<App />);
    await act(async () => {});
    act(() => {
      FakeWebSocket.instances[0].onmessage({
        data: JSON.stringify({
          type: 'issues',
          issues: [{ id: 1, timestamp: 1, severity: 'serious', elementDescription: 'Btn', description: 'd', suggestedFix }],
        }),
      });
    });
  };

  it('shows the detected framework snippet first and links https docs', async () => {
    await showIssue(JSON.stringify({
      summary: 'Make it 48dp', views: 'android:minHeight="48dp"', compose: 'Modifier.minimumInteractiveComponentSize()',
      docUrl: 'https://developer.android.com/guide/topics/ui/accessibility/apps', framework: 'compose',
    }));

    expect(screen.getByText(/How to fix: Make it 48dp/)).toBeInTheDocument();
    const labels = [...document.querySelectorAll('.fix-snippet-label')].map((el) => el.textContent);
    expect(labels).toEqual(['Compose (detected)', 'Views']);
    expect(screen.getByRole('link', { name: 'Android docs' })).toHaveAttribute('href', 'https://developer.android.com/guide/topics/ui/accessibility/apps');
  });

  it('puts Views first when the element is a View', async () => {
    await showIssue(JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'https://x', framework: 'views' }));
    const labels = [...document.querySelectorAll('.fix-snippet-label')].map((el) => el.textContent);
    expect(labels).toEqual(['Views (detected)', 'Compose']);
  });

  it('never renders a non-https doc link', async () => {
    await showIssue(JSON.stringify({ summary: 'S', views: 'V', compose: 'C', docUrl: 'javascript:alert(1)', framework: 'views' }));
    expect(screen.queryByRole('link', { name: 'Android docs' })).toBeNull();
  });

  it('shows a legacy plain-text fix as the summary', async () => {
    await showIssue('Add a content description');
    expect(screen.getByText(/How to fix: Add a content description/)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Write the failing export tests** — create `server/src/export.test.js`:

```js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toCsv, toHtml } = require('./export');

const base = {
  id: 1, packageName: 'com.example', screen: 'Main', timestamp: 0, severity: 'serious',
  wcagSC: '2.5.5', wcagLevel: 'AAA', elementDescription: 'Button', description: 'Too small',
};
const fix = (over = {}) => JSON.stringify({
  summary: 'Make it <48dp>', views: 'android:minHeight="48dp"',
  compose: 'Modifier.minimumInteractiveComponentSize()',
  docUrl: 'https://developer.android.com/guide/topics/ui/accessibility/apps', framework: 'compose', ...over,
});

test('HTML shows the escaped summary, the detected framework snippet and the https doc link', () => {
  const html = toHtml([{ ...base, suggestedFix: fix() }]);
  assert.match(html, /Make it &lt;48dp&gt;/);
  assert.match(html, /Modifier\.minimumInteractiveComponentSize\(\)/);
  assert.doesNotMatch(html, /android:minHeight/);
  assert.match(html, /href="https:\/\/developer\.android\.com\/guide\/topics\/ui\/accessibility\/apps"/);
});

test('HTML drops non-https doc links', () => {
  const html = toHtml([{ ...base, suggestedFix: fix({ docUrl: 'javascript:alert(1)' }) }]);
  assert.doesNotMatch(html, /javascript:/);
});

test('HTML shows a legacy plain-text fix as the summary', () => {
  assert.match(toHtml([{ ...base, suggestedFix: 'Add a label' }]), /How to fix:<\/strong> Add a label/);
});

test('CSV puts the fix summary in the suggestedFix column, not raw JSON', () => {
  const csv = toCsv([{ ...base, suggestedFix: fix() }]);
  assert.match(csv, /Make it <48dp>/);
  assert.doesNotMatch(csv, /minimumInteractiveComponentSize/);
});
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd dashboard && npx vitest run src/App.test.jsx` — Expected: FAIL (`parseFix` not exported; fix panel missing).
Run: `node --test server/src/export.test.js` — Expected: FAIL (summary not rendered as "How to fix").

- [ ] **Step 4: Implement the dashboard side**

Append to `dashboard/src/logic.js`:

```js
// suggestedFix is a JSON string {summary, views, compose, docUrl, framework}
// from the device. Older rows hold plain text, shown as the summary.
export function parseFix(raw) {
  if (!raw) return null;
  try {
    const fix = JSON.parse(raw);
    if (fix && typeof fix === 'object' && fix.summary) return fix;
  } catch {
    // plain text, handled below
  }
  return { summary: String(raw) };
}
```

In `dashboard/src/App.jsx`, add `parseFix` to the `./logic` import and add above `IssueCard`:

```jsx
function FixPanel({ fix }) {
  if (!fix) return null;
  const snippets = [
    { key: 'compose', label: 'Compose', code: fix.compose },
    { key: 'views', label: 'Views', code: fix.views },
  ].filter((s) => s.code);
  if (fix.framework !== 'compose') snippets.reverse();
  return (
    <details className="issue-fix">
      <summary>How to fix: {fix.summary}</summary>
      {snippets.map((s) => (
        <div key={s.key} className="fix-snippet">
          <p className="fix-snippet-label">{s.label}{s.key === fix.framework && ' (detected)'}</p>
          <pre><code>{s.code}</code></pre>
        </div>
      ))}
      {fix.docUrl?.startsWith('https://') && (
        <a href={fix.docUrl} target="_blank" rel="noreferrer">Android docs</a>
      )}
    </details>
  );
}
```

In `IssueCard`, replace `{issue.suggestedFix && <p className="issue-fix">Fix: {issue.suggestedFix}</p>}` with:

```jsx
      <FixPanel fix={parseFix(issue.suggestedFix)} />
```

In `dashboard/src/App.css`, replace the `.issue-fix { … }` rule with:

```css
.issue-fix {
  margin: 0.4rem 0 0.25rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
}

.issue-fix summary {
  cursor: pointer;
  color: var(--text-primary);
}

.issue-fix a {
  color: var(--link);
}

.fix-snippet-label {
  margin: 0.5rem 0 0.2rem;
  font-weight: 600;
}

.fix-snippet pre {
  margin: 0;
  padding: 0.5rem;
  overflow-x: auto;
  background: var(--page-plane);
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 0.75rem;
}
```

- [ ] **Step 5: Implement the export side** — in `server/src/export.js`, add below `csvField`:

```js
// suggestedFix is a JSON string {summary, views, compose, docUrl, framework}
// from the device. Older rows hold plain text, shown as the summary.
// Same contract as dashboard/src/logic.js parseFix; this copy goes away when
// export moves into the dashboard (Phase 2).
function parseFix(raw) {
  if (!raw) return null;
  try {
    const fix = JSON.parse(raw);
    if (fix && typeof fix === 'object' && fix.summary) return fix;
  } catch {
    // plain text, handled below
  }
  return { summary: String(raw) };
}

function fixHtml(fix) {
  if (!fix) return '';
  const code = fix.framework === 'compose' ? fix.compose : fix.views;
  const docs = typeof fix.docUrl === 'string' && fix.docUrl.startsWith('https://')
    ? `<p><a href="${escapeHtml(fix.docUrl)}">Android docs</a></p>` : '';
  return `<div class="fix"><p><strong>How to fix:</strong> ${escapeHtml(fix.summary)}</p>${code ? `<pre><code>${escapeHtml(code)}</code></pre>` : ''}${docs}</div>`;
}
```

In `toCsv`, replace the `rows` line with:

```js
  const rows = issues.map((i) => header.map((h) => csvField(h === 'suggestedFix' ? parseFix(i[h])?.summary : i[h])).join(','));
```

In `toHtml`, replace the `${i.suggestedFix ? … : ''}` line with:

```js
          ${fixHtml(parseFix(i.suggestedFix))}
```

In the export's `<style>`, add:

```css
  .fix pre { background: #f5f5f5; padding: 0.5rem; overflow-x: auto; font-size: 0.8rem; }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `npm test` (repo root: server `node --test` + dashboard Vitest)
Expected: PASS, all suites.

- [ ] **Step 7: Commit** — SKIPPED (commit gate). Report changed files.

---

### Task 7: Audit dialogs, bottom sheets and popups (all target windows)

**Files:**
- Create: `auditor-app/app/src/main/java/com/a11yauditor/app/ScreenNaming.kt`
- Modify: `auditor-app/app/src/main/java/com/a11yauditor/app/AuditorAccessibilityService.kt` (`onAccessibilityEvent`, `runAudit`, new `currentTargetWindows`, `isActivity`)
- Modify: `auditor-app/app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `README.md` ("How it works" paragraph)
- Test: `auditor-app/app/src/test/java/com/a11yauditor/app/ScreenNamingTest.kt` (create)

**Interfaces:**
- Consumes: `checkHierarchy(root): List<AuditIssue>`, `captureScreenshot`, `reportSender.send(packageName, screen, issues, png)` (existing).
- Produces:
  - `data class TargetWindow<R>(val packageName: String?, val isApplication: Boolean, val layer: Int, val title: String?, val rootClassName: String?, val root: R)`
  - `ScreenNaming.targetWindows(windows: List<TargetWindow<R>>, target: String): List<TargetWindow<R>>` — target app windows, lowest layer (the activity) first.
  - `ScreenNaming.shouldScan(eventPackage: String?, isWindowsChanged: Boolean, windowPackages: () -> List<String?>, target: String): Boolean`
  - `ScreenNaming.shortName(className: String?): String?`
  - `ScreenNaming.screenLabel(activity: String?, window: TargetWindow<*>, isBase: Boolean): String`

Design note (deviation from spec §4, intentional): ATF always runs **per window**; the spec's optional multi-window ATF builder is not used, because per-window runs are what give each dialog/popup its own screen label, and a merged hierarchy would lose that.

- [ ] **Step 1: Check package visibility for activity lookups**

Run: `grep -nE "QUERY_ALL_PACKAGES|<queries" auditor-app/app/src/main/AndroidManifest.xml`
Expected: a match (the app picker already lists installed apps). If nothing matches, `isActivity` (Step 5) returns false on Android 11+ and labels fall back to window titles; note it in the task report.

- [ ] **Step 2: Write the failing tests** — create `ScreenNamingTest.kt`:

```kotlin
package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNamingTest {

    private fun win(pkg: String?, layer: Int, title: String? = null, rootClass: String? = null, app: Boolean = true) =
        TargetWindow(pkg, app, layer, title, rootClass, root = "root-$layer")

    @Test
    fun `targetWindows keeps target application windows, activity first`() {
        val windows = listOf(
            win("com.target", 3, title = "Confirm payment"),
            win("com.google.android.inputmethod", 5),
            win("com.target", 9, app = false),
            win("com.target", 1, title = "Checkout"),
        )
        val result = ScreenNaming.targetWindows(windows, "com.target")
        assertEquals(listOf("root-1", "root-3"), result.map { it.root })
    }

    @Test
    fun `targetWindows is empty when the target has no windows`() {
        assertTrue(ScreenNaming.targetWindows(listOf(win("com.other", 1)), "com.target").isEmpty())
    }

    @Test
    fun `shouldScan accepts events from the target package`() {
        assertTrue(ScreenNaming.shouldScan("com.target", false, { error("not needed") }, "com.target"))
    }

    @Test
    fun `shouldScan accepts a null-package windows-changed event when a target window exists`() {
        assertTrue(ScreenNaming.shouldScan(null, true, { listOf("com.other", "com.target") }, "com.target"))
    }

    @Test
    fun `shouldScan rejects a windows-changed event with no target window`() {
        assertFalse(ScreenNaming.shouldScan(null, true, { listOf("com.other") }, "com.target"))
    }

    @Test
    fun `shouldScan never queries windows for other event types`() {
        assertFalse(ScreenNaming.shouldScan("com.other", false, { error("must not query windows") }, "com.target"))
    }

    @Test
    fun `shortName strips package and inner class`() {
        assertEquals("CheckoutActivity", ScreenNaming.shortName("com.shop.CheckoutActivity"))
        assertEquals("PopupWindow", ScreenNaming.shortName("android.widget.PopupWindow\$PopupDecorView"))
        assertEquals(null, ScreenNaming.shortName(null))
    }

    @Test
    fun `base window is labelled by its activity`() {
        assertEquals("CheckoutActivity", ScreenNaming.screenLabel("com.shop.CheckoutActivity", win("com.shop", 1, title = "Shop"), isBase = true))
    }

    @Test
    fun `base window falls back to its title, then a placeholder`() {
        assertEquals("Shop", ScreenNaming.screenLabel(null, win("com.shop", 1, title = "Shop"), isBase = true))
        assertEquals("Unknown screen", ScreenNaming.screenLabel(null, win("com.shop", 1), isBase = true))
    }

    @Test
    fun `dialog is labelled activity then dialog title`() {
        assertEquals(
            "CheckoutActivity › Confirm payment",
            ScreenNaming.screenLabel("com.shop.CheckoutActivity", win("com.shop", 2, title = "Confirm payment"), isBase = false),
        )
    }

    @Test
    fun `untitled popup is labelled by its root class`() {
        assertEquals(
            "CheckoutActivity › PopupWindow",
            ScreenNaming.screenLabel(
                "com.shop.CheckoutActivity",
                win("com.shop", 2, rootClass = "android.widget.PopupWindow\$PopupDecorView"),
                isBase = false,
            ),
        )
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd auditor-app && ./gradlew testDebugUnitTest --tests "com.a11yauditor.app.ScreenNamingTest"`
Expected: compile FAIL — `Unresolved reference: TargetWindow` / `ScreenNaming`.

- [ ] **Step 4: Implement `ScreenNaming.kt`**

```kotlin
package com.a11yauditor.app

/**
 * Plain snapshot of an AccessibilityWindowInfo plus its root, so window
 * selection and screen labelling are JVM-testable. `R` is
 * AccessibilityNodeInfo in the service and a stand-in in tests.
 */
data class TargetWindow<R>(
    val packageName: String?,
    val isApplication: Boolean,
    val layer: Int,
    val title: String?,
    val rootClassName: String?,
    val root: R,
)

object ScreenNaming {

    /** The target app's own windows (activity, dialogs, sheets, popups), lowest layer (the activity) first. */
    fun <R> targetWindows(windows: List<TargetWindow<R>>, target: String): List<TargetWindow<R>> =
        windows.filter { it.isApplication && it.packageName == target }.sortedBy { it.layer }

    /**
     * TYPE_WINDOWS_CHANGED events often carry a null package, so for those
     * check whether any current window belongs to the target. `windowPackages`
     * is only called for windows-changed events.
     */
    fun shouldScan(eventPackage: String?, isWindowsChanged: Boolean, windowPackages: () -> List<String?>, target: String): Boolean =
        eventPackage == target || (isWindowsChanged && windowPackages().any { it == target })

    fun shortName(className: String?): String? =
        className?.substringAfterLast('.')?.substringBefore('$')?.takeIf { it.isNotBlank() }

    /** "CheckoutActivity" for the base window, "CheckoutActivity › Confirm payment" for a dialog/popup above it. */
    fun screenLabel(activity: String?, window: TargetWindow<*>, isBase: Boolean): String {
        val base = shortName(activity) ?: window.title?.takeIf { it.isNotBlank() } ?: "Unknown screen"
        if (isBase) return base
        val overlay = window.title?.takeIf { it.isNotBlank() } ?: shortName(window.rootClassName) ?: "Popup"
        return "$base › $overlay"
    }
}
```

- [ ] **Step 5: Wire it into the service**

`accessibility_service_config.xml` — change the event types line to:

```xml
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
```

`AuditorAccessibilityService.kt`:

Add imports:

```kotlin
import android.content.ComponentName
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityWindowInfo
```

Add a field next to `pendingAudit`:

```kotlin
    // Last target-app Activity seen in a TYPE_WINDOW_STATE_CHANGED event; names the base screen.
    private var lastActivity: ComponentName? = null
```

In `onAccessibilityEvent`, replace:

```kotlin
            if (event.packageName?.toString() != targetPackage) return
```

with:

```kotlin
            val isWindowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (!ScreenNaming.shouldScan(
                    event.packageName?.toString(),
                    isWindowsChanged,
                    { windows.map { it.root?.packageName?.toString() } },
                    targetPackage,
                )
            ) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.className?.toString()
                    ?.takeIf { isActivity(targetPackage, it) }
                    ?.let { lastActivity = ComponentName(targetPackage, it) }
            }
```

and replace `val runnable = Runnable { runAudit(targetPackage, event.className?.toString()) }` with:

```kotlin
            val runnable = Runnable { runAudit(targetPackage) }
```

Replace the whole `runAudit` function with:

```kotlin
    private fun runAudit(targetPackage: String) {
        // Re-selected here, not at event time: the debounce means the foreground
        // can change in between, and only target-app windows are ever audited
        // or screenshotted.
        val targetWindows = currentTargetWindows(targetPackage)
        if (targetWindows.isEmpty()) {
            Log.d(TAG, "runAudit: no $targetPackage windows on screen, skipping")
            return
        }
        val activity = lastActivity?.takeIf { it.packageName == targetPackage }?.className

        // One ATF run per window so a failure in one (e.g. a popup mid-dismiss)
        // doesn't drop the others, and each dialog/popup gets its own screen label.
        val perWindow = targetWindows.mapIndexedNotNull { index, window ->
            val issues = try {
                checkHierarchy(window.root)
            } catch (e: Exception) {
                Log.e(TAG, "ATF check run failed on window '${window.title}'", e)
                emptyList()
            }
            if (issues.isEmpty()) null
            else ScreenNaming.screenLabel(activity, window, isBase = index == 0) to issues
        }
        Log.d(TAG, "runAudit: ${perWindow.sumOf { it.second.size }} issue(s) across ${targetWindows.size} window(s)")
        if (perWindow.isEmpty()) return

        captureScreenshot { png ->
            perWindow.forEach { (label, issues) ->
                sessionIssueCount.value += issues.size
                reportSender.send(targetPackage, label, issues, png)
            }
        }
    }

    /** Target-app windows, activity first; falls back to rootInActiveWindow if the window list is unavailable. */
    private fun currentTargetWindows(targetPackage: String): List<TargetWindow<AccessibilityNodeInfo>> {
        val all = windows.mapNotNull { w ->
            val root = w.root ?: return@mapNotNull null
            TargetWindow(
                packageName = root.packageName?.toString(),
                isApplication = w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                layer = w.layer,
                title = w.title?.toString(),
                rootClassName = root.className?.toString(),
                root = root,
            )
        }
        val selected = ScreenNaming.targetWindows(all, targetPackage)
        if (selected.isNotEmpty()) return selected
        val active = rootInActiveWindow ?: return emptyList()
        if (active.packageName?.toString() != targetPackage) return emptyList()
        return listOf(TargetWindow(targetPackage, true, 0, null, active.className?.toString(), active))
    }

    private fun isActivity(pkg: String, className: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, className), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
```

- [ ] **Step 6: Run all unit tests and build**

Run: `cd auditor-app && ./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`; `ScreenNamingTest` 11 tests pass.

- [ ] **Step 7: README** — in "## How it works", replace the sentence "On each change it grabs the current `AccessibilityNodeInfo` tree, runs it through …" up to "(ATF)," with:

```markdown
On each change it grabs every window that belongs to the target app (the activity plus any dialog,
bottom sheet or popup on top of it), runs each through Google's
[Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android)
(ATF), labels dialog/popup issues as `Activity › Title`,
```

Keep the rest of that sentence ("maps each finding to a WCAG 2.1 success criterion …") unchanged.

- [ ] **Step 8: Commit** — SKIPPED (commit gate). Report changed files.

---

### Task 8: On-device verification

**Files:** none changed unless a check fails (then fix in the owning task's files and rerun that task's tests).

**Interfaces:** consumes everything above.

- [ ] **Step 1: Build and start everything**

Run (repo root): `npm install && npm start` (leave running)
Run (second shell): `cd auditor-app && ./gradlew installDebug && adb reverse tcp:8080 tcp:8080`
Expected: dashboard at `http://localhost:8080`, "device connected" after enabling the service.

- [ ] **Step 2: Walk the checklist** — set a target app with dialogs and menus (e.g. `com.android.settings`), Start Auditing, and confirm each:

- [ ] Opening a dialog produces issues grouped under `<Activity> › <dialog title>`.
- [ ] Opening a bottom sheet (any app that has one) produces its own `<Activity> › …` group.
- [ ] Opening an overflow/popup menu produces a `<Activity> › PopupWindow` (or titled) group.
- [ ] Opening a popup triggers a scan even though no content-change event comes from the target (`adb logcat -s A11yAuditor.Service` shows `runAudit: … across 2 window(s)`).
- [ ] Each issue card has "How to fix: …"; expanding it shows two snippets and an "Android docs" link that opens.
- [ ] On a Compose app (any app built with Jetpack Compose), the snippet order is "Compose (detected)", "Views". On a Views app it is "Views (detected)", "Compose".
- [ ] Export HTML shows "How to fix" with one snippet and a docs link; Export CSV's `suggestedFix` column holds the summary text.
- [ ] Dismissing a popup while a scan is pending does not crash the service, and other windows' issues still arrive (Review Focus 5).
- [ ] Mark an issue resolved, `curl -X DELETE http://localhost:8080/issues` from a terminal, revisit the screen: the new issues are not marked resolved, and the Resolved tile reads `0/N` (Review Focus 3).
- [ ] Primary/danger buttons and resolved cards read clearly in light and dark mode.

- [ ] **Step 3: Report** — list every checklist item as pass/fail with notes. Commit — SKIPPED (commit gate).
