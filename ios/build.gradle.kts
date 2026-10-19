/*
 * The iOS application shell: the Kotlin side of what iosApp's Xcode project
 * embeds.
 *
 * Everything the app draws comes from :sharedUi, and everything it plays from
 * :shared — this module is only the platform answers (PlayerHost, AppUiHost),
 * the AVPlayer transport, and the one ComposeUIViewController the Swift entry
 * asks for. The framework it produces is named BitChord; the Xcode project's
 * Run Script phase builds and embeds it for whichever target xcodebuild asked
 * for (embedAndSignAppleFrameworkForXcode), so nothing in this file names a
 * configuration or an SDK.
 *
 * Linkable from a Mac only: the klib compile tasks run anywhere — which is
 * how the iOS targets are verified on Windows — but linking the framework and
 * the embed step are Xcode's, and run in CI.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    // Sideloaded iOS: the device is always arm64, the simulator always Apple
    // silicon. There is no x86_64 iOS worth carrying — same as shared and
    // sharedUi, and the reason this module needs no default hierarchy.
    iosArm64()
    iosSimulatorArm64()

    // One framework for Xcode to embed, whichever target it asked for. No
    // exports: Swift reaches exactly one function, MainViewController, and
    // everything else stays Kotlin-internal, so the umbrella header stays the
    // size of one declaration.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BitChord"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(project(":sharedUi"))
            // ComposeUIViewController, for the entry point. The Compose
            // runtime/foundation/ui/material3 the shell draws with all ride
            // in on :sharedUi's api already.
            implementation(compose.ui)
        }
    }
}
