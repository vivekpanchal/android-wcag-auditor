package com.a11yauditor.app

import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect

data class AuditIssue(
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val elementDescription: String,
    val description: String,
    val fix: WcagMapping.Fix?,
    // "views" or "compose", so the dashboard shows the matching fix snippet first.
    val framework: String,
    // Screen-pixel bounds of the flagged element, same coordinate space as
    // the report's screenshot — lets the dashboard draw a highlight box on
    // the image.
    val bounds: Rect? = null,
)

/** Same identity groupStored uses for "seen N times": screen, element, criterion, message. Bounds left out, so movement is not a new finding. */
data class FindingKey(val screen: String, val element: String, val wcagSc: String, val description: String)

fun findingKeys(perWindow: List<Pair<String, List<AuditIssue>>>): Set<FindingKey> =
    perWindow.flatMap { (screen, issues) -> issues.map { FindingKey(screen, it.elementDescription, it.wcagSc, it.description) } }.toSet()
