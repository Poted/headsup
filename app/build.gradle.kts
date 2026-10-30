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

    // Models too large for the repository, fetched by the build (see below).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("models/assets"))
}

// The inpainting model for Clean up (LaMa, Apache 2.0; 208 MB, more than the repository takes):
// downloaded once at a fixed revision and checked against its hash.
val downloadModels by tasks.registering {
    val target = layout.buildDirectory.file("models/assets/lama.onnx")
    val url = "https://huggingface.co/Carve/LaMa-ONNX/resolve/c3c0c9e468934d62e79c329e35d82dd09ff8c444/lama_fp32.onnx"
    val sha256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        fun hash(f: java.io.File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (file.exists() && hash(file) == sha256) return@doLast
        file.parentFile.mkdirs()
        val part = java.io.File(file.parentFile, file.name + ".part")
        java.net.URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
        check(hash(part) == sha256) { "The downloaded model ${file.name} is not the expected one" }
        if (file.exists()) file.delete()
        check(part.renameTo(file)) { "Could not keep ${file.name}" }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }

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
