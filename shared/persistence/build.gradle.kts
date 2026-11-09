import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

kotlin {
    android {
        namespace = "roc.win.lottery.persistence"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    jvm()
    iosArm64()
    iosSimulatorArm64 {
        // 模拟器从 __TEXT,__entitlements 段读取应用身份；测试程序嵌入后才能访问钥匙串，验证 AI 连接配置的钥匙串读写。
        binaries.matching { it is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable }.configureEach {
            linkerOpts(
                "-sectcreate",
                "__TEXT",
                "__entitlements",
                file("src/iosTest/simulator-keychain.entitlements").absolutePath,
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.okio)
            implementation(libs.room3.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
    add("kspJvm", libs.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
