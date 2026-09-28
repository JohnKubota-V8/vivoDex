plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vivodex"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vivodex"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
