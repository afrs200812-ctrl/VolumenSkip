plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.volumenskip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.volumenskip"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8-menos-falsos"
    }

    signingConfigs {
        create("fija") {
            storeFile = file("volumenskip.jks")
            storePassword = "volumenskip"
            keyAlias = "volumenskip"
            keyPassword = "volumenskip"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fija") }
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
    // Sin dependencias externas: solo APIs nativas de Android.
}
