plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = System.getenv("SYNQ_ANDROID_KEYSTORE") ?: System.getenv("BRIDGEY_ANDROID_KEYSTORE")
val releaseStorePassword = System.getenv("SYNQ_ANDROID_STORE_PASSWORD") ?: System.getenv("BRIDGEY_ANDROID_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SYNQ_ANDROID_KEY_ALIAS") ?: System.getenv("BRIDGEY_ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SYNQ_ANDROID_KEY_PASSWORD") ?: System.getenv("BRIDGEY_ANDROID_KEY_PASSWORD")

android {
    namespace = "dev.synq.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "bharadwaj.synq.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "2.5.4"
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:discovery"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("synq.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    }
