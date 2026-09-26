plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // 主题是跨 feature 的 UI 横切关注点：MobileCoderTheme / LocalAppPalette 需被
    // feature_editor、feature_terminal、feature_git 等共同订阅，故 core_common 启用 Compose。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mobilecoder.ide.core.common"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // 主题主题色 → Material3 ColorScheme 的映射（见 theme/MobileCoderTheme.kt）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
