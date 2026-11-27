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

    // The release key is never in the repository: the build takes it from the environment
    // (in GitHub Actions from the repository's secrets SIGNING_KEYSTORE_B64 and SIGNING_PASSWORD).
    // Without it the release is not signed, and the build stops before publishing anything.
    val signingFile = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        create("release") {
            if (signingFile != null) {
                storeFile = file(signingFile)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "otthon"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingFile != null) signingConfigs.getByName("release") else null
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
        // also into the build's log, to read without downloading the report
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
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
    // installs the baseline profile (src/main/baseline-prof.txt) on the TV, so the system compiles
    // the launcher ahead of time instead of while it runs
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // the film library's player: Dolby Vision, HDR, the TV's own decoders, MKV/MP4, subtitles
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
    // the real org.json: Android's own is only there on a device
    testImplementation("org.json:json:20240303")
}
