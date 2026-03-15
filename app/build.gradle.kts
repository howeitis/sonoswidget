plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sycamorecreek.sonoswidget"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.sycamorecreek.sonoswidget"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Jetpack Glance (widget framework)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Hilt (dependency injection)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Kotlin Coroutines
    implementation(libs.coroutines.android)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.retrofit)

    // Image loading (Coil — raw ImageLoader API only, NOT Compose integration)
    implementation(libs.coil)

    // DataStore (state persistence)
    implementation(libs.datastore.preferences)

    // Palette (album art color extraction)
    implementation(libs.palette)

    // Encrypted storage (OAuth token storage)
    implementation(libs.security.crypto)

    // WorkManager (idle-mode periodic updates)
    implementation(libs.work.runtime)

    // Material Design (companion app)
    implementation(libs.material)

    // Jetpack Compose (companion app UI only — NOT for widget code)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
}
