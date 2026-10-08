plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.example.cameraapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cameraapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Java 8+ API (java.timeなど) を低バージョンのAndroidで使うために必要
        multiDexEnabled = true
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
        // Amplify SDK 2.x 以降で必須の設定
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Core Library Desugaring の本体を追加
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Amplify ライブラリ
    implementation("com.amplifyframework:aws-storage-s3:2.35.0")
    implementation("com.amplifyframework:aws-auth-cognito:2.35.0")
    implementation("com.amplifyframework:core:2.35.0")

    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(libs.androidx.runtime)

    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("com.google.android.gms:play-services-location:21.0.1")

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    implementation("com.google.guava:guava:31.1-android")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation(libs.firebase.inappmessaging)
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
