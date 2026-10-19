import java.io.FileInputStream
import java.util.Properties

// نقرأ مفاتيح الـ API من local.properties (ملف لا يُرفع إلى Git) ونمررها كـ BuildConfig
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(FileInputStream(localPropsFile))
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chelseasaver.dream"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chelseasaver.dream"
        // minSdk 26 (Android 8.0) يغطي عمليًا كل أجهزة Android TV الحديثة المستخدمة فعليًا،
        // ويتيح استخدام أيقونة Adaptive Icon بصيغة XML بدون الحاجة لملفات PNG منفصلة.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val openWeatherKey = localProps.getProperty("OPENWEATHER_API_KEY", "")
        val footballKey = localProps.getProperty("FOOTBALL_DATA_API_KEY", "")
        buildConfigField("String", "OPENWEATHER_API_KEY", "\"$openWeatherKey\"")
        buildConfigField("String", "FOOTBALL_DATA_API_KEY", "\"$footballKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // لا توجد مكتبات خارجية — كل الشبكة والـ JSON تعتمد على ما يوفره Android SDK مباشرة
    // (java.net.HttpURLConnection + org.json) لتقليل احتمالية مشاكل الإصدارات أثناء البناء.
}
