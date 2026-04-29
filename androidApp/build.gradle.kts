import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = providers.gradleProperty("app.namespace").get()
    compileSdk = providers.gradleProperty("android.compileSdk").get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("app.applicationId").get()
        minSdk = providers.gradleProperty("android.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("android.targetSdk").get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            // Подпись debug-keystore: APK ставится через adb/APK без отдельного keystore (не для магазина).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/** Release APK: isDebuggable=false, R8, без debugImplementation-артефактов; подпись debug-keystore для локальной установки. */
tasks.register("nonDebugApk") {
    group = "build"
    description =
        "Собирает release APK без режима отладки (для установки знакомым; не для магазина). Выход: build/outputs/apk/release/"
    dependsOn("assembleRelease")
}
