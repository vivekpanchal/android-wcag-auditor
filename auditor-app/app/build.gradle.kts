plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.a11yauditor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.a11yauditor.app"
        // takeScreenshot() on AccessibilityService requires API 30 (Android 11).
        // minSdk 26 keeps the app installable on older devices; the service
        // just skips the screenshot step and still reports the issue on those.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

    // Google's Accessibility Test Framework for Android — runs the actual
    // WCAG-mapped checks against the AccessibilityNodeInfo tree.
    // https://github.com/google/Accessibility-Test-Framework-for-Android
    implementation(libs.accessibility.test.framework)
    // ATF's public API returns Guava collection types (ImmutableSet, etc.)
    // directly but doesn't expose Guava transitively — needed to reference
    // those return types from our own code.
    implementation(libs.guava)

    testImplementation(libs.junit)
    // Local unit tests run on a plain JVM against android.jar stubs, which
    // throw at runtime for org.json.* (only Android's real implementation
    // is usable, not the stub) -- DeviceProtocolTest exercises real
    // JSONObject/JSONArray, so it needs a working implementation on the
    // test classpath. Same artifact android.jar's org.json is originally
    // vendored from.
    testImplementation(libs.json)
}
