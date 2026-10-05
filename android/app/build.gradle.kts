import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.sidequest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sidequest"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        val backendUrl = providers.gradleProperty("BACKEND_URL").getOrElse("http://localhost:8000/")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")

        // The app key lives in local.properties, which is never committed
        val localProps = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
        buildConfigField("String", "APP_KEY", "\"${localProps.getProperty("APP_KEY", "")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Hackathon build: signed with the local debug key so it installs over the dev build.
            // Use a real upload key before publishing to Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
}
