import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

val versionPropsFile = project.file("../version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(versionPropsFile.inputStream())
}
val appVersion = versionProps["VERSION_NAME"]?.toString() ?: "1.0.0"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }
    
    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.material3)
                implementation(compose.ui)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.components.resources)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.androidx.activity.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    namespace = "com.groovebox.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")
    sourceSets["main"].resources.srcDirs("src/commonMain/resources")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    dependencies {
        debugImplementation(libs.compose.ui.tooling)
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "LoomGroovebox"
            packageVersion = appVersion

            macOS {
                bundleID = "com.groovebox.loom"
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Loom needs microphone access for sampling and granular recording.</string>
                    """.trimIndent()
                }
                entitlementsFile.set(project.file("entitlements.plist"))
            }
        }
        buildTypes.release.proguard {
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
        jvmArgs += "-Djava.library.path=\$APPDIR:\$APPDIR/resources"
    }
}

// ── Version sync ──────────────────────────────────────────────────────────────
// Writes Version.kt from version.properties before Kotlin compilation.
// Declared here (in composeApp) so Gradle sees it as a proper peer of the
// compile tasks rather than an implicit cross-module dependency.
val syncVersionKt by tasks.registering {
    val propsFile = rootProject.file("version.properties")
    val ktFile = project.file("src/commonMain/kotlin/com/groovebox/utils/Version.kt")

    inputs.file(propsFile)
    outputs.file(ktFile)

    doLast {
        val props = Properties()
        props.load(propsFile.inputStream())
        val versionName = props["VERSION_NAME"] as String? ?: "1.0.0"
        ktFile.writeText(
            "package com.groovebox.utils\n\n" +
            "// AUTO-GENERATED — do not edit. Source of truth: version.properties\n" +
            "object Version {\n" +
            "    const val APP_VERSION = \"" + versionName + "\"\n" +
            "}\n"
        )
        println("Version.kt synced to $versionName")
    }
}

// Make every Kotlin compile task depend on syncVersionKt
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncVersionKt)
}
