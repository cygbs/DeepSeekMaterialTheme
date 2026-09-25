import com.android.build.gradle.tasks.PackageAndroidArtifact

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ygbs.deepseekmaterial"

    defaultConfig {
        // 只在这台机器上跑：设备是 Android 13 (API 33)
        compileSdk = 36
        buildToolsVersion = "37.0.0"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 模块代码量小，先不混淆，方便看崩溃栈
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            // libxposed 的入口清单必须原样进 APK；其余 META-INF 垃圾一律丢掉
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

// 避免 AGP 往 APK 里塞 app-metadata.properties（和老版本 Gradle 的兼容写法）
tasks.withType<PackageAndroidArtifact> {
    doFirst { appMetadata.asFile.orNull?.writeText("") }
}

dependencies {
    // compileOnly：Xposed API 由框架在运行时提供，绝对不能打进 APK
    compileOnly(libs.libxposed)
    // libxposed API 的签名里带 androidx.annotation，编译期需要它在 classpath 上
    compileOnly("androidx.annotation:annotation:1.9.1")
}

// AGP 9 的内置 Kotlin 支持会自动带入 kotlin-stdlib；本模块是纯 Java，
// 不需要它（它会让 dex 从几十 KB 膨胀到 2MB）。
// 只对 App 自己的编译/运行 classpath 剔除，不要碰 lint 工具自己的 lintClassPath。
configurations
    .matching { it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") }
    .configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
