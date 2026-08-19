package com.a11yauditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Instrumented-test-only fixture: seeds the same two ATF-checkable
 * violations as XmlFixtureActivity, via Compose semantics instead of XML:
 *   - a clickable Image with no contentDescription -> WCAG 1.1.1
 *   - a 20dp clickable Box (below the 48dp minimum), with a name so it only
 *     trips the size check -> WCAG 2.5.5
 *
 * Proves AtfHierarchyChecker.runAtfChecks — the exact function
 * AuditorAccessibilityService uses in production — flags the same issues on
 * a real Compose semantics tree as it does on a real XML view tree.
 */
class ComposeFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column {
                        Image(
                            painter = ColorPainter(Color.Red),
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clickable { },
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { }
                                .semantics { contentDescription = "small target" },
                        )
                    }
                }
            }
        }
    }
}
