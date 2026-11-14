import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
        versionCode = 100
        versionName = "1.0.0"
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
        fun hash(f: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = FileInputStream(f)
            try {
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            } finally {
                input.close()
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
        if (file.exists() && hash(file) == sha256) return@doLast
        file.parentFile.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        val input = URI(url).toURL().openStream()
        try {
            Files.copy(input, part.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            input.close()
        }
        check(hash(part) == sha256) { "The downloaded model ${file.name} is not the expected one" }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
// Place names for the planner's search (towns, villages, peaks, passes, lakes, huts) from GeoNames
// (CC BY 4.0: commercial use with credit). Their dumps change daily, so they are not pinned by hash;
// a failed download leaves the search empty rather than failing the build.
val buildPlaces by tasks.registering {
    val target = layout.buildDirectory.file("models/assets/places.tsv.gz")
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        if (file.exists() && file.length() > 1000) return@doLast
        file.parentFile.mkdirs()
        val keep = setOf(
            "P" to null,
            "T" to setOf("MT", "MTS", "PK", "PKS", "HLL", "HLLS", "PASS", "RDGE", "CLF", "SPUR", "CRQ", "CRQS", "SDL", "VAL", "GRGE", "CNYN", "GAP"),
            "H" to setOf("LK", "LKS", "LKN", "RSV", "WTRF", "PND"),
            "S" to setOf("HUT", "HUTS", "OBPT", "TOWR", "CSTL", "CH", "MSTY", "RUIN"),
            "L" to setOf("PRK", "RESN", "AREA", "LCTY"),
        )
        var rows = 0
        val out = java.util.zip.GZIPOutputStream(java.io.FileOutputStream(file)).bufferedWriter()
        try {
            for (country in listOf("PL", "SK", "CZ")) {
                try {
                    val zip = java.util.zip.ZipInputStream(URI("https://download.geonames.org/export/dump/$country.zip").toURL().openStream())
                    try {
                        var entry = zip.nextEntry
                        while (entry != null && entry.name != "$country.txt") entry = zip.nextEntry
                        if (entry != null) {
                            val reader = zip.bufferedReader()
                            var line = reader.readLine()
                            while (line != null) {
                                val f = line.split('\t')
                                if (f.size >= 17) {
                                    val cls = f[6]
                                    val code = f[7]
                                    var wanted = false
                                    for ((c, codes) in keep) if (c == cls && (codes == null || code in codes)) wanted = true
                                    if (wanted) {
                                        val height = (if (f[15].isEmpty()) f[16] else f[15]).toIntOrNull()
                                        val elevation = if (height != null && height > -1000) height.toString() else ""
                                        val population = if (f[14].isEmpty()) "0" else f[14]
                                        out.write(listOf(f[1], f[4], f[5], elevation, code, population, country).joinToString("\t"))
                                        out.write("\n")
                                        rows++
                                    }
                                }
                                line = reader.readLine()
                            }
                        }
                    } finally {
                        zip.close()
                    }
                } catch (e: Exception) {
                    logger.warn("Place names for $country could not be fetched: ${e.message}")
                }
            }
        } finally {
            out.close()
        }
        logger.lifecycle("Place names: $rows places")
    }
}

tasks.named("preBuild") { dependsOn(downloadModels, buildPlaces) }

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
    // The planner's live view: the camera behind the sky overlay (and the light it measures).
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    // Mask models on the phone: objects (MobileSAM) and the sky (DeepLab, ADE20K).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
