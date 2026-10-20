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

    android {
        namespace = "dev.idoodler.labimporter.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Host JVM target: only used to run the shared unit tests fast, against
    // the real loinc.db (the bundled SQLite driver ships macOS/Linux natives).
    jvm()

    // The iOS app links this as the static `LabImporterCore` framework: Xcode's
    // "Build shared Kotlin" phase runs `embedAndSignAppleFrameworkForXcode`
    // (an XCFramework is also available via
    // `assembleLabImporterCoreReleaseXCFramework`). SKIE bridges suspend
    // functions/Flows to Swift async/AsyncSequence.
    val xcf = XCFramework("LabImporterCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LabImporterCore"
            isStatic = true
            binaryOption("bundleId", "dev.idoodler.labimporter.core")
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
            implementation(libs.xmlutil.core)
        }
        // Android/JVM bundle SQLite so FTS5 is guaranteed on every OS version;
        // iOS uses the system SQLite (which ships FTS5) to avoid a second copy.
        androidMain.dependencies { implementation(libs.androidx.sqlite.bundled) }
        jvmMain.dependencies { implementation(libs.androidx.sqlite.bundled) }
        iosMain.dependencies { implementation(libs.androidx.sqlite.framework) }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// SKIE would otherwise try to upload anonymous build analytics; this project
// makes no network calls it doesn't need.
skie {
    analytics {
        disableUpload.set(true)
        enabled.set(false)
    }
}

tasks.withType<Test>().configureEach {
    // Tests that need the catalog read it from the build-generated loinc.db.
    systemProperty("loinc.db", rootProject.layout.buildDirectory.file("loinc/loinc.db").get().asFile.absolutePath)
    systemProperty("golden.dir", layout.projectDirectory.dir("src/jvmTest/golden").asFile.absolutePath)
    // ./gradlew :shared:jvmTest -Pgolden.record=true rewrites the golden files.
    systemProperty("golden.record", providers.gradleProperty("golden.record").getOrElse("false"))
}

