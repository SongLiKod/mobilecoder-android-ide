plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mobilecoder.ide"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mobilecoder.ide"
        minSdk = 29
        // targetSdk 必须保持 28：Android 10+ 对 targetSdk >= 29 的应用禁止 execve()
        // 自己 app home 目录里的文件（W^X，SELinux 拒 execute_no_trans），会表现为
        //   sh: …/files/bin/node: Permission denied
        // ——即使执行位齐全也照样失败，终端 / Gradle 构建 / npm 全都跑不起来。
        // Termux 至今仍用 28 就是同一个原因。不上 Google Play，故不受 target API 要求限制。
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
    }

    lint {
        // 刻意停在 targetSdk = 28：Android 10+ 对 targetSdk >= 29 的应用禁止
        // execve() 自己 app home 目录里的文件（W^X，SELinux 拒 execute_no_trans），
        // 会把内置终端、Gradle 构建、npm 全部打死（报成 Permission denied）。
        // 本项目只自行分发、不上 Google Play，这条按 Play 政策写死的检查不适用。
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Clean Architecture：UI 层（Compose）依赖领域层与数据层模块
    implementation(project(":core_common"))
    implementation(project(":core_native"))
    implementation(project(":core_storage"))
    implementation(project(":feature_editor"))
    implementation(project(":feature_terminal"))
    implementation(project(":feature_cli"))
    implementation(project(":feature_git"))
    implementation(project(":feature_ssh"))
    implementation(project(":feature_build"))
    implementation(project(":feature_ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
