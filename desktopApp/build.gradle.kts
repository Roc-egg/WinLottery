import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** 桌面安装包和原生启动器的统一名称。 */
val desktopPackageName = "WinLottery"

/** 与当前移动端稳定版一致的桌面预览版版本号。 */
val desktopPackageVersion = "1.3.0"

/** 当前宿主平台在 Compose 分发目录中的原生启动器相对路径。 */
val desktopLauncherRelativePath =
    when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> {
            "$desktopPackageName.app/Contents/MacOS/$desktopPackageName"
        }

        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> {
            "$desktopPackageName/$desktopPackageName.exe"
        }

        else -> {
            "$desktopPackageName/bin/$desktopPackageName"
        }
    }

dependencies {
    implementation(project(":shared"))
    implementation(project(":shared:recognition"))

    implementation(compose.desktop.currentOs)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "roc.win.lottery.DesktopEntryKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            description = "本地处理、人工确认并查询官方开奖数据的彩票核对工具"
            vendor = "roc"
            copyright = "Copyright © 2026 roc"
            licenseFile.set(rootProject.file("LICENSE"))

            macOS {
                bundleID = "roc.win.lottery"
                dockName = "给我中"
                appCategory = "public.app-category.utilities"
            }
        }
    }
}

tasks.register<Exec>("verifyPackagedOnnxRuntime") {
    group = "verification"
    description = "验证桌面分发启动器通过无遥测子进程加载 ONNX Runtime"
    dependsOn("createDistributable")

    val launcher =
        layout.buildDirectory
            .file("compose/binaries/main/app/$desktopLauncherRelativePath")
            .get()
            .asFile
    environment("ORT_DISABLE_TELEMETRY", "0")
    commandLine(launcher.absolutePath, "--verify-packaged-onnx-runtime")
}

tasks.register<Exec>("verifyPackagedDesktopOcr") {
    group = "verification"
    description = "使用合成票面验证桌面分发启动器内的无遥测 PP-OCRv5 子进程"
    dependsOn("createDistributable")

    val launcher =
        layout.buildDirectory
            .file("compose/binaries/main/app/$desktopLauncherRelativePath")
            .get()
            .asFile
    environment("ORT_DISABLE_TELEMETRY", "0")
    commandLine(launcher.absolutePath, "--verify-packaged-desktop-ocr")
}
