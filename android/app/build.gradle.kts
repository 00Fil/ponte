plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.ponte"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.ponte.app"
        minSdk = 29
        targetSdk = 35
        // Il numero di build arriva da GitHub Actions: è quello che l'app confronta per aggiornarsi.
        val build = (project.findProperty("ponteBuild") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    // Firma fissa: ogni build firmata con la stessa chiave, così l'app può aggiornarsi da sola.
    // La chiave non sta nel repo: GitHub Actions la ricava dai segreti ANDROID_KEYSTORE_B64 e ANDROID_KEYSTORE_PASSWORD.
    // Senza, si usa la chiave di debug (l'app funziona, ma ogni build ha una firma diversa e non si aggiorna da sola).
    val keystorePath = System.getenv("PONTE_KEYSTORE")
    val keystorePassword = System.getenv("PONTE_KEYSTORE_PASSWORD")
    val fixedSigning = !keystorePath.isNullOrEmpty() && !keystorePassword.isNullOrEmpty() && file(keystorePath).exists()
    signingConfigs {
        if (fixedSigning) {
            create("ponte") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = "ponte"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (fixedSigning) signingConfig = signingConfigs.getByName("ponte")
        }
        release {
            if (fixedSigning) signingConfig = signingConfigs.getByName("ponte")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
