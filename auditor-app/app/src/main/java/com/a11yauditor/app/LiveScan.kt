package com.a11yauditor.app

/** Latest pass over the target's windows, kept even when clean so the bubble can return to 0. Never a running total. */
data class LiveScan(val screen: String?, val issues: List<AuditIssue>) {
    val hasSerious get() = issues.any { it.severity == "serious" }

    companion object {
        /** `results` is label to issues per window, lowest first, so the top window names the screen. */
        fun from(results: List<Pair<String, List<AuditIssue>>>) =
            LiveScan(results.lastOrNull()?.first, results.flatMap { it.second })
    }
}
