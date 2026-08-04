import java.util.Base64

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
        Base64.getDecoder().decode(encodedDebugKeystore.readText().trim()),
    )
}

// Keep the uploaded launcher artwork as data in git, then decode it into a real PNG
// before Android resource processing. This avoids vector redraws changing the image.
val encodedLauncherIcon = project.file("src/main/icon/lulu_exact_icon.png.b64")
val generatedLauncherRes = layout.buildDirectory.dir("generated/luluLauncherRes").get().asFile
val generatedLauncherIcon = generatedLauncherRes.resolve("drawable/lulu_exact_icon.png")
if (encodedLauncherIcon.exists()) {
    generatedLauncherIcon.parentFile.mkdirs()
    generatedLauncherIcon.writeBytes(
        Base64.getDecoder().decode(encodedLauncherIcon.readText().trim()),
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

    sourceSets {
        getByName("main").res.srcDir(generatedLauncherRes)
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
    implementation("com.google.android.gms:play-services-location:21.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
