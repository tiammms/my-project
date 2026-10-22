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

    // Release key: only from the environment (CI secrets), never from git.
    val releaseStore = System.getenv("SAQTAN_KEYSTORE_FILE")?.takeIf { file(it).exists() }
    signingConfigs {
        // A fixed demo key (standard debug-key passwords, public in this repository) so that test APKs install over each other.
        // It is NOT a production key: anyone can sign an APK with it. Builds made with it are test builds only.
        create("demo") {
            storeFile = file("neuroshield-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("SAQTAN_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SAQTAN_KEY_ALIAS")
            keyPassword = System.getenv("SAQTAN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            // R8 removes unused code (e.g. thousands of unused icons): the APK gets several times smaller
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "demo")
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
    testOptions { unitTests.isReturnDefaultValues = true }
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

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
