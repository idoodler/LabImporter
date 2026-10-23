// Quality check for on-device models: runs the app's real prompt and the shared
// post-processing over sample reports with known values, on this computer.
// Not part of the app or CI (it needs a multi-GB model) — see README.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.litertlm.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.idoodler.labimporter.evaluation.EvaluateKt")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.named<JavaExec>("run") {
    dependsOn(":androidApp:buildLoincDb")
    workingDir = rootProject.projectDir
    // The LOINC catalog the app ships, built by the Android module.
    systemProperty("eval.verbose", providers.gradleProperty("verbose").getOrElse("false"))
    systemProperty("loinc.db", project(":androidApp").layout.buildDirectory.file("generated/loincAssets/loinc.db").get().asFile.absolutePath)
}
