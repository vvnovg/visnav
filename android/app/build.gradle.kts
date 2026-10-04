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

/** Fails if a merged manifest requests network permissions (offline app). */
abstract class VerifyNoNetworkPermissions : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        check(manifest.isFile) { "merged manifest not found: $manifest" }
        val text = manifest.readText()
        val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE")
        val found = banned.filter { text.contains("\"$it\"") }
        check(found.isEmpty()) { "network permissions in merged manifest $manifest: $found" }
    }
}

val verifyNoNetworkPermissions by tasks.registering {
    description = "Fails if the merged manifest of any variant requests network permissions (offline app)."
}
tasks.named("check") { dependsOn(verifyNoNetworkPermissions) }

androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val perVariant = tasks.register<VerifyNoNetworkPermissions>("verify${cap}NoNetworkPermissions") {
            description = "Fails if the merged ${variant.name} manifest requests network permissions."
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
        }
        verifyNoNetworkPermissions.configure { dependsOn(perVariant) }
        // package<Variant> also covers install<Variant>, which does not go through assemble<Variant>.
        tasks.named { it == "assemble$cap" || it == "package$cap" }.configureEach { dependsOn(perVariant) }
    }
}

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
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
