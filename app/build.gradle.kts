plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zkincamcam.xcam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zkincamcam.xcam"
        minSdk = 28
        targetSdk = 37
        versionCode = project.findProperty("APP_VERSION_CODE")?.toString()?.toInt() ?: System.getenv("APP_VERSION_CODE")?.toInt() ?: 1
        versionName = project.findProperty("APP_VERSION_NAME")?.toString() ?: System.getenv("APP_VERSION_NAME") ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("RELEASE_STORE_FILE") ?: project.findProperty("RELEASE_STORE_FILE")?.toString()
            val keystorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
            val keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("RELEASE_KEY_ALIAS")?.toString()
            val keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD")?.toString()

            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = providers.gradleProperty("xcamAbiSplits")
                .map(String::toBoolean)
                .getOrElse(false)
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // androidx.exifinterface touches android.util.Log in its static initializer;
            // default values instead of "not mocked" keeps JVM tests running.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.ffmpeg.kit.next)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.face.mesh.detection)
    implementation(libs.mediapipe.tasks.vision)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
