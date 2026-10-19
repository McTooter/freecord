import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Sideloaded iOS: the device is always arm64, the simulator always Apple
    // silicon. There is no x86_64 iOS worth carrying.
    iosArm64()
    iosSimulatorArm64()

    // Android and desktop are both JVM, but the default hierarchy gives them no source set in
    // common.
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Compile-only: the two applications supply the runtime artifact themselves.
                compileOnly("com.microsoft.onnxruntime:onnxruntime:1.20.0")
                api("io.ktor:ktor-client-okhttp:3.5.2")
                api("org.jsoup:jsoup:1.22.2")
                // NewPipe is the stream-resolution fallback behind InnerTubeX,
                // compile-only because the phone ships a trimmed jar of it. iOS
                // has no NewPipe: it resolves through InnerTubeX alone.
                compileOnly("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        jvmMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            // The network stack the lyrics providers and the YouTube Music
            // client run on. Common, so the iOS target compiles the same code;
            // the engine is chosen per platform — okhttp where it already was,
            // Darwin's NSURLSession on iOS.
            api("io.ktor:ktor-client-core:3.5.2")
            api("io.ktor:ktor-client-websockets:3.5.2")
            api("io.ktor:ktor-client-content-negotiation:3.5.2")
            api("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            // The IOException every platform agrees on (a typealias to
            // java.io.IOException on the JVM, so the applications' catch sites
            // are unchanged), and what ktor's own channels are built on —
            // the version ktor 3.5.2 pins.
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
            // YouTube stream resolution, as the phone does it: InnerTubeX's
            // client catalog and cipher tiers. Its own build publishes iOS
            // klibs, so the port inherits it for free.
            api("com.github.MetrolistGroup.innertubex:innertubex:v0.7.0")
        }
        val iosMain by getting {
            dependencies {
                api("io.ktor:ktor-client-darwin:3.5.2")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("com.squareup.okhttp3:mockwebserver:5.3.2")
        }
    }
}

android {
    namespace = "com.music.bitchord.shared"
    compileSdk = 36
}
