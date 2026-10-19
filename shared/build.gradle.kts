import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.skie)
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidLibrary {
        namespace = "dev.idoodler.labimporter.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Host JVM target: only used to run the shared unit tests fast, against
    // the real loinc.db (the bundled SQLite driver ships macOS/Linux natives).
    jvm()

    // The iOS app links this as `LabCore.xcframework` (built by
    // `./gradlew :shared:assembleLabCoreReleaseXCFramework`). SKIE bridges
    // suspend functions/Flows to Swift async/AsyncSequence.
    val xcf = XCFramework("LabCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LabCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // Tests that need the catalog read it from the build-generated loinc.db.
    systemProperty("loinc.db", rootProject.layout.buildDirectory.file("loinc/loinc.db").get().asFile.absolutePath)
}

