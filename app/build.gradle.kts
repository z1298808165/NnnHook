plugins {
    id("com.android.application")
}

android {
    namespace = "io.nnnhook"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.nnnhook"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 正式签名从环境变量读，本地/CI 没有就用 debug 签名兜底（保证产物能装）
    val ksPath = System.getenv("NNNHOOK_KEYSTORE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("NNNHOOK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NNNHOOK_KEY_ALIAS")
                keyPassword = System.getenv("NNNHOOK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (ksPath != null && file(ksPath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // .so 不压缩，保证 System.loadLibrary 能直接映射
        noCompress += "so"
    }

    packaging {
        resources {
            // libxposed aar 带 lint.jar 与多份 LICENSE，会与主工程冲突，排除
            excludes += setOf(
                "META-INF/lint.jar",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**"
            )
        }
        jniLibs {
            useLegacyPackaging = false
            // 四个 ABI 全留：DexKit aar 自带四份 libdexkit.so。
            // 早先这里排掉了 x86/x86_64，结果模块在模拟器上必抛
            // UnsatisfiedLinkError，bridge 建不起来。
        }
    }
}

dependencies {
    // libxposed API 102：编译期可见，运行时由 LSPosed 注入
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit：必须打进 APK，aar 内含 libdexkit.so
    implementation("org.luckypray:dexkit:2.0.5")

    // aar 传递依赖，显式声明避免解析差异
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    // 真机/模拟器上验证 DexKit 建桥与查询是否可用的仪器测试
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
