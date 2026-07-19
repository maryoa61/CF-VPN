plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cfvpn"
    compileSdk = 35
    ndkVersion = "26.2.11394342" // اگر نصب نباشد، Gradle/Android Studio خودش از SDK Manager دانلود می‌کند

    defaultConfig {
        applicationId = "com.example.cfvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    // اگر xray برای چند ABI متفاوت است، آن‌ها را داخل src/main/assets/<abi>/xray
    // قرار دهید و در زمان اجرا بر اساس Build.SUPPORTED_ABIS انتخاب کنید،
    // یا فقط باینری arm64-v8a را برای سادگی نگه دارید.

    defaultConfig {
        ndk {
            // با ABI باینری‌های hev-socks5-tunnel که ساخته‌اید هماهنگ کنید
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // DataStore برای ذخیره‌ی تنظیمات
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Jetpack Security برای رمزنگاری فایل توکن (EncryptedFile / MasterKey)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // شبکه برای ارتباط با Cloudflare API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
