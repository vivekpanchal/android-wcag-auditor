package com.a11yauditor.app

import com.google.android.apps.common.testing.accessibility.framework.replacements.Rect

data class AuditIssue(
    val severity: String,
    val wcagSc: String,
    val wcagLevel: String,
    val elementDescription: String,
    val description: String,
    val suggestedFix: String?,
    // Screen-pixel bounds of the flagged element, same coordinate space as
    // the report's screenshot — lets the dashboard draw a highlight box on
    // the image.
    val bounds: Rect? = null,
)
