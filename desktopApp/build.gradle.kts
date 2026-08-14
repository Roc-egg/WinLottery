import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":composeApp"))

    implementation(compose.desktop.currentOs)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "roc.win.lottery.DesktopEntryKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "roc.win.lottery"
            packageVersion = "1.0.0"
        }
    }
}
