plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.zktsw.androidsmtcbridge"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.zktsw.androidsmtcbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val localReleaseKey = rootProject.file("../signing/media-bridge-release.jks")
    signingConfigs {
        create("localRelease") {
            if (localReleaseKey.exists()) {
                storeFile = localReleaseKey
                storePassword = "androidsmtcbridge"
                keyAlias = "media-bridge"
                keyPassword = "androidsmtcbridge"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (localReleaseKey.exists()) {
                signingConfig = signingConfigs.getByName("localRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
