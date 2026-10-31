plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/keystore/mariaplayer.jks"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = System.getenv("KEY_ALIAS") ?: "mariaplayer"
                keyPassword = System.getenv("KEY_PASS") ?: System.getenv("KEYSTORE_PASS")
            }
        }
    }
    namespace = "ir.mariaplayer"
    compileSdk = 36
    defaultConfig {
        applicationId = "ir.mariaplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
configurations.all {
    exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
}

configurations.all {
    exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.5")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.8") {
        // JitPack POM pulls both the Android AAR and the desktop JVM jar.
        // They contain identical com.k2fsa.sherpa.onnx.* classes → duplicate-class
        // failure at checkReleaseDuplicateClasses. Android only needs the AAR.
        exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
