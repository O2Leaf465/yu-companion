plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.leaf.vixi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.leaf.vixi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-m1"
    }

    // 本机 C:\Users\<user>\.android 不可写，AGP 自动生成 debug keystore 会失败，
    // 所以用工程内的 keystore（同样的通用调试口令）。必须先于 buildTypes 声明。
    signingConfigs {
        create("localDebug") {
            storeFile = file("../keystore/debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 主动开口（后台版）：周期任务 + 通知。
    //  · work-runtime-ktx 给 CoroutineWorker / PeriodicWorkRequest；它自带 androidx.startup，
    //    所以「启动时排任务」只要一个 Initializer + 清单里一条 meta-data，
    //    不用改 App.kt（那个文件在别人的改动里）。
    //  · core-ktx 给 NotificationCompat / ContextCompat（通知渠道、POST_NOTIFICATIONS 检查）。
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")

    // 状态事件分类的单元测试：改词表后跑 `gradlew :app:testDebugUnitTest` 就能回归
    testImplementation("junit:junit:4.13.2")
    // 单测里的 org.json 是 android.jar 的桩（调用就抛 "Stub!"），换一份真实现。
    // 聊天记录的备份/导入要走 JSON，没有它这两条路径根本测不了。
    testImplementation("org.json:json:20240303")
}
