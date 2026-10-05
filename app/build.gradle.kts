import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeDesktop)
    // No `application` plugin: the Compose plugin provides its own `run` and `package*`
    // tasks, and `application` collides with `run` at configuration time.
}

dependencies {
    implementation(project(":core"))
    implementation(project(":levels"))
    implementation(project(":scripting"))
    implementation(libs.bundles.kotlinxEcosystem)

    // The UI module is the only one that touches Compose. Nothing below it does.
    implementation(compose.desktop.currentOs)

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "fr.godox.replikube.app.MainKt"
        nativeDistributions {
            // jpackage rejects a name with spaces, which is the plugin's default.
            packageName = "replikube"
            packageVersion = "1.0.0"
            description = "Reproduce 3D shapes by writing Kotlin"
            vendor = "replikube"

            // The compiler needs reflection and instrumentation at runtime; without
            // these the app dies on startup with a confusing NoClassDefFoundError.
            modules("java.instrument", "java.management", "jdk.unsupported")

            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
        }
    }
}
