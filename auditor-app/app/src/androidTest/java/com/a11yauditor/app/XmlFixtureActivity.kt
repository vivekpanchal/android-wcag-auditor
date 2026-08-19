package com.a11yauditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.a11yauditor.app.test.R

/**
 * Instrumented-test-only fixture hosting a real XML view tree with two seeded
 * accessibility violations. See activity_xml_fixture.xml and
 * AtfScanParityTest for what's asserted against it.
 */
class XmlFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_fixture)
    }
}
