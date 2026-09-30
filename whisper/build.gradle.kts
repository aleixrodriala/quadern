// :whisper — on-device speech-to-text (whisper.cpp) as an Android library.
// Catalog aliases used here (must exist in the including project's catalog too):
//   plugins.android.library, versions.compileSdk, versions.minSdk,
//   libraries.androidx.test.runner, libraries.androidx.test.ext.junit
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.aleixrodriala.noteai.whisper"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // r28+ links with 16 KB ELF alignment by default (required for Android 15+ 16 KB-page devices).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Every .so talks to the others only through ggml's/whisper's C APIs, so each can
                // carry its own (hidden) libc++ and nothing clashes with other native deps.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
