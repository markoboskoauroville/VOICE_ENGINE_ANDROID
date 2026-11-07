plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The one place the version is written: gradle.properties appVersion (android-app.md §1).
val appVersion = (project.findProperty("appVersion") as String).trim().toInt()
// The permanent key (a PKCS12 made on the phone, ~/.voiceengine-signing), handed in by the workflow.
val keyFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "community.auroville.voiceengine"
    compileSdk = 35

    defaultConfig {
        applicationId = "community.auroville.voiceengine"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersion
        versionName = appVersion.toString()
    }

    signingConfigs {
        create("mantra") {
            if (keyFile != null) {
                storeFile = file(keyFile)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "voiceengine"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (keyFile != null) signingConfig = signingConfigs.getByName("mantra")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
