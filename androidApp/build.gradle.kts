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

/** Copies the repo's LICENSE and .github/FUNDING.yml (when present) into the app's assets for Settings. */
abstract class StageAboutAssets : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        sources.files.filter { it.isFile }.forEach { it.copyTo(out.resolve(it.name.lowercase().removePrefix(".")), overwrite = true) }
    }
}

val stageAboutAssets = tasks.register<StageAboutAssets>("stageAboutAssets") {
    sources.from(rootProject.file("LICENSE"), rootProject.file(".github/FUNDING.yml"))
    outputDir.set(layout.buildDirectory.dir("generated/aboutAssets"))
}

/** Git facts stamped into BuildConfig (like the iOS Info.plist keys); empty outside a git checkout. */
fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

/** The app version, set once for both apps: `MARKETING_VERSION` in the repo root's Config.xcconfig. */
val appVersion: String = providers.fileContents(rootProject.layout.projectDirectory.file("Config.xcconfig")).asText.get()
    .lineSequence()
    .firstNotNullOfOrNull { Regex("""^\s*MARKETING_VERSION\s*=\s*(\d+\.\d+\.\d+)\s*$""").find(it)?.groupValues?.get(1) }
    ?: error("Config.xcconfig needs MARKETING_VERSION = MAJOR.MINOR.PATCH")

/** 2.0.0 → 20000, 2.1.3 → 20103: rises with every version bump (Android installs only an equal or higher one). */
val appVersionCode: Int = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "versionCode needs minor and patch below 100: $appVersion" }
    major * 10_000 + minor * 100 + patch
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
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "GIT_REMOTE", "\"${git("remote", "get-url", "origin")}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${git("rev-parse", "--abbrev-ref", "HEAD").ifEmpty { "unknown" }}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Test builds only: signed with the debug key so the APK installs
            // directly. Real releases need their own release key.
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

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
    // One Robolectric sandbox for every test: a second sandbox (e.g. another graphics
    // mode) can't load the bundled SQLite native library again, so the catalog would
    // silently fail to open there. Native graphics also let the PDF pages draw.
    systemProperty("robolectric.graphicsMode", "NATIVE")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildLoincDb, BuildLoincDb::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(stageAboutAssets, StageAboutAssets::outputDir)
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
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.health.connect)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.document.scanner)
    // Gemma on phones without Gemini Nano for apps (see ai/GemmaAi.kt).
    implementation(libs.litertlm.android)

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
