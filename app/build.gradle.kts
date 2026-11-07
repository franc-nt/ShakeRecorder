plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shakerecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shakerecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.25"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        create("release") {
            // Never generate or silently substitute a key: installed updates require continuity.
            System.getenv("SHAKE_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("SHAKE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SHAKE_KEY_ALIAS")
            keyPassword = System.getenv("SHAKE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
