// The kit: one Android library module shared by MirrorMap Dash and the
// MirrorMsg Android app. Apps include it with
//
//   include(":kit")
//   project(":kit").projectDir = file("kit/kit")
//
// (the repo is a submodule at kit/, the module is kit/kit/).
//
// Plugins are applied by bare id, with no version: the app's root build
// already has AGP and the Compose compiler plugin on its classpath, and
// asking again with a version fails ("already on the classpath with an
// unknown version"). Dependencies are plain coordinates, not catalog
// aliases, because each app's catalog names things differently.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mirrorapps.kit"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The lower of the two apps: MirrorMsg is 26, Dash is 30.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in unit tests; this is the real one.
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
