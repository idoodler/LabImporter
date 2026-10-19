import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Distills the committed LOINC release zip into `loinc.db` — the same script
 * (and therefore the same database) the iOS "Generate LOINC resource" build
 * phase uses. The DB is a build product, never committed.
 */
abstract class BuildLoincDb : DefaultTask() {
    @get:InputDirectory abstract val vendorDir: DirectoryProperty
    @get:InputFile abstract val script: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val out = outputDir.get().file("loinc.db").asFile
        out.delete()
        exec.exec {
            commandLine("python3", script.get().asFile.absolutePath, vendorDir.get().asFile.absolutePath, out.absolutePath)
        }
    }
}

val buildLoincDb = tasks.register<BuildLoincDb>("buildLoincDb") {
    vendorDir.set(rootProject.layout.projectDirectory.dir("Vendor/LOINC"))
    script.set(rootProject.layout.projectDirectory.file("tools/build_loinc_resource.py"))
    outputDir.set(layout.buildDirectory.dir("generated/loincAssets"))
}

// The shared module's JVM tests read the catalog from the root build dir.
val copyLoincForTests = tasks.register<Copy>("copyLoincForTests") {
    from(buildLoincDb)
    into(rootProject.layout.buildDirectory.dir("loinc"))
}
rootProject.project(":shared").tasks.matching { it.name == "jvmTest" }.configureEach { dependsOn(copyLoincForTests) }

android {
    namespace = "dev.idoodler.labimporter"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.idoodler.labimporter"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Test builds only: signed with the debug key so the APK installs
            // directly. Real releases need an upload key (see README).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Per-ABI APKs for side-loading (the bundled OCR engine is ~11 MB per ABI).
    // Play Store builds use an AAB, which splits automatically.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Android 13+ per-app language picker, generated from the values-* folders.
        generateLocaleConfig = true
    }

    lint {
        // Android-only strings without an iOS twin fall back to English until
        // translated; tools/xcstrings_to_android.py lists them. Keep visible, not fatal.
        warning += "MissingTranslation"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.time.ExperimentalTime",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildLoincDb, BuildLoincDb::outputDir)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navsuite)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.health.connect)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.document.scanner)
}
