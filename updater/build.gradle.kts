// The test-build updater: an app's DEBUG build checks for a newer test build
// and installs it by itself. Its own module, separate from :kit, so an app
// can take it as `debugImplementation` and its release build contains none
// of it. Apps include it with
//
//   include(":kit-updater")
//   project(":kit-updater").projectDir = file("kit/updater")
//
//   debugImplementation(project(":kit-updater"))
//
// The app declares REQUEST_INSTALL_PACKAGES and
// UPDATE_PACKAGES_WITHOUT_USER_ACTION in its own debug-only manifest; this
// module's manifest only adds the install-status receiver.
//
// Plugins are applied by bare id, with no version: the app's root build
// already has AGP on its classpath. Plain coordinates, not catalog aliases.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.mirrorapps.kit.updater"

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    // In the public API (the host's client, the state flow).
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in unit tests; this is the real one.
    testImplementation("org.json:json:20231013")
}
