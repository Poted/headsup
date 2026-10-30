import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plugin versions live here rather than in a root build file so that :core never has to
// resolve the Android Gradle Plugin.
plugins {
    id("com.android.application") version "8.10.1"
    id("org.jetbrains.kotlin.android") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

android {
    namespace = "app.headsup"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.headsup"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // Native code (libjxl) for the phones the app is for.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // libjxl is built optimised also in debug builds: unoptimised it decodes many times slower.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
                cppFlags += "-O2"
            }
        }
    }

    // The mask models are read straight from the APK, so they are stored uncompressed.
    androidResources {
        noCompress += "onnx"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // A fixed debug key (not a secret) so each CI-built APK installs over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    // Mask models on the phone: objects (MobileSAM) and the sky (DeepLab, ADE20K).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
