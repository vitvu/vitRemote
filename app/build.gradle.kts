plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vvtool.vitremote"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.vvtool.vitremote"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Ký bằng debug key để cài được APK release ngay
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
