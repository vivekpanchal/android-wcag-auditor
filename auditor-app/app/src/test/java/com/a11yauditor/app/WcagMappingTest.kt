package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Local JVM unit tests for WcagMapping — pure Kotlin, no Android framework
 * types involved, so this runs under plain JUnit with no Robolectric or
 * instrumentation needed.
 */
class WcagMappingTest {

    @Test
    fun `known check classes map to their documented WCAG criterion`() {
        val expected = listOf(
            "SpeakableTextPresentCheck" to WcagMapping.Criterion("1.1.1", "A", "Non-text Content"),
            "EditableContentDescCheck" to WcagMapping.Criterion("4.1.2", "A", "Name, Role, Value"),
            "TouchTargetSizeCheck" to WcagMapping.Criterion("2.5.5", "AAA", "Target Size"),
            "TextContrastCheck" to WcagMapping.Criterion("1.4.3", "AA", "Contrast (Minimum)"),
            "ImageContrastCheck" to WcagMapping.Criterion("1.4.11", "AA", "Non-text Contrast"),
            "DuplicateSpeakableTextCheck" to WcagMapping.Criterion("4.1.2", "A", "Name, Role, Value"),
            "DuplicateClickableBoundsCheck" to WcagMapping.Criterion("4.1.2", "A", "Name, Role, Value"),
            "ClassNameCheck" to WcagMapping.Criterion("4.1.2", "A", "Name, Role, Value"),
            "ClickableSpanCheck" to WcagMapping.Criterion("2.1.1", "A", "Keyboard"),
            "RedundantDescriptionCheck" to WcagMapping.Criterion("4.1.2", "A", "Name, Role, Value"),
            "TraversalOrderCheck" to WcagMapping.Criterion("2.4.3", "A", "Focus Order"),
            "LinkPurposeUnclearCheck" to WcagMapping.Criterion("2.4.4", "A", "Link Purpose (In Context)"),
        )

        for ((checkClass, criterion) in expected) {
            assertEquals(checkClass, criterion, WcagMapping.forCheckClass(checkClass))
        }
    }

    @Test
    fun `unrecognized check class falls back to the unmapped criterion`() {
        val result = WcagMapping.forCheckClass("SomeCompletelyMadeUpCheckName")

        assertEquals(WcagMapping.Criterion("N/A", "-", "Unmapped check"), result)
    }
}
