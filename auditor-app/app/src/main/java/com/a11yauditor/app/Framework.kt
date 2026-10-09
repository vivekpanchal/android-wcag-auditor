package com.a11yauditor.app

/**
 * "compose" if the element or any ancestor is a Compose host view
 * (AndroidComposeView, or the ComposeView wrapping it), else "views".
 * Takes class names rather than ATF elements so it is JVM-testable.
 */
fun frameworkFor(ancestorClassNames: Sequence<CharSequence?>): String =
    if (ancestorClassNames.any { it?.contains("ComposeView") == true }) "compose" else "views"
