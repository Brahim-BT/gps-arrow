plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.gpsarrow"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.gpsarrow"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"

            // Debug APKs are downloaded by hand from a GitHub Actions artifact every round, so
            // three quarters of a universal APK is native code for architectures that will never
            // run it — including x86 builds that exist only for emulators. Restricting the debug
            // variant to the target device's ABI cuts roughly 37 MB off each download.
            //
            // This affects the debug variant ONLY. Release ships as an App Bundle, where Play
            // splits per device anyway, so the release path is untouched and there is nothing to
            // keep in sync.
            //
            // Testing on an emulator or a 32-bit device means adding its ABI here:
            //   arm64-v8a (most phones since ~2017), armeabi-v7a (older 32-bit),
            //   x86_64 (most emulators), x86 (very old emulators).
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // v1 map renderer. Native .so per ABI — ship an App Bundle, not a universal APK.
    implementation(libs.maplibre.android.sdk)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
