@Suppress("DSL_SCOPE_VIOLATION") // Accessing catalog libs in plugins block needs this on some setups.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    // Single source of truth for pre-release package. Rename here (+ applicationId) before release. See DECISIONS.md D1.
    namespace = "com.scraper.classroomcapture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scraper.classroomcapture"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-p10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // JNI: whisper/llama adapters land in P7/P10. Layout reserved now.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Release ABI: arm64-v8a only (DECISIONS.md D2.3).
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        debug {
            // Debug: physical arm64 + emulator (x86_64/arm64) for UI/persistence/recovery tests.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
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
        compose = true
        // ScraperApp logs its own version in diagnostics events (P3.6).
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    sourceSets {
        // Unit tests load the canonical dataset fixtures from the repo-root
        // schemas/ dir (single source of truth, also seen by CI).
        getByName("test") {
            resources.srcDir("../schemas")
        }
        // MigrationTestHelper loads exported Room schemas from test assets
        // (P2.5): <database-class>/1.json must be on the classpath.
        getByName("androidTest") {
            assets.srcDir("schemas")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
