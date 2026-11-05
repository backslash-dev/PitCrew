plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.pitcrew.app"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.pitcrew.app"
        // Pleos Connect 사이닝 키 로테이션 요건으로 28 미만 불가
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SERVER_URL", "\"${providers.gradleProperty("pitcrew.serverUrl").getOrElse("")}\"")
        buildConfigField("String", "API_TOKEN", "\"${providers.gradleProperty("pitcrew.apiToken").getOrElse("")}\"")
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트의 로컬 서버로 붙는다. 비워 두면 앱 안 목업으로 동작한다
            buildConfigField("String", "SERVER_URL", "\"${providers.gradleProperty("pitcrew.serverUrl").getOrElse("http://10.0.2.2:3000/")}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.tts)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
}
