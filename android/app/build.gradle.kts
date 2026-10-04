plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.visnav.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.visnav.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

val verifyNoNetworkPermissions by tasks.registering {
    description = "Fails if the merged debug manifest requests network permissions (offline app)."
    dependsOn("processDebugMainManifest")
    doLast {
        val manifest = layout.buildDirectory.file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml").get().asFile
        check(manifest.isFile) { "merged manifest not found: $manifest" }
        val text = manifest.readText()
        val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE")
        val found = banned.filter { text.contains("\"$it\"") }
        check(found.isEmpty()) { "network permissions in merged manifest: $found" }
    }
}
tasks.named("check") { dependsOn(verifyNoNetworkPermissions) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.onnxruntime.android)
    implementation(libs.maplibre.android)
}
