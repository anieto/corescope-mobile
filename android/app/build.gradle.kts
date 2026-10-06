import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val cartoKey = providers.environmentVariable("CARTO_API_KEY")
    .orElse(localProperties.getProperty("CARTO_API_KEY", "")).get()
/** Optional Google Maps (beta) provider; without a key only CARTO is offered. */
val googleMapsKey = providers.environmentVariable("GOOGLE_MAPS_API_KEY")
    .orElse(localProperties.getProperty("GOOGLE_MAPS_API_KEY", "")).get()
/** Release signing comes only from the git-ignored local.properties; without it, release builds are unsigned. */
fun signingValue(name: String): String? = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
val releaseStore = signingValue("RELEASE_STORE_FILE")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "org.nodescope.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.nodescope.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.7.8"
        buildConfigField("String", "CARTO_API_KEY", "\"" + cartoKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        buildConfigField("boolean", "MAPS_CONFIGURED", cartoKey.isNotBlank().toString())
        buildConfigField("boolean", "GOOGLE_MAPS_CONFIGURED", googleMapsKey.isNotBlank().toString())
        manifestPlaceholders["googleMapsApiKey"] = googleMapsKey
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = signingValue("RELEASE_STORE_PASSWORD")
            keyAlias = signingValue("RELEASE_KEY_ALIAS")
            keyPassword = signingValue("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8: shrink, optimize and obfuscate (Play flags unoptimized DEX); the mapping file
            // ships inside the bundle so crash reports stay readable in Play Console.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The bundled registry holds only the default source (as on iOS), so any other source can be
    // delisted from the live registry without an app update; its icon ships in assets/icons.
    sourceSets["test"].resources.srcDir("../../shared/fixtures")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3")
    // Adaptive panes and fold/hinge information; matches the existing Compose generation.
    implementation("androidx.compose.material3.adaptive:adaptive:1.1.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.1.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // Keep app and instrumentation classpaths aligned with AndroidX Test.
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Web links open in a Custom Tab inside the app (iOS uses SFSafariViewController).
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // OpenGL supports the full API 26+ device range, including non-Vulkan GPUs.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    // Google Maps (beta): the native SDK with ordinary markers and no map ID (the free Maps SDK SKU).
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.maps.android:android-maps-utils:4.0.0")
    // QR encoding for MeshCore contact codes (iOS uses Core Image's built-in generator).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
