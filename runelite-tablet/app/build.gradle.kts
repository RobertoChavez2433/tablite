plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localAppData = System.getenv("LOCALAPPDATA")?.replace("\\", "/")
val winBisonExecutable =
    localAppData?.let {
        "$it/Microsoft/WinGet/Packages/WinFlexBison.win_flex_bison_Microsoft.Winget.Source_8wekyb3d8bbwe/win_bison.exe"
    }

android {
    namespace = "com.runelitetablet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.runelitetablet"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                if (winBisonExecutable != null) {
                    arguments += "-DBISON_EXECUTABLE=$winBisonExecutable"
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // GeckoView's ExoPlayer transitive dependency references POST_NOTIFICATIONS
        // but we don't use notifications — suppress to avoid false positive lint error
        disable += "NotificationPermission"
    }

    packaging {
        jniLibs {
            // The gated app-UID VirGL host is a packaged native executable, not a
            // library loaded through System.loadLibrary(). Extract native payloads
            // so ProcessBuilder can execute it from nativeLibraryDir.
            useLegacyPackaging = true
            // Preserve the byte-verified Termux host payloads. AGP's native-strip
            // pass changes the async host binary's behavior before it ever opens
            // its listener; these files are already release-built artifacts.
            keepDebugSymbols += setOf(
                "**/libvirgl_test_server_app.so",
                "**/libvirglrenderer.so",
                "**/libepoxy.so",
            )
        }
    }

    externalNativeBuild {
        cmake {
            // Wrapper that pulls in termux-x11 and adds librlawtsurface (the
            // direct-Android-surface consumer). Single CMake path per AGP
            // android block.
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Auth: GeckoView (Firefox engine) for Cloudflare-compatible OAuth with localhost redirect capture
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:133.0.20241209150345")
    // Auth: Encrypted storage for Jagex credentials
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Unit testing — core
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.2.1")

    // Real HTTP testing — MockWebServer is a real HTTP server, not a mock
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Compose UI testing with Robolectric (Phase 4+)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    // Instrumented — smoke tests only
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
