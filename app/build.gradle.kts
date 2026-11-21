plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.aaa000111.maid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "top.aaa000111.maid"
        minSdk = 31
        targetSdk = 37
        versionCode = 100
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "litertlm"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures {
        compose = true
        aidl = true
    }
    sourceSets {
        getByName("main") {
            kotlin.directories.add("../third_party/vtfx/src/main/kotlin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Dhizuku
    implementation(libs.dhizuku.api)

    // Tink
    implementation(libs.tink.android)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Koog (via JitPack)
    implementation(libs.koog)
    implementation(libs.agents.features.memory)
    implementation(libs.prompt.executor.llms.all)

    // Compose Markdown (via JitPack)
    implementation(libs.compose.markdown)

    // Zstandard decompression (.tar.zst / .tzst rootfs support)
    implementation("com.github.luben:zstd-jni:${libs.versions.zstdJni.get()}@aar")
    testImplementation(libs.zstd.jni.jvm)

    // LiteRT-LM on-device LLM runtime (internal TinyModel)
    implementation(libs.litertlm.android)

    // Web search & HTML parsing (Ktor Client + Ksoup)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ksoup)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
