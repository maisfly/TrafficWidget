plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.trafficwidget"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.trafficwidget"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
