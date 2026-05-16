plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    api(project(":common"))
    implementation(project(":ai-assistant"))
    implementation(project(":macro-engine"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javalin)
    implementation(libs.javalin.ssl.plugin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)
    implementation(libs.mcp.kotlin.sdk)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockito.core)
}

tasks.test {
    useJUnitPlatform()
}

// -----------------------------------------------------------------------------
// Tests de integración Python: arrancan TestServerMain (test classpath) y pegan
// HTTP/SSE real con pytest + httpx. Linkeado a `check` para que `:mcp-server:check`
// cubra unit + integration.
// -----------------------------------------------------------------------------

val testServerClasspathFile = layout.buildDirectory.file("test-server-classpath.txt")

val testRuntime = sourceSets.named("test").map { it.runtimeClasspath }

val writeTestServerClasspath by tasks.registering {
    description = "Vuelca el runtime-classpath de tests a un archivo para que pytest lo lea."
    dependsOn(tasks.named("testClasses"))
    val outFile = testServerClasspathFile
    val testCp = testRuntime
    inputs.files(testCp)
    outputs.file(outFile)
    doLast {
        val cpString = testCp.get().files.joinToString(File.pathSeparator) { it.absolutePath }
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(cpString, Charsets.UTF_8)
    }
}

val pythonVenvDir = layout.buildDirectory.dir("python-venv")
val pythonProjectDir = layout.projectDirectory.dir("src/test/python")

fun resolveSystemPython(): String {
    System.getenv("MCP_TEST_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    val candidates = if (isWin) listOf("py", "python", "python3") else listOf("python3", "python")
    for (name in candidates) {
        val r = runCatching {
            ProcessBuilder(name, "--version").redirectErrorStream(true).start().also { it.waitFor() }
        }.getOrNull()
        if (r != null && r.exitValue() == 0) return name
    }
    return "python"
}

fun venvBinary(name: String): java.io.File {
    val venv = pythonVenvDir.get().asFile
    val isWin = org.gradle.internal.os.OperatingSystem.current().isWindows
    val sub = if (isWin) "Scripts" else "bin"
    val exe = if (isWin) "$name.exe" else name
    return venv.resolve(sub).resolve(exe)
}

val createPythonVenv by tasks.registering {
    description = "Crea `mcp-server/build/python-venv/` si no existe."
    val venv = pythonVenvDir.get().asFile
    outputs.dir(venv)
    doLast {
        if (!venv.resolve("pyvenv.cfg").exists()) {
            venv.parentFile.mkdirs()
            val python = resolveSystemPython()
            val proc = ProcessBuilder(python, "-m", "venv", venv.absolutePath)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            check(code == 0) { "Falló crear el venv con `$python -m venv`: $output" }
        }
    }
}

val installPythonDeps by tasks.registering {
    description = "Instala pyproject deps en el venv."
    dependsOn(createPythonVenv)
    val pyproject = pythonProjectDir.file("pyproject.toml").asFile
    val marker = pythonVenvDir.get().file(".deps-installed").asFile
    inputs.file(pyproject)
    outputs.file(marker)
    doLast {
        val pip = venvBinary("pip").absolutePath
        val proc = ProcessBuilder(pip, "install", "--quiet", "--upgrade", "-e", pythonProjectDir.asFile.absolutePath)
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        check(code == 0) { "pip install falló (exit=$code):\n$output" }
        marker.parentFile.mkdirs()
        marker.writeText("ok")
    }
}

val pythonTests by tasks.registering {
    group = "verification"
    description = "Corre los tests pytest de integración del servidor MCP."
    dependsOn(writeTestServerClasspath, installPythonDeps)
    doLast {
        val pytest = venvBinary("pytest").absolutePath
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        val env = mutableMapOf<String, String>()
        env.putAll(System.getenv())
        env["MCP_TEST_JAVA"] = "$javaHome/bin/java"
        env["MCP_TEST_CLASSPATH_FILE"] = testServerClasspathFile.get().asFile.absolutePath
        val pb = ProcessBuilder(pytest, "-v")
            .directory(pythonProjectDir.asFile)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine { println(it) }
        val code = proc.waitFor()
        check(code == 0) { "pytest devolvió exit=$code" }
    }
}

tasks.named("check") {
    dependsOn(pythonTests)
}