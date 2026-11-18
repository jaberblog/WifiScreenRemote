plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wifiscreenremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.wifiscreenremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Native WebRTC engine: hardware video encoder when the device supports it.
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    // Small embedded HTTP server for local signaling and the browser control page.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
