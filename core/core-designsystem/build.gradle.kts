plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hush.core.designsystem"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    // Canvas, border, background, clickable, LazyColumn-level foundation APIs (version comes from the BOM).
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core.ktx)
    // coil.compose also brings Coil 2's core (coil.imageLoader / ImageRequest) used by AdaptiveBackground.kt
    implementation(libs.coil.compose)

    // Backdrop blur for Acrylic.kt. api (not implementation): HazeState appears in AcrylicHost / acrylicSource signatures, so feature modules need it on their compile classpath.
    api(libs.haze)

    api(libs.phosphor.icon)
}