plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.martinbartin.simpleheartratemonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.martinbartin.simpleheartratemonitor"
        minSdk = 23
        targetSdk = 36
        versionCode = 8
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    // The buildFeatures for compose has been removed.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1") // Updated version
    implementation(libs.androidx.core.ktx)
    // Added for registerForActivityResult
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ADDED: Material Components Library dependency
    // Always check for the latest stable version on Google's Maven Repository:
    // https://maven.google.com/web/index.html#com.google.android.material:material
    implementation("com.google.android.material:material:1.12.0") // Using a recent stable version

    // --- All Jetpack Compose dependencies have been removed ---

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
