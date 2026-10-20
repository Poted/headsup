import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// Bytecode stays at Java 17 so the module can be consumed by the Android app.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.garmin:fit:21.218.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
