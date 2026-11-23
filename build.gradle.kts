// Standalone build only. The versions here match MirrorMap Dash's catalog
// (AGP 9.1.1, Kotlin 2.2.10). Inside an app, the app's own root build puts
// these plugins on the classpath and kit/build.gradle.kts applies them by
// bare id, so the kit always builds with the app's versions.
plugins {
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
