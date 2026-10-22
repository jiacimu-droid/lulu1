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
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Keep this stable so every Lulu1 APK can cover-install the previous Lulu1 build.
        applicationId = "app.lulu"
        minSdk = 26
        targetSdk = 35
        // The supported Lulu phone is ARM64. Ship the JNI runtime with every installable variant.
        ndk { abiFilters += "arm64-v8a" }
        val releaseNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.coerceIn(1, 2_000_000_000) ?: 1
        val revision = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.take(12) ?: "local"
        versionCode = releaseNumber
        versionName = "1.0.$releaseNumber"
        buildConfigField("String", "BUILD_REVISION", "\"$revision\"")
    }

    sourceSets {
        getByName("main").res.srcDir(generatedLauncherRes)
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        getByName("release") {
            // Android 16 ART rejected R8's rewritten large coroutine method
            // LuluDeviceToolBridge.respond (VerifyError, Undefined v307).
            // Treat all installable variants as D8-only until the public
            // decision method has been split and device-verified. Disabling
            // only 'phone' leaves assembleRelease as an accidental crash path.
            // Keep the same applicationId and signer to preserve all user data.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("phone") {
            initWith(getByName("release"))
            // Phone APK inherits the safer D8-only release settings.
            // Do not re-enable R8 in any installable variant until on-device
            // ART verification passes on the affected Android 16 build.
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    lint {
        // AndroidX detector uses an incompatible Kotlin analysis ABI with AGP 8.7.3.
        // Keep all other release checks; the crashing detector cannot inspect even valid code.
        disable += "NullSafeMutableLiveData"
    }

    testOptions {
        // WorkManager uses merged AndroidX resources even in manifest-free Robolectric tests.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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


// Native Cubism integration requires the official Core distribution.
// This verification is OPT-IN and never runs during regular APK building,
// so the proven WebGL->Compose compatibility video remains available.
tasks.register("verifyCubismNativePrerequisites") {
    group = "verification"
    description = "Validate local licensed Cubism Native SDK before any NDK integration"
    doLast {
        val sdkPath = providers.gradleProperty("luluNativeSdkDir").orNull
            ?: error("Missing -PluluNativeSdkDir=/absolute/path/to/official/CubismSdkForNative. " +
                "Download the Native SDK from Live2D's official site first. " +
                "The Web Core JS file cannot replace the native Core.")
        val sdkRoot = file(sdkPath)
        check(sdkRoot.isDirectory) { "Cubism Native SDK directory does not exist: $sdkPath" }
        val headerDir = sdkRoot.resolve("Core/include")
        val arm64Lib = sdkRoot.resolve("Core/lib/android/arm64-v8a")
        check(headerDir.isDirectory) { "Official Native Core headers missing: $headerDir" }
        check(arm64Lib.isDirectory &&
            arm64Lib.listFiles()?.any { it.extension == "a" || it.extension == "so" } == true) {
            "Licensed Android ARM64 Native Core library missing: $arm64Lib"
        }
        println("Cubism Native Core ARM64 prerequisites detected. " +
            "This task does not mean an EGL/Surface renderer has been built or validated.")
    }
}

// The exact R8-generated register failure cannot be caught by JVM unit tests.
// Explicitly reject any accidental R8 re-enable in either installable variant
// before generating another APK that crashes at launch on Android 16.
tasks.register("verifyArtSafeVariants") {
    group = "verification"
    description = "Ensure installed Lulu APK variants do not trigger the known ART VerifyError"
    doLast {
        for (name in listOf("release", "phone")) {
            val variant = android.buildTypes.getByName(name)
            check(!variant.isMinifyEnabled) {
                "Do not publish ${name}: R8 caused ART VerifyError in LuluDeviceToolBridge.respond"
            }
            check(!variant.isShrinkResources) {
                "Do not resource-shrink ${name} while ART-safe packaging is enabled"
            }
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Render the OpenMoji SVG artwork bundled as text assets into local PNGs;
    // no network access or per-icon hand labeling is required at first launch.
    implementation("com.caverock:androidsvg:1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.17")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.13.0")
    // Opt-in local camera preview for AI video calls; frames are NOT uploaded to the model.
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.belerweb:pinyin4j:2.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
