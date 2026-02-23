plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "green.mobileapps.musictageditor"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "green.mobileapps.musictageditor"
        minSdk = 24
        targetSdk = 36
        versionCode = 10
        versionName = "2.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
    viewBinding {
        enable = true
    }
}

dependencies {
    // base
    implementation(libs.androidx.core.ktx.v1120)
    implementation(libs.androidx.appcompat.v161)

    // ui
    implementation(libs.glide)
    implementation(libs.swiperefreshlayout)
    annotationProcessor(libs.compiler)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.material.v1110)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // activity ktx for permission request contract
    implementation(libs.androidx.activity.ktx)

    // coroutines for background threading
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // media3 support
    implementation(libs.media)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.media3.session)

    implementation(libs.library)
    implementation(libs.media3.ui)

    implementation(libs.jaudiotagger)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    implementation(libs.review.ktx)
}