plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "io.glucostride"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vadimfrolov.glucostride"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.openminimed:javasake:0.2.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    testImplementation("junit:junit:4.13.2")
}
