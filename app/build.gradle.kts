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

    signingConfigs {
        getByName("debug") {
            // A debug APK is installed over the previous one by hand every round, and Android
            // refuses that when the signing certificate changes. AGP's default signs with
            // ~/.android/debug.keystore, which does not exist on a fresh CI runner — so it
            // generated a NEW random key every build, and every download had to be installed
            // as a fresh app with the previous one's saved destinations thrown away.
            //
            // CI writes this file from the DEBUG_KEYSTORE_BASE64 secret before building. It is
            // absent on an ordinary local build, where AGP's own keystore is the right answer,
            // so the default is left alone rather than replaced with something that would need
            // a secret to compile at all.
            val stable = rootProject.file("ci-debug.keystore")
            if (stable.exists()) {
                storeFile = stable
                // The conventional debug values, in plain sight on purpose: what keeps this key
                // private is the keystore bytes living in an encrypted secret, not the password.
                // It signs dev.gpsarrow.debug and can never sign a Play release.
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

    // Debug APKs are downloaded by hand from a GitHub Actions artifact every round, and three
    // quarters of a universal APK is native code the device will never execute. This used to be
    // `ndk { abiFilters += "arm64-v8a" }` on the debug build type, which made the download small
    // by making it install on exactly one machine — and an arm64-only APK cannot install on a
    // 32-bit device at all. The installer's entire explanation for that is "App not installed",
    // which is how it cost three weeks on an Android car head unit.
    //
    // Splitting gets the size back without the trap: one small APK per architecture for the
    // routine phone download, plus a universal one to reach for when the device is unfamiliar
    // and you would otherwise have to identify its CPU before you could install anything.
    //
    // This is an `android { }` block rather than a build-type one, so it applies to every
    // variant — but the release path ships as an App Bundle, where `splits.abi` is ignored and
    // Play generates a per-device APK anyway. Nothing there changes, and there is nothing to
    // keep in sync.
    //
    //   app-arm64-v8a-debug.apk     ~33 MB   phones since ~2017
    //   app-armeabi-v7a-debug.apk   ~33 MB   32-bit devices, which most car radios are
    //   app-x86_64-debug.apk        ~33 MB   emulators
    //   app-universal-debug.apk     ~68 MB   anything; install this one when in doubt
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
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
