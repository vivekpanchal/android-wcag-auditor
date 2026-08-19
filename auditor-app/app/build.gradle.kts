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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
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

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    // ATF and Guava aren't re-declared here: androidTest automatically
    // compiles against the app's own `implementation` classpath, which
    // already has both (see above).
}
