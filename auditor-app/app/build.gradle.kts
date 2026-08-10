plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google's Accessibility Test Framework for Android — runs the actual
    // WCAG-mapped checks against the AccessibilityNodeInfo tree.
    // https://github.com/google/Accessibility-Test-Framework-for-Android
    implementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1")
    // ATF's public API returns Guava collection types (ImmutableSet, etc.)
    // directly but doesn't expose Guava transitively — needed to reference
    // those return types from our own code.
    implementation("com.google.guava:guava:33.3.1-android")
}
