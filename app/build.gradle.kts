plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sanxis.fotosduplicadas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sanxis.fotosduplicadas"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Modelos incluidos en la APK: funcionan sin conexión
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // Caras: modelos InsightFace (buffalo_s) con ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
