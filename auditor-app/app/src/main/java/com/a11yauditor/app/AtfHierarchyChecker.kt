package com.a11yauditor.app

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import java.util.Locale

/**
 * Runs ATF's AccessibilityCheckPreset.LATEST checks against a live
 * AccessibilityNodeInfo tree and maps results to AuditIssues.
 *
 * Extracted out of AuditorAccessibilityService.checkHierarchy so instrumented
 * tests can exercise the exact same ATF invocation path against fixture
 * Activities (XML and Compose) instead of duplicating it — see
 * androidTest/.../AtfScanParityTest.kt.
 */
internal fun runAtfChecks(root: AccessibilityNodeInfo, context: Context): List<AuditIssue> {
    val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root, context).build()
    val checks = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
        AccessibilityCheckPreset.LATEST
    )

    val results = mutableListOf<AccessibilityHierarchyCheckResult>()
    checks.forEach { check -> results.addAll(check.runCheckOnHierarchy(hierarchy)) }

    return results
        .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
        .map { result ->
            // result.sourceCheckClass / result.element are ATF's linkage back to
            // which check fired and which node it fired on.
            val checkClassName = result.sourceCheckClass?.simpleName ?: "Unknown"
            val criterion = WcagMapping.forCheckClass(checkClassName)
            val element = result.element
            val bounds = element?.boundsInScreen?.takeUnless { it.isEmpty }
            AuditIssue(
                severity = if (result.type == AccessibilityCheckResultType.ERROR) "serious" else "moderate",
                wcagSc = criterion.sc,
                wcagLevel = criterion.level,
                elementDescription = describeAtfElement(element?.className, element?.resourceName),
                description = result.getMessage(Locale.getDefault())?.toString() ?: result.toString(),
                suggestedFix = null,
                bounds = bounds,
            )
        }
}

private fun describeAtfElement(className: CharSequence?, resourceId: CharSequence?) =
    listOfNotNull(className?.toString()?.substringAfterLast('.'), resourceId?.toString())
        .joinToString(" ")
        .ifBlank { "Unnamed element" }
