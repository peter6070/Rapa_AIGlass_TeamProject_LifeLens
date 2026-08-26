fun readDotEnv(): Map<String, String> {
    val file = rootProject.file(".env")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .toList().asReversed().associate { line ->
            val (key, value) = line.split('=', limit = 2)
            key.trim() to value.trim().trim('"', '\'')
        }
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val dotEnv = readDotEnv()
val metaApplicationId = System.getenv("META_DAT_APPLICATION_ID") ?: dotEnv["META_DAT_APPLICATION_ID"].orEmpty()
val metaClientToken = System.getenv("META_DAT_CLIENT_TOKEN") ?: dotEnv["META_DAT_CLIENT_TOKEN"].orEmpty()
val relayServerUrl = System.getenv("RELAY_SERVER_URL") ?: dotEnv["RELAY_SERVER_URL"] ?: "http://10.0.2.2:8080"
val relayApiKey = System.getenv("RELAY_API_KEY") ?: dotEnv["RELAY_API_KEY"].orEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.kimchi_r1"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.kimchi_r1"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["mwdat_application_id"] = metaApplicationId
        manifestPlaceholders["mwdat_client_token"] = metaClientToken
        buildConfigField("String", "DEFAULT_RELAY_SERVER_URL", relayServerUrl.asBuildConfigString())
        buildConfigField("String", "DEFAULT_RELAY_API_KEY", relayApiKey.asBuildConfigString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Signed with the existing local debug certificate for installable internal releases.
            // Replace this with a dedicated upload key before Play Store distribution.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Some third-party DAT/Matter/OpenCV binaries are still 4 KB ELF aligned.
        // Compress them so Android extracts them instead of enabling 16 KB backcompat mode.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.opencv)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
