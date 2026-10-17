plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camerapremium"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.camerapremium"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Deliberately no AndroidX: framework Camera2 + Views only (see docs/ARQUITETURA.md).
    implementation(project(":imaging"))
}
