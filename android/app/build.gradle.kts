import java.time.Duration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.neuroshield.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.neuroshield.app"
        minSdk = 29            // Android 10: call-screening role, modern background rules
        targetSdk = 35
        versionCode = (System.getenv("NS_VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("NS_VERSION_CODE") ?: "0")
    }

    // One small APK per processor type (most phones: arm64-v8a) plus a universal APK that runs everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // ---- Signing -------------------------------------------------------------------------------------------------
    // Release key: from the environment (CI secrets) or from android/keystore.properties on the developer's computer
    // (that file and *.jks/*.keystore outside this module are ignored by git). Secrets are never written in this file.
    val localSigning = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun secret(env: String, prop: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: localSigning.getProperty(prop)?.takeIf { it.isNotBlank() }
    val releaseStore = secret("SAQTAN_KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    if (releaseStore != null) {
        val missing = listOf("SAQTAN_KEYSTORE_PASSWORD" to "storePassword", "SAQTAN_KEY_ALIAS" to "keyAlias", "SAQTAN_KEY_PASSWORD" to "keyPassword")
            .filter { (e, p) -> secret(e, p) == null }.map { it.first }
        if (missing.isNotEmpty()) throw GradleException("Release key found, but not set: " + missing.joinToString())
    }
    // The old demo key (standard debug-key passwords, public in this repository). It is NOT a production key: anyone
    // can sign an APK with it. Used for debug builds when present, so that new test builds install over the old
    // ones; for release builds only when explicitly allowed (CI test builds), never silently.
    val demoStore = file("neuroshield-debug.keystore").takeIf { it.exists() }
    val allowDemoRelease = System.getenv("SAQTAN_ALLOW_DEMO_SIGNING") == "1"
    signingConfigs {
        if (demoStore != null) create("demo") {
            storeFile = demoStore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = secret("SAQTAN_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("SAQTAN_KEY_ALIAS", "keyAlias")
            keyPassword = secret("SAQTAN_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        debug {
            // without the demo file the standard Android debug key of this computer is used (created automatically)
            if (demoStore != null) signingConfig = signingConfigs.getByName("demo")
        }
        release {
            // R8 removes unused code (e.g. thousands of unused icons): the APK gets several times smaller
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                releaseStore != null -> signingConfigs.getByName("release")
                allowDemoRelease && demoStore != null -> signingConfigs.getByName("demo").also {
                    logger.warn("Saqtan: release APK is signed with the PUBLIC demo key - test build only, not for publication")
                }
                else -> null.also {
                    logger.warn("Saqtan: no release key (SAQTAN_KEYSTORE_* or keystore.properties): the release APK is left unsigned")
                }
            }
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { events("started", "failed") }; it.timeout.set(Duration.ofMinutes(10)) }
    }
    lint {
        abortOnError = false          // findings are reported by ci/security_checks.py (security errors fail CI there)
        xmlReport = true
        htmlReport = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Camera + on-device face detection (Google ML Kit, bundled model: works offline)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Offline speech recognition (Vosk / Kaldi). Language models are downloaded by the app on request.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Experimental synthetic-voice check: small spectrogram CNN (ml/voice_spoof) run on the phone by ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Offline text recognition for screenshots/photos (Tesseract). Language files are downloaded by the app on request.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
