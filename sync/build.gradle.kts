plugins { application; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }

application { mainClass.set("com.gamut.sync.ApplicationKt") }
dependencies { implementation(project(":core:core-model")); implementation(libs.ktor.server.core); implementation(libs.ktor.server.netty); implementation(libs.ktor.server.content.negotiation); implementation(libs.ktor.serialization.json); implementation(libs.logback) }