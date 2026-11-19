plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Semantic version — see /version.properties at repo root
fun readVersionProp(key: String, fallback: String): String {
    val f = rootProject.file("version.properties")
    if (!f.exists()) return fallback
    return f.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")?.trim()
        ?: fallback
}
val vMajor = readVersionProp("major", "1")
val vMinor = readVersionProp("minor", "0")

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
        val vc = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = vc
        versionName = "$vMajor.$vMinor.$vc"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// C54: unique versionCode per ABI so Cafe Bazaar accepts all 4 split APKs.
// base * 10 + abi_offset  ->  1 = arm64-v8a, 2 = armeabi-v7a, 3 = x86, 4 = x86_64
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            // Cafe Bazaar / Play Store ABI rule:
            //   AAB < universal(ALL) < armeabi-v7a(ARM32) < arm64-v8a(ARM64)
            // x86/x86_64 slot after ARM. AAB has no ABI filter (the store
            // splits it) — its own offset keeps it distinct from universal.apk.
            val isAab = output.outputFileName.orNull?.endsWith(".aab") == true
            val offset = when {
                isAab                -> 0  // AAB (Play/Bazaar splits it) — lowest
                abi == null          -> 1  // universal APK (ALL)
                abi == "armeabi-v7a" -> 2  // ARM32
                abi == "arm64-v8a"   -> 3  // ARM64 — highest among ARM
                abi == "x86"         -> 4
                abi == "x86_64"      -> 5
                else                 -> 6
            }
            val base = output.versionCode.orNull ?: return@forEach
            output.versionCode.set(base * 10 + offset)
        }
    }
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
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
