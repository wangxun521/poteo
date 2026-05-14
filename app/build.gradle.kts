plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.phonecam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.phonecam"
        minSdk = 30           // Android 11
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // writingminds/FFmpegAndroid 0.3.2 only ships armv7 + x86 ffmpeg binaries.
        // Forcing armv7 keeps the APK consistent; arm64-v8a Android 11 devices still
        // run this via the 32-bit ABI bridge. Pure-64-bit devices (Pixel 7+, some
        // newer Samsungs) will fail loadBinary — pivot to MJPEG if that happens.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // CameraX
    val camerax = "1.3.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Embedded HTTP server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ffmpeg for HLS packaging.
    // Both arthenica ffmpeg-kit and mobile-ffmpeg were removed from Maven Central
    // in 2025. We fall back to writingminds (older, ffmpeg 3.0.1, async API) which
    // is still hosted. -c copy transmux + segment muxer + h264_mp4toannexb bsf are
    // all supported by ffmpeg 3.x so functionality is unaffected.
    implementation("com.writingminds:FFmpegAndroid:0.3.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
