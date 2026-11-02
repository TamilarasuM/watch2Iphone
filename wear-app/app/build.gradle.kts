plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tamilarasu.watch2iphone"
    // 37: the current androidx releases (core 1.19.1, wear compose 1.7.1) require compiling against API 37+.
    // compileSdk only picks the APIs available at compile time; targetSdk below is what sets runtime behavior.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tamilarasu.watch2iphone"
        // 33 = Android 13: POST_NOTIFICATIONS and the API 31+ Bluetooth permissions always apply,
        // so no legacy BLUETOOTH / BLUETOOTH_ADMIN declarations or version branching are needed.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// The protocol unit tests are tiny; the default test-JVM heap (a quarter of RAM) fails on this low-memory machine.
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
