import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "roc.win.lottery.data"
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
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.serializationJson)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
            implementation("com.tom-roush:pdfbox-android:${libs.versions.tom.roush.pdfbox.get()}") {
                exclude(group = "org.bouncycastle")
            }
        }
        named("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.testExt.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutinesCore)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
        }
        jvmTest.dependencies {
            implementation(libs.pdfbox)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.ktor.clientMock)
        }
    }
}
