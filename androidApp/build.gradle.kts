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
        // Every string ships in all 25 languages (tools/xcstrings_to_android.py
        // generates values-*/strings.xml); a missing translation fails the build.
        fatal += "MissingTranslation"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // Robolectric renders Compose UI tests on the JVM with the app's real resources.
        unitTests.isIncludeAndroidResources = true
        // Plain JVM tests (no Robolectric): android.jar stubs such as Log return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
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

// Unit tests run on the host JVM (Robolectric): use the bundled SQLite driver's
// JVM build, which carries host natives, so tests open the real LOINC catalog.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled-android"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}"))
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric reaches into FileDescriptor internals when it sets up the application.
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    dependsOn(buildLoincDb)
    systemProperty("loinc.db", layout.buildDirectory.file("generated/loincAssets/loinc.db").get().asFile.absolutePath)
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
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    // Compose UI tests idle through Espresso; 3.5 (via ui-test) predates Android 16's InputManager.
    testImplementation(libs.androidx.test.espresso)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
