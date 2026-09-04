import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** 桌面安装包和原生启动器的统一名称。 */
val desktopPackageName = "WinLottery"

/** Android、iOS、macOS 与 Windows 统一使用的语义版本号。 */
val desktopPackageVersion = "1.3.1"

/** Windows 安装包跨版本复用的稳定升级标识。 */
val desktopWindowsUpgradeUuid = "BB1BC520-8D33-30F8-AA0A-352A3E2186A0"

/** Windows 开始菜单中展示桌面预览版的分组名称。 */
val desktopWindowsMenuGroup = "给我中"

/** 与 Android/iOS 品牌图标同源的 Windows 多尺寸图标。 */
val desktopWindowsIconFile = project.file("src/main/packaging/WinLottery.ico")

/** 与 Android/iOS 品牌图标同源的 macOS Retina 图标。 */
val desktopMacIconFile = project.file("src/main/packaging/WinLottery.icns")

/** Windows MSI 在 Compose 分发目录中的相对路径。 */
val desktopMsiRelativePath = "compose/binaries/main/msi/$desktopPackageName-$desktopPackageVersion.msi"

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
                iconFile.set(desktopMacIconFile)
            }

            windows {
                // 当前用户安装固定使用 LocalAppData，避免自选盘符导致卸载回滚目录权限异常。
                perUserInstall = true
                dirChooser = false
                menu = true
                menuGroup = desktopWindowsMenuGroup
                upgradeUuid = desktopWindowsUpgradeUuid
                iconFile.set(desktopWindowsIconFile)
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

tasks.register<Exec>("verifyPackagedWindowsMsi") {
    group = "verification"
    description = "验证 Windows MSI 的架构、版本、安装范围、升级标识和关键载荷"
    dependsOn("packageMsi")

    /** 当前版本应生成并接受检查的 Windows MSI。 */
    val installer =
        layout.buildDirectory
            .file(desktopMsiRelativePath)
            .get()
            .asFile

    /** 使用 Windows Installer 只读接口检查 MSI 的脚本。 */
    val verifier = rootProject.file("tools/windows/verify-packaged-msi.ps1")

    inputs.file(installer)
    inputs.file(verifier)
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        verifier.absolutePath,
        "-MsiPath",
        installer.absolutePath,
        "-ExpectedVersion",
        desktopPackageVersion,
        "-ExpectedUpgradeCode",
        desktopWindowsUpgradeUuid,
    )
}
