plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mobilecoder.ide.core.nativebridge"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // 设备内构建（移动码匠 IDE）传 -PmcAbi=arm64-v8a 收窄到目标 ABI；
            // PC 不传该属性 = 维持全 ABI。
            val mcAbis = providers.gradleProperty("mcAbi").getOrElse("")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (mcAbis.isNotEmpty()) {
                abiFilters.clear()
                abiFilters += mcAbis
            }
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_static")
                // 设备内构建的 ninja 并发压缩在 cmake/ninja 包装器（sdk/cmake/3.22.1/bin）
                // 里做钳制；这里不能加 -j：AGP 会把它同时喂给 configure，cmake CLI 不认。
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
