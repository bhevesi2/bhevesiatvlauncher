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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // reported, not blocking: the report is kept with each build
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    }
}

dependencies {
    // Windows (SMB) shares, for the wallpapers on the NAS; Bouncy Castle gives it MD4 for NTLM
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    // the real org.json: Android's own is only there on a device
    testImplementation("org.json:json:20240303")
}
