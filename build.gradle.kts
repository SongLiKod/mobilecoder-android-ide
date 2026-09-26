// Root build file — MobileCoder 移动码匠
// 全局插件声明（各模块按需启用，版本统一由 gradle/libs.versions.toml 管理）

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
