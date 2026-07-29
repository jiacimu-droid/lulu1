plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val fixedDebugKeystore = layout.buildDirectory.file("signing/lulu-debug.keystore").get().asFile
val encodedDebugKeystore = rootProject.file("ci/lulu-debug.keystore.b64")
if (encodedDebugKeystore.exists() && !fixedDebugKeystore.exists()) {
    fixedDebugKeystore.parentFile.mkdirs()
    fixedDebugKeystore.writeBytes(
        java.util.Base64.getDecoder().decode(encodedDebugKeystore.readText().trim()),
    )
}

android {
    namespace = "com.jiacimu.lulu"
    compileSdk = 35

    defaultConfig {
        // Keep this stable so every Lulu1 APK can cover-install the previous Lulu1 build.
        applicationId = "app.lulu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            if (fixedDebugKeystore.exists()) {
                storeFile = fixedDebugKeystore
                storePassword = "lulu-ci-debug"
                keyAlias = "lulu"
                keyPassword = "lulu-ci-debug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
