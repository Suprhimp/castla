import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// Reads the highest semver tag from `git tag`. Used by debug builds so the
// installed APK reflects the actual latest release (CI strips/injects versionName
// for release builds — debug runs locally without that injection, so we derive
// it from git here). Fails open to the defaultConfig versionName if git is
// unavailable.
fun gitLatestSemverTag(): String? = try {
    val proc = ProcessBuilder("git", "tag", "--list", "v*", "--sort=-version:refname")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    if (proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.inputStream.bufferedReader().readLines()
            .map { it.trim().removePrefix("v") }
            .firstOrNull { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }
    } else {
        proc.destroyForcibly(); null
    }
} catch (_: Throwable) { null }

// Total commit count — used as debug versionCode so each rebuild after pulling
// new commits gets a higher code than any previously installed debug build.
fun gitCommitCount(): Int = try {
    val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    if (proc.waitFor(2, TimeUnit.SECONDS)) {
        proc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
    } else {
        proc.destroyForcibly(); 0
    }
} catch (_: Throwable) { 0 }

android {
    namespace = "com.castla.mirror"
    compileSdk = 35

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.castla.mirror"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // versionNameSuffix intentionally not set here — overridden via
            // androidComponents.onVariants below so the debug name follows the
            // current git tag rather than the stub defaultConfig.versionName.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val tag = gitLatestSemverTag()
        val commitCount = gitCommitCount()
        variant.outputs.forEach { output ->
            val nameProvider = output.versionName
            val codeProvider = output.versionCode
            if (tag != null) {
                nameProvider.set("$tag-debug")
            } else {
                nameProvider.set(nameProvider.get() + "-debug")
            }
            if (commitCount > 0) {
                codeProvider.set(commitCount)
            }
        }
    }
}

dependencies {
    // NanoHTTPD (HTTP + WebSocket server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")

    // Android Instrumented Tests (스크린샷 자동화용)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}