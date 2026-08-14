plugins {
    // 避免各子项目在独立类加载器中重复加载插件。
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target(
            "androidApp/src/**/*.kt",
            "composeApp/src/**/*.kt",
            "desktopApp/src/**/*.kt",
            "shared/*/src/**/*.kt",
        )
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "androidApp/*.gradle.kts",
            "composeApp/*.gradle.kts",
            "desktopApp/*.gradle.kts",
            "shared/*/*.gradle.kts",
        )
        ktlint(libs.versions.ktlint.get())
    }
}
