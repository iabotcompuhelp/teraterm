plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.opentermx.app.MainKt")
}

// Tests instancian JavaFX y los runners de CI no tienen GPU; el pipeline software
// es la opción estable para esos casos. El runtime de producción usa el pipeline
// default (D3D en Windows, Metal en macOS, GL en Linux) — JavaFX 21.0.7+ arregló
// el NPE de NGCanvas$RenderBuf.validate que afectaba a 21.0.5 con D3D.
tasks.withType<Test>().configureEach {
    jvmArgs("-Dprism.order=sw")
}

// Empaqueta un MSI con jpackage (requiere WiX 3.14 instalado en C:\Program Files (x86)\WiX Toolset v3.14).
// jpackage rechaza versiones con sufijo `-SNAPSHOT`; stripeamos al primer `-`.
val packageMsi by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Genera instalador .msi (jpackage + WiX 3.14)"
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/app")
    val outDir = layout.buildDirectory.dir("jpackage")
    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val rawVersion = project.version.toString()
    val appVersion = rawVersion.substringBefore('-')
        .takeIf { it.matches(Regex("\\d+(\\.\\d+){0,2}")) }
        ?: "0.0.0"
    val wixBin = "C:\\Program Files (x86)\\WiX Toolset v3.14\\bin"
    val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
    val jpackageExe = "$javaHome\\bin\\jpackage.exe"

    inputs.dir(installDir)
    outputs.dir(outDir)

    doFirst {
        outDir.get().asFile.deleteRecursively()
        outDir.get().asFile.mkdirs()
    }

    environment("PATH", "$wixBin;${System.getenv("PATH") ?: ""}")
    executable = jpackageExe
    argumentProviders.add {
        listOf(
            "--type", "msi",
            "--name", "OpenTermX",
            "--app-version", appVersion,
            "--vendor", "COMPUHELP",
            "--description", "OpenTermX — emulador de terminal multi-protocolo",
            "--input", installDir.get().dir("lib").asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", "com.opentermx.app.MainKt",
            "--dest", outDir.get().asFile.absolutePath,
            "--win-shortcut",
            "--win-menu",
            "--win-menu-group", "OpenTermX",
            "--win-dir-chooser",
            "--win-per-user-install",
            "--java-options", "-Dprism.order=sw",
        )
    }
}

// -----------------------------------------------------------------------------
// jpackage helpers para .dmg (Mac), .deb/.rpm (Linux). El .msi ya existe arriba.
// Cada task solo funciona en el OS correspondiente — jpackage es OS-bound.
// El JRE viene empaquetado vía jlink que hace jpackage automáticamente cuando
// se pasa `--input` con todos los jars del runtime.
// -----------------------------------------------------------------------------

fun appVersion(): String {
    val raw = project.version.toString().substringBefore('-')
    return if (raw.matches(Regex("\\d+(\\.\\d+){0,2}"))) raw else "0.0.0"
}

fun jpackageExecutable(): String {
    val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    return "$javaHome/bin/jpackage" + if (isWin) ".exe" else ""
}

val jpackageMac by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Genera instalador .dmg (jpackage). Sólo en macOS."
    dependsOn(tasks.named("installDist"))
    val installDir = layout.buildDirectory.dir("install/app")
    val outDir = layout.buildDirectory.dir("jpackage-mac")
    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    executable = jpackageExecutable()
    argumentProviders.add {
        listOf(
            "--type", "dmg",
            "--name", "OpenTermX",
            "--app-version", appVersion(),
            "--vendor", "COMPUHELP",
            "--description", "OpenTermX — emulador de terminal multi-protocolo con servidor MCP integrado",
            "--input", installDir.get().dir("lib").asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", "com.opentermx.app.MainKt",
            "--dest", outDir.get().asFile.absolutePath,
            "--mac-package-name", "OpenTermX",
            "--java-options", "-Dprism.order=sw",
        )
    }
}

val jpackageLinuxDeb by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Genera instalador .deb (jpackage). Sólo en Linux con dpkg."
    dependsOn(tasks.named("installDist"))
    val installDir = layout.buildDirectory.dir("install/app")
    val outDir = layout.buildDirectory.dir("jpackage-deb")
    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    executable = jpackageExecutable()
    argumentProviders.add {
        listOf(
            "--type", "deb",
            "--name", "opentermx",
            "--app-version", appVersion(),
            "--vendor", "COMPUHELP",
            "--description", "OpenTermX — terminal emulator with built-in MCP server",
            "--input", installDir.get().dir("lib").asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", "com.opentermx.app.MainKt",
            "--dest", outDir.get().asFile.absolutePath,
            "--linux-shortcut",
            "--linux-menu-group", "Network",
            "--java-options", "-Dprism.order=sw",
        )
    }
}

val jpackageLinuxRpm by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Genera instalador .rpm (jpackage). Sólo en Linux con rpmbuild."
    dependsOn(tasks.named("installDist"))
    val installDir = layout.buildDirectory.dir("install/app")
    val outDir = layout.buildDirectory.dir("jpackage-rpm")
    val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    executable = jpackageExecutable()
    argumentProviders.add {
        listOf(
            "--type", "rpm",
            "--name", "opentermx",
            "--app-version", appVersion(),
            "--vendor", "COMPUHELP",
            "--description", "OpenTermX — terminal emulator with built-in MCP server",
            "--input", installDir.get().dir("lib").asFile.absolutePath,
            "--main-jar", mainJarName.get(),
            "--main-class", "com.opentermx.app.MainKt",
            "--dest", outDir.get().asFile.absolutePath,
            "--linux-shortcut",
            "--linux-menu-group", "Network",
            "--java-options", "-Dprism.order=sw",
        )
    }
}

val installStdioProxy by tasks.registering {
    group = "distribution"
    description = "Genera el wrapper script del stdio proxy en `~/.opentermx/bin/`."
    doLast {
        val home = File(System.getProperty("user.home"))
        val binDir = File(home, ".opentermx/bin")
        binDir.mkdirs()
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
        val javaExe = "$javaHome/bin/java" + if (isWin) ".exe" else ""
        val installLib = layout.buildDirectory.dir("install/app/lib").get().asFile.absolutePath
        val cpGlob = if (isWin) "$installLib\\*" else "$installLib/*"
        val wrapperBody = if (isWin) {
            "@echo off\n\"$javaExe\" -cp \"$cpGlob\" com.opentermx.mcp.StdioProxyMain %*\n"
        } else {
            "#!/usr/bin/env bash\nexec \"$javaExe\" -cp \"$cpGlob\" com.opentermx.mcp.StdioProxyMain \"$@\"\n"
        }
        val wrapper = File(binDir, if (isWin) "opentermx-mcp-stdio.bat" else "opentermx-mcp-stdio")
        wrapper.writeText(wrapperBody)
        if (!isWin) {
            wrapper.setExecutable(true, false)
        }
        println("Wrapper instalado en: ${wrapper.absolutePath}")
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.swing")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":serial-comm"))
    implementation(project(":ssh-comm"))
    implementation(project(":telnet-comm"))
    implementation(project(":file-transfer"))
    implementation(project(":tftp-service"))
    implementation(project(":macro-engine"))
    implementation(project(":logger"))
    implementation(project(":ai-assistant"))
    implementation(project(":rest-api"))
    implementation(project(":mcp-server"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.javafx)
    implementation(libs.jsch)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.richtextfx)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
}