plugins {
    id("com.android.application")
}

android {
    namespace = "com.xckeji.xiaoxiong"
    compileSdk = 34
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.xckeji.xiaoxiong"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
