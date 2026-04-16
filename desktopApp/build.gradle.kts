plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
                runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.metroid.editor.MainKt"
        nativeDistributions {
            includeAllModules = true

            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "Metroid Editor"
            packageVersion = "1.0.0"
            description = "Metroid NES ROM map viewer and tile editor"
            copyright = "© 2025 Metroid Editor"

            macOS {
                bundleID = "com.metroid.editor"
                iconFile.set(project.file("src/jvmMain/resources/macos/app_icon.icns"))
            }

            windows {
                iconFile.set(project.file("src/jvmMain/resources/windows/app_icon.ico"))
                menuGroup = "Metroid Editor"
                upgradeUuid = "a4c8e1d2-9b3f-4a7e-c5d6-2f1a8b3e7c4d"
            }

            linux {
                packageName = "metroideditor"
                iconFile.set(project.file("src/jvmMain/resources/linux/app_icon.png"))
                shortcut = true
                menuGroup = "Games"
            }
        }
    }
}
