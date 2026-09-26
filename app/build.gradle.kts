plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.hilt); alias(libs.plugins.ksp) }

android { namespace = "com.hush"; compileSdk = 35
    defaultConfig { applicationId = "com.hush"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":core:core-model")); implementation(project(":core:core-common")); implementation(project(":core:core-database"));
    implementation(libs.androidx.palette.ktx); implementation(project(":core:core-network")); implementation(project(":core:core-player")); implementation(project(":core:core-designsystem")); implementation(project(":feature:feature-search")); implementation(project(":feature:feature-library")); implementation(project(":feature:feature-player-ui")); implementation(project(":feature:feature-resolver"))
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.lifecycle.runtime); implementation(libs.androidx.activity.compose); implementation(libs.androidx.navigation.compose); implementation(libs.hilt.navigation.compose); implementation(platform(libs.androidx.compose.bom)); implementation(libs.androidx.compose.ui); implementation(libs.androidx.compose.material3); implementation(libs.androidx.compose.icons); implementation(libs.hilt.android); ksp(libs.hilt.compiler)
}