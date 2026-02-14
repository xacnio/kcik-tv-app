plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("kotlin-kapt")
    id("com.google.gms.google-services")
}

android {
    namespace = "dev.xacnio.kciktv"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.xacnio.kciktv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0-beta"
    }

    sourceSets {
        getByName("main") {
            res.setSrcDirs(
                listOf(
                    "src/main/res/mobile",
                    "src/main/res/tv",
                    "src/main/res/shared"
                )
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    
    // Leanback for Android TV
    implementation("androidx.leanback:leanback:1.0.0")
    
    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    
    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Retrofit for API calls
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    implementation("jp.wasabeef:glide-transformations:4.3.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    
    // Amazon IVS Player for ultra-low latency
    implementation("com.amazonaws:ivs-player:1.48.0")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    
    // AndroidX WebKit - Provides WebSettingsCompat to remove X-Requested-With header
    implementation("androidx.webkit:webkit:1.9.0")
    
    // QR Code Generation
    implementation("com.google.zxing:core:3.5.3")
    
    // APNG for animated badges (with shared instance caching)
    implementation("com.github.penfeizhou.android.animation:apng:3.0.5")
    
    // Firebase Analytics (Privacy-focused configuration)
    // Using 32.x for Kotlin 1.9.x compatibility
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    
    // Jsoup for HTML parsing (Link Previews)
    implementation("org.jsoup:jsoup:1.17.2")

    // QR Code Scanning
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
