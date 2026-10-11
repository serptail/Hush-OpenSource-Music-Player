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
        // CI passes these on every release (see .github/workflows/release.yml); a local build stays 1 / "1.0".
        versionCode = (project.findProperty("gamut.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("gamut.versionName") as String?) ?: "1.0"

        // Where the app looks for updates: the latest GitHub release's latest.json. Blank (local builds) = no update check.
        val updateRepo = (project.findProperty("gamut.repo") as String?).orEmpty()
        val updateUrl = if (updateRepo.isBlank()) "" else "https://github.com/$updateRepo/releases/latest/download/latest.json"
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateUrl\"")

        multiDexEnabled = true

        // Local builds keep all four (emulators). CI passes -Pgamut.abis=... to ship a much smaller APK, because every
        // required update is a full download and yt-dlp's python + ffmpeg are copied per ABI.
        ndk {
            val abis = ((project.findProperty("gamut.abis") as String?) ?: "x86,x86_64,armeabi-v7a,arm64-v8a").split(",")
            abiFilters += abis.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Brand logos live in the repo-root gamut-brandkit/ folder; this makes them part of R.drawable.
    sourceSets {
        getByName("main") {
            res.srcDir("${rootDir}/gamut-brandkit/res")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // An installed app can only be updated by an APK signed with the SAME key. CI signs every release with the keystore
    // from its secrets; without them (a local build) the debug key is used, as before.
    val ciKeystore = System.getenv("GAMUT_KEYSTORE_FILE").orEmpty()
    signingConfigs {
        create("release") {
            if (ciKeystore.isNotBlank()) {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("GAMUT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GAMUT_KEY_ALIAS")
                keyPassword = System.getenv("GAMUT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = if (ciKeystore.isNotBlank()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    // App updates (checks GitHub, downloads the APK)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

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