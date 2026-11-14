import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Cloud identifiers live in secrets.properties (not committed); see secrets.properties.example.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use(::load)
}
fun secret(name: String): String = "\"${secrets.getProperty(name, "")}\""

// The BLE key bundle is not committed (.secrets/ is ignored); unit tests that need it skip without it.
val keyBundlePath: String = rootProject.file(".secrets/key_bundle.txt").absolutePath

// Release signing: .secrets/keystore.properties + its keystore (never committed). Without them a
// release build is signed with the local debug key, which is fine for trying it out.
val releaseKeys = Properties().apply {
    val file = rootProject.file(".secrets/keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.example.mygrill"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mygrill"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.9"

        buildConfigField("String", "AUTH0_BASE", secret("auth0.base"))
        buildConfigField("String", "AUTH0_AUDIENCE", secret("auth0.audience"))
        buildConfigField("String", "AUTH0_CLIENT_ID", secret("auth0.clientId"))
        buildConfigField("String", "AWS_BASE", secret("aws.base"))
        buildConfigField("String", "AWS_API_KEY", secret("aws.apiKey"))
    }

    signingConfigs {
        if (releaseKeys.isNotEmpty()) create("release") {
            storeFile = rootProject.file(".secrets/" + releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8-optimised Compose, signed with the release key when there is one.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isShrinkResources = true
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.all { it.systemProperty("keyBundle", keyBundlePath) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
