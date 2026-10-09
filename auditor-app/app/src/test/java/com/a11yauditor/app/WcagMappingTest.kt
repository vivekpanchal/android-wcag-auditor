package com.a11yauditor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            assertEquals(checkClass, criterion, WcagMapping.forCheckClass(checkClass).copy(fix = null))
        }
    }

    @Test
    fun `unrecognized check class falls back to the unmapped criterion`() {
        val result = WcagMapping.forCheckClass("SomeCompletelyMadeUpCheckName")

        assertEquals(WcagMapping.Criterion("N/A", "-", "Unmapped check"), result)
    }

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
}
