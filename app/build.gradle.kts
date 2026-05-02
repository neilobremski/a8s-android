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
        versionCode = 1
        versionName = "1.0.0"
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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // org.json is shipped with the Android runtime, so production code uses it
    // freely. JVM unit tests don't have it on the classpath; pull in the
    // upstream jar so `decideRoute` (and any future pure-logic test) works.
    testImplementation("org.json:json:20240303")
}
