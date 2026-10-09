package com.a11yauditor.app

/**
 * Maps ATF (Accessibility Test Framework for Android) check classes to the
 * WCAG 2.1 success criterion they correspond to. Keyed by the check's simple
 * class name since that's what's available from an
 * AccessibilityHierarchyCheckResult without extra parsing.
 *
 * Check class names, and the mapping itself, were cross-checked against ATF
 * 4.1.1's own source (github.com/google/Accessibility-Test-Framework-for-Android,
 * package .../checks) — not just guessed from class names. Two things worth
 * knowing if you're extending this:
 *
 * - TouchTargetSizeCheck enforces 48x48dp by default (Android's own Material
 *   Design guideline, see TOUCH_TARGET_MIN_HEIGHT/WIDTH in its source), not
 *   WCAG 2.1's 44x44 CSS px for SC 2.5.5. 48dp is a strict superset of 44px,
 *   so a check pass always satisfies 2.5.5, but a check failure doesn't
 *   necessarily mean 2.5.5 fails (e.g. a 46dp target fails ATF's check but
 *   clears WCAG's 44px floor). Reported as 2.5.5 anyway since it's the only
 *   relevant WCAG 2.1 SC and the check is strictly more conservative, but
 *   don't read a flagged issue here as proof of a WCAG failure without a
 *   manual measurement.
 * - TextContrastCheck and ImageContrastCheck use the literal WCAG constants
 *   from ATF's own ContrastUtils (CONTRAST_RATIO_WCAG_NORMAL_TEXT = 4.5,
 *   CONTRAST_RATIO_WCAG_LARGE_TEXT = 3.0), which are exactly the 1.4.3 AA and
 *   1.4.11 AA thresholds — these two are an exact match, not an approximation.
 *
 * `byCheckClass` covers all 14 check classes in ATF 4.1.1's
 * `AccessibilityCheckPreset.LATEST` (package .../checks), so `forCheckClass`
 * should never fall through to UNKNOWN for a result produced by this pinned
 * ATF version. If the ATF version in auditor-app/gradle/libs.versions.toml is
 * ever bumped, re-verify: unzip the `accessibility-test-framework-*-api.jar`
 * and `-runtime.jar` from the Gradle cache, list the `*Check.class` entries
 * under each jar's checks package, and diff that list against
 * `byCheckClass`'s keys.
 *
 * If a future ATF version renames or splits a check, add/update the entry
 * here — everything else keys off this map, so this is the one place to
 * extend when new checks show up.
 *
 * Each entry also carries a [Fix]: generic, per-check remediation with a
 * Views and a Compose snippet. ATF's own result message (which has the
 * specifics, e.g. the measured contrast ratio) stays the issue description;
 * the fix sits beside it. `WcagMappingTest` fails if any check lacks one.
 */
object WcagMapping {

    data class Fix(val summary: String, val views: String, val compose: String, val docUrl: String)

    data class Criterion(val sc: String, val level: String, val title: String, val fix: Fix? = null)

    private val UNKNOWN = Criterion("N/A", "-", "Unmapped check")

    private const val DOC_GUIDE = "https://developer.android.com/guide/topics/ui/accessibility/apps"
    private const val DOC_LABELS = "https://support.google.com/accessibility/android/answer/7158690"
    private const val DOC_TOUCH = "https://support.google.com/accessibility/android/answer/7101858"
    private const val DOC_CONTRAST = "https://support.google.com/accessibility/android/answer/7158390"

    private val byCheckClass: Map<String, Criterion> = mapOf(
        // Missing accessible name entirely — ATF: "checks that items that
        // require speakable text have some."
        "SpeakableTextPresentCheck" to Criterion("1.1.1", "A", "Non-text Content", Fix(
            "Give the element an accessible name, or hide it from accessibility services if it is decorative.",
            "android:contentDescription=\"@string/close\"\n<!-- decorative: android:importantForAccessibility=\"no\" -->",
            "Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))\n// decorative: contentDescription = null",
            DOC_LABELS,
        )),
        // Editable field's value gets hidden because contentDescription
        // overrides it instead of using a proper label — wrong name exposure.
        "EditableContentDescCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Remove contentDescription from the editable field; label it with a hint or a separate label instead.",
            "<TextView android:labelFor=\"@id/email\" android:text=\"@string/email\" />\n<EditText android:id=\"@+id/email\" android:hint=\"@string/email\" />",
            "TextField(value, onValueChange, label = { Text(stringResource(R.string.email)) })",
            DOC_LABELS,
        )),
        // See class doc above re: 48dp vs WCAG's 44px.
        "TouchTargetSizeCheck" to Criterion("2.5.5", "AAA", "Target Size", Fix(
            "Make the touch target at least 48x48dp (padding counts).",
            "android:minWidth=\"48dp\"\nandroid:minHeight=\"48dp\"",
            "IconButton(onClick) { … } // already 48dp\nModifier.minimumInteractiveComponentSize()",
            DOC_TOUCH,
        )),
        // Exact match — see class doc above.
        "TextContrastCheck" to Criterion("1.4.3", "AA", "Contrast (Minimum)", Fix(
            "Use text and background colours with at least 4.5:1 contrast (3:1 for 18sp+, or 14sp+ bold).",
            "android:textColor=\"@color/on_surface\" <!-- verify 4.5:1 against the background -->",
            "Text(text, color = MaterialTheme.colorScheme.onSurface)",
            DOC_CONTRAST,
        )),
        // Exact match — see class doc above.
        "ImageContrastCheck" to Criterion("1.4.11", "AA", "Non-text Contrast", Fix(
            "Give icons and meaningful graphics at least 3:1 contrast against their background.",
            "app:tint=\"@color/on_surface\"",
            "Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface)",
            DOC_CONTRAST,
        )),
        // Two elements with identical accessible names — ambiguous identity.
        "DuplicateSpeakableTextCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Give elements with different actions different accessible names, e.g. include the item name.",
            "view.contentDescription = getString(R.string.delete_item, item.name) // \"Delete %s\"",
            "Modifier.semantics { contentDescription = \"Delete ${'$'}{item.name}\" }",
            DOC_LABELS,
        )),
        // A container shares exact bounds with a clickable child but doesn't
        // handle clicks itself — exposes a false/misleading interactive role
        // to assistive tech. Not about size, about role correctness.
        "DuplicateClickableBoundsCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Make only one of the overlapping views clickable, normally the outer container.",
            "<!-- keep clickable on the container, remove it from the child -->\nandroid:clickable=\"false\"",
            "Row(Modifier.clickable(onClick = onOpen)) { Text(title) } // no clickable on the child",
            DOC_GUIDE,
        )),
        // Custom view's reported class/role isn't one AT recognizes.
        "ClassNameCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Report a standard role for the custom view so assistive technology knows what it is.",
            "override fun getAccessibilityClassName(): CharSequence = Button::class.java.name",
            "Modifier.semantics { role = Role.Button }",
            DOC_GUIDE,
        )),
        // ClickableSpan inside a TextView isn't individually reachable by
        // non-touch input (switch access, keyboard) pre-API 26.
        "ClickableSpanCheck" to Criterion("2.1.1", "A", "Keyboard", Fix(
            "Use URLSpan for links, or expose ClickableSpans so accessibility services can activate them.",
            "ViewCompat.enableAccessibleClickableSpanSupport(textView)\n// or: SpannableString with URLSpan(url)",
            "buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(\"Terms\") } }",
            DOC_GUIDE,
        )),
        // Accessible name bakes in role/state/action info that should be
        // conveyed by the Role/Value parts of the accessibility API instead
        // (ATF's own doc: checks for the view's type, state, or available
        // actions duplicated into its speakable text).
        "RedundantDescriptionCheck" to Criterion("4.1.2", "A", "Name, Role, Value", Fix(
            "Remove role or state words (\"button\", \"selected\") from the label; the role already conveys them.",
            "android:contentDescription=\"@string/submit\" <!-- \"Submit\", not \"Submit button\" -->",
            "Modifier.semantics { contentDescription = \"Submit\" } // not \"Submit button\"",
            DOC_LABELS,
        )),
        // Developer-specified traversal order breaks reading/focus order.
        "TraversalOrderCheck" to Criterion("2.4.3", "A", "Focus Order", Fix(
            "Remove conflicting traversalBefore/After attributes; order the layout to match the reading order.",
            "<!-- remove, or make consistent: -->\nandroid:accessibilityTraversalBefore=\"@id/next\"",
            "Modifier.semantics { isTraversalGroup = true } // on the parent\nModifier.semantics { traversalIndex = 1f } // on children",
            DOC_GUIDE,
        )),
        // Link text alone doesn't convey the link's purpose.
        "LinkPurposeUnclearCheck" to Criterion("2.4.4", "A", "Link Purpose (In Context)", Fix(
            "Make the link text describe its destination, not \"click here\" or \"more\".",
            "<string name=\"privacy_link\">Read the privacy policy</string>",
            "withLink(LinkAnnotation.Url(url)) { append(\"Read the privacy policy\") }",
            DOC_GUIDE,
        )),
        // Text sized in px/dip/pt instead of sp so it won't grow with the user's font-size
        // setting, or sp text that gets clipped because it (or its container) has a fixed
        // width/height — ATF's own doc: "recommended dimension type for text is
        // scale-independent pixels (sp)... allows the user to make the text larger... using
        // an Android device's Accessibility > Font size setting." That's the same failure
        // mode 1.4.4 targets (content lost when text is resized/scaled), just triggered by
        // Android's OS-level font-scale setting rather than a browser's 200% zoom.
        "TextSizeCheck" to Criterion("1.4.4", "AA", "Resize Text", Fix(
            "Size text in sp and let its container grow (no fixed height) so it scales with the font-size setting.",
            "android:textSize=\"16sp\"\nandroid:layout_height=\"wrap_content\"",
            "Text(text, fontSize = 16.sp, modifier = Modifier.heightIn(min = 48.dp)) // not Modifier.height(…)",
            DOC_GUIDE,
        )),
        // OCR-detected text found rendered inside an ImageView/SurfaceView with no matching
        // accessible name exposed via the accessibility tree — text baked into an image with
        // no text alternative, same rationale as SpeakableTextPresentCheck above. Note: ATF's
        // source marks this class @Beta ("this check is under development"), so expect more
        // false positives/negatives here than the other 13 checks.
        "UnexposedTextCheck" to Criterion("1.1.1", "A", "Non-text Content", Fix(
            "Use real text instead of text baked into an image, or put the image's text in its accessible name.",
            "android:contentDescription=\"@string/sale_banner\" <!-- \"Summer sale: 50% off\" -->",
            "Image(painter, contentDescription = \"Summer sale: 50% off\")",
            DOC_LABELS,
        )),
    )

    fun forCheckClass(simpleClassName: String): Criterion =
        byCheckClass[simpleClassName] ?: UNKNOWN

    val checkClasses: Set<String> get() = byCheckClass.keys
}
