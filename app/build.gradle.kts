import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Chiave fissa: le build successive si installano sopra senza perdere l'indice
val p12 = file("cercafoto.p12")
val p12b64 = file("cercafoto.p12.b64")
if (!p12.exists() && p12b64.exists()) p12.writeBytes(Base64.getDecoder().decode(p12b64.readText().trim()))

android {
    namespace = "eu.iz2oii.cercafoto"
    compileSdk = 35
    defaultConfig {
        applicationId = "eu.iz2oii.cercafoto"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("fissa") {
            storeFile = file("cercafoto.p12")
            storeType = "pkcs12"
            storePassword = "cercafoto"
            keyAlias = "cercafoto"
            keyPassword = "cercafoto"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fissa")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "tflite" }
    packaging { jniLibs { pickFirsts += "**/libtensorflowlite_jni.so" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Modelli ML Kit "bundled": inclusi nell'APK, funzionano offline
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // FaceNet per il riconoscimento dei volti
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
