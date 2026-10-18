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
            "РќРµС‚ ${libboxAar.invariantSeparatorsPath}. РЎРѕР±РµСЂРёС‚Рµ AAR: sing-box cmd/internal/build_libbox -target android " +
                "(РІСЃРµ ABI) РёР»Рё scripts/build-libbox-aar.ps1 РїСЂРё РєР»РѕРЅРµ sing-box РІ <СЂРѕРґРёС‚РµР»СЊ mobileVPN>/_deps/sing-box; " +
                "СЃРј. experimental/libbox/ffi.json в†’ DEPLOY_ANDROID."
        }
    }
}

tasks.named("preBuild").configure { dependsOn("checkLibboxAar") }

android {
    namespace = providers.gradleProperty("app.namespace").get()
    compileSdk {
        version = release(providers.gradleProperty("android.compileSdk").get().toInt()) {
            minorApiLevel = providers.gradleProperty("android.compileSdkMinor").get().toInt()
        }
    }

    defaultConfig {
        applicationId = providers.gradleProperty("app.applicationId").get()
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Русский — основной (values/), английский — values-en. Язык выбирается в системных
    // настройках приложения (Android 13+); locale_config генерируется из ресурсов.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("ru", "en")
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
            isShrinkResources = true
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // С новыми Compose BOM иконки не приходят транзитивно через material3.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** Release APK: isDebuggable=false, R8, Р±РµР· debugImplementation-Р°СЂС‚РµС„Р°РєС‚РѕРІ; РїРѕРґРїРёСЃСЊ debug-keystore РґР»СЏ Р»РѕРєР°Р»СЊРЅРѕР№ СѓСЃС‚Р°РЅРѕРІРєРё. */
tasks.register("nonDebugApk") {
    group = "build"
    description =
        "РЎРѕР±РёСЂР°РµС‚ localRelease APK Р±РµР· СЂРµР¶РёРјР° РѕС‚Р»Р°РґРєРё (Р»РѕРєР°Р»СЊРЅР°СЏ СѓСЃС‚Р°РЅРѕРІРєР°, debug key). Р’С‹С…РѕРґ: build/outputs/apk/localRelease/"
    dependsOn("assembleLocalRelease")
}
