import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libboxAar: File = file("libs/libbox.aar")

tasks.register("checkLibboxAar") {
    doLast {
        require(libboxAar.isFile) {
            "Нет ${libboxAar.invariantSeparatorsPath}. Соберите AAR: sing-box cmd/internal/build_libbox -target android " +
                "(все ABI) или scripts/build-libbox-aar.ps1 при клоне sing-box в <родитель mobileVPN>/_deps/sing-box; " +
                "см. experimental/libbox/ffi.json → DEPLOY_ANDROID."
        }
    }
}

tasks.named("preBuild").configure { dependsOn("checkLibboxAar") }

android {
    namespace = providers.gradleProperty("app.namespace").get()
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("app.applicationId").get()
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
        val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
        val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
        val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
        val hasReleaseSigning =
            !releaseStoreFile.isNullOrBlank() &&
                !releaseStorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("localRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(mapOf("name" to "libbox", "ext" to "aar"))
    implementation(project(":shared"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** Release APK: isDebuggable=false, R8, без debugImplementation-артефактов; подпись debug-keystore для локальной установки. */
tasks.register("nonDebugApk") {
    group = "build"
    description =
        "Собирает localRelease APK без режима отладки (локальная установка, debug key). Выход: build/outputs/apk/localRelease/"
    dependsOn("assembleLocalRelease")
}
