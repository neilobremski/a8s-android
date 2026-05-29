plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.a8s.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.a8s.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 26
        versionName = "1.21.0"
    }

    signingConfigs {
        // Stable debug keystore committed at `app/debug.keystore`.
        // Default Android Studio behavior generates a fresh keystore per
        // workstation / CI runner, so consecutive builds produce APKs
        // signed by different certs and Android refuses upgrade-in-place
        // with "App not installed as package conflicts with an existing
        // package." Committing a single keystore solves that for sideload.
        // This is NOT for Play Store distribution.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // MQTT v3 client — use the standalone jar, not the Paho Android Service
    // (Paho Android Service has broken foreground service compat on Android 8+)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // QR code scanning for Bridge Key setup

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // EncryptedSharedPreferences (Keystore-backed AES-256-GCM). The 1.0.0
    // stable line throws on Android 12+ in some configurations; 1.1.0-alpha06
    // is the de-facto-stable build.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // org.json is shipped with the Android runtime, so production code uses it
    // freely. JVM unit tests don't have it on the classpath; pull in the
    // upstream jar so `decideRoute` (and any future pure-logic test) works.
    testImplementation("org.json:json:20240303")
}
