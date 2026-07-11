plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.puremusicplayer"
    compileSdk = 36

    // 签名：用 Android SDK 自带的 debug keystore 签 release，实现「R8 优化 + 直接安装」
    //   keystore: ~/.android/debug.keystore（首次运行 SDK 时自动生成）
    //   alias:    androiddebugkey
    //   password: android
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.puremusicplayer"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 仅打包两种 ABI，兼顾低版本(armeabi-v7a)与主流(arm64-v8a)，保持包体精简
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    packaging {
        resources {
            // OkHttp 的公网域名后缀表：纯本地播放器不需要，体积 41KB
            excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
            // 未使用依赖的 LICENSE/META-INF 文件
            excludes += "META-INF/androidx/constraintlayout/constraintlayout-core/LICENSE.txt"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
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
        viewBinding = true
    }
}

// 自动生成 debug keystore（若不存在），保证 release 签名不因缺 keystore 失败
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        val ks = file(System.getProperty("user.home") + "/.android/debug.keystore")
        if (!ks.exists()) {
            ks.parentFile.mkdirs()
            exec {
                commandLine(
                    "keytool", "-genkey", "-v",
                    "-keystore", ks.absolutePath,
                    "-storepass", "android",
                    "-alias", "androiddebugkey",
                    "-keypass", "android",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US"
                )
            }
        }
    }
}

dependencies {
    val lifecycleVersion = "2.8.7"

    // 基础 AndroidX（精简核心依赖）
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // 媒体会话（锁屏/通知/媒体键）直接使用 Android 框架原生 API
    // （android.media.session.MediaSession 等，API 21+ 自带），零额外依赖，保持精简。

    // 生命周期
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // 从专辑封面取主色，做轻量动态主题
    implementation("androidx.palette:palette-ktx:1.0.0")

    // 轻量异步加载专辑封面（来自 MediaStore URI）
    implementation("io.coil-kt:coil:2.7.0")

    // 播放页封面/歌词横向分页（框架原生 ViewPager2）
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
