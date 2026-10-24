plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.oeildelynx"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "fr.oeildelynx"
        minSdk = 29
        targetSdk = 35
        // Repli local pour le build debug ; en release, APP_VERSION vient toujours du tag vX.Y.Z.
        val version = System.getenv("APP_VERSION") ?: "1.0.0"
        val parts = version.split(".").map { it.toIntOrNull() ?: error("APP_VERSION doit suivre X.Y.Z") }
        require(parts.size == 3) { "APP_VERSION doit suivre X.Y.Z" }
        // Ce numéro doit augmenter : Android s’en sert pour accepter ou refuser une mise à jour.
        versionCode = parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        versionName = version
    }

    signingConfigs {
        create("release") {
            // La CI fournit le chemin et le mot de passe du keystore ; ne pas les stocker dans le dépôt.
            System.getenv("SIGNING_KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "oeil-de-lynx-release"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
