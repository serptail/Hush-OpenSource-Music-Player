// A tiny JVM program that resolves a few well-known songs through NewPipe Extractor, the same way the app does, and
// prints "SMOKE_RESULT passed=X total=Y". CI runs it before and after a version bump (.github/workflows/extractor-bump.yml).
// It uses the versions from the catalog, so a bump is tested with exactly what the app would ship.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.gamut.smoke.MainKt")
}

dependencies {
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
}
