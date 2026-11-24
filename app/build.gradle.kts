plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Injetados pelo CI: -PversionCode=<nº do run> -PgithubRepo=<dono/repo>
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val githubRepo = (project.findProperty("githubRepo") as String?) ?: ""

android {
    namespace = "com.example.babymonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.babymonitor"
        minSdk = 30
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.$appVersionCode"
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
