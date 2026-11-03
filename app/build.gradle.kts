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

        // Ship native libs for one ABI only. sherpa-onnx + onnxruntime + mlkit
        // each carry .so for arm64-v8a, armeabi-v7a, x86, x86_64 → ~150 MB wasted.
        // arm64-v8a covers Xiaomi 2201123G (HyperOS 3) and all modern phones.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // PR-L25: copy the root models.json into assets at build time.
    // The update-models workflow regenerates it every 6 hours; every APK
    // ships the latest snapshot. Root file is the single source of truth.
    tasks.register<Copy>("syncModelCatalog") {
        from(rootProject.file("models.json"))
        into(layout.projectDirectory.dir("src/main/assets"))
        rename { "models.json" }
    }
    tasks.named("preBuild") { dependsOn("syncModelCatalog") }

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
    implementation("org.apache.commons:commons-compress:1.26.2")
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
