plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.anct"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.anct"
        minSdk = 29
        // PRoot must execute guest ELF files from its private rootfs. Android 10+
        // permits this only for applications targeting API 28 or lower.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        val storePath = providers.gradleProperty("ANCT_KEYSTORE").orNull
        val storePasswordValue = providers.gradleProperty("ANCT_KEYSTORE_PASSWORD").orNull
        if (storePath != null && storePasswordValue != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePasswordValue
                keyAlias = providers.gradleProperty("ANCT_KEY_ALIAS").orNull ?: "anct"
                keyPassword = providers.gradleProperty("ANCT_KEY_PASSWORD").orNull ?: storePasswordValue
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libtermux.so"
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    lint {
        // Community-channel application. API 28 is required for PRoot W^X.
        disable += "ExpiredTargetSdkVersion"
        // The upstream AAR has a 4 KiB library, but the packaged APK replaces
        // that exact path with our 16 KiB aligned JNI build.
        disable += "Aligned16KB"
    }
}

val verifyRuntimeAssets by tasks.registering {
    doLast {
        val required = listOf(
            rootProject.file("runtime-proot/src/main/assets/proot/bootstrap-aarch64.zip") to 1_000_000L,
            rootProject.file("runtime-proot/src/main/assets/proot/bootstrap-aarch64.sha256") to 64L,
            rootProject.file("runtime-proot/src/main/assets/proot/proot-distro.zip") to 10_000L,
            rootProject.file("runtime-proot/src/main/assets/proot/proot-distro.sha256") to 64L,
            rootProject.file("runtime-native/src/main/assets/native/droidspaces-aarch64") to 100_000L,
            rootProject.file("runtime-native/src/main/assets/native/busybox-aarch64") to 100_000L,
        )
        val missing = required.filter { (file, minimum) -> !file.isFile || file.length() < minimum }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release runtime assets are missing. Run scripts/build-runtimes.sh first:\n" +
                    missing.joinToString("\n") { " - ${it.first.relativeTo(rootProject.projectDir)}" },
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyRuntimeAssets)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":runtime-proot"))
    implementation(project(":runtime-native"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.3.0")

    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("com.github.topjohnwu.libsu:io:5.2.1")
    implementation("com.github.termux.termux-app:terminal-view:0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:0.118.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

