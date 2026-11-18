import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
version = "1.0.$build"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // Modelli ONNX eseguiti in locale (FaceNet identico a quello dell'app Android)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")
    implementation("com.drewnoakes:metadata-extractor:2.19.0")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
}

kotlin { jvmToolchain(17) }

compose.desktop {
    application {
        mainClass = "eu.iz2oii.cercafoto.MainKt"
        jvmArgs += listOf("-Xmx4g", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "CercaFoto"
            packageVersion = "1.0.$build"
            description = "Cerca Foto - ricerca locale delle foto"
            vendor = "Pony10"
            includeAllModules = true
            windows {
                menuGroup = "Cerca Foto"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "4f7c2a6e-1d3b-4a8e-9c55-2b7d0e9f6a31"
                iconFile.set(project.file("icona.ico"))
            }
        }
    }
}
