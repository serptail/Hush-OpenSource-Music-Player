
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.gamut"
    compileSdk = 35

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        applicationId = "com.gamut"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true

        ndk {
            abiFilters += listOf(
                "x86",
                "x86_64",
                "armeabi-v7a",
                "arm64-v8a"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Project modules
    implementation(project(":core:core-model"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-player"))
    implementation(project(":core:core-designsystem"))
    implementation(project(":core:core-sync"))

    implementation(project(":feature:feature-search"))
    implementation(project(":feature:feature-library"))
    implementation(project(":feature:feature-player-ui"))
    implementation(project(":feature:feature-resolver"))

    // AndroidX and Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.palette.ktx)

    implementation(platform(libs.androidx.compose.bom))

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // WorkManager and sign-in
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.credentials)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)

    // Core library desugaring
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Startup and baseline profiles
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}