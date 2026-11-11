import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Minutes since 1970: always grows, so a newer build installs over an older one
// no matter whether it was built on GitHub or on a PC.
val buildStamp = (System.currentTimeMillis() / 60_000L).toInt()
val buildName = System.getenv("GITHUB_RUN_NUMBER")?.let { "0.1.$it" }
    ?: ("0.1-" + SimpleDateFormat("MMdd.HHmm").format(Date()))

android {
    namespace = "hu.otthon.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "hu.otthon.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = buildStamp
        versionName = buildName
    }

    signingConfigs {
        create("release") {
            storeFile = file("launcher.keystore")
            storePassword = "launcher"
            keyAlias = "launcher"
            keyPassword = "launcher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
