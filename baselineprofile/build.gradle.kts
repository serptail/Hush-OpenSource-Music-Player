plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.gamut.baselineprofile"
    compileSdk = 35
    defaultConfig {
        minSdk = 28   // baseline profiles are generated on API 28+
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
}

// Run ":app:generateBaselineProfile" with a phone or emulator (API 28+, NOT rooted-emulator-only) connected.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.uiautomator)
}
