plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autoclicker"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.autoclicker"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.6.4"
    }

    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val signingKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
        val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        val signingValues = listOf(keystorePath, keystorePassword, signingKeyAlias, signingKeyPassword)
        require(signingValues.all { it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) {
            "Release signing environment is incomplete"
        }
        if (signingValues.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
