plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// Manifest del JAR: `Package.getImplementationVersion()` consulta este attribute en
// runtime, así que necesitamos exponerlo para que el endpoint /mcp/health y el
// resultado de `initialize` reporten la versión real del build (no la hardcoded).
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "opentermx-mcp",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// Resource generado como fallback: cuando las clases corren desde `build/classes/`
// (tests, dev runs), el manifest del JAR no está disponible. Este .properties
// vive en el classpath de runtime y BuildInfo.kt lo lee si el manifest falla.
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/resources/build-info")
    val versionString = project.version.toString()
    inputs.property("version", versionString)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/opentermx/mcp")
        dir.mkdirs()
        dir.resolve("build-info.properties").writeText(
            "version=$versionString\n",
            Charsets.UTF_8,
        )
    }
}

sourceSets.named("main") {
    resources.srcDir(generateBuildInfo)
}

dependencies {
    api(project(":common"))
    api(project(":policy-engine"))
    implementation(project(":ai-assistant"))
    implementation(project(":macro-engine"))
    // Fase 2 telemetría: parsers de output por vendor + OutputCleaner compartido.
    implementation(project(":net-parsers"))
    // Fase 3 telemetría: persistencia PostgreSQL (histórico, scheduler, audit). `api`
    // porque TelemetryStore expone TelemetryDb/DbConfig y la app los construye.
    api(project(":telemetry-db"))
    // Fase 4: conectores read-only Zabbix/OpManager. `api` porque la app construye el
    // IntegrationRegistry desde sus settings.
    api(project(":integrations"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javalin)
    implementation(libs.javalin.ssl.plugin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.everit.json.schema)
    implementation(libs.slf4j.api)
    implementation(libs.mcp.kotlin.sdk)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp)
    testImplementation(libs.mockito.core)
    // Fase 3: test de integración get_interface_stats → interface_metrics (PG embebido).
    testImplementation(libs.zonky.embedded.postgres)
    // Fase 4: mocks HTTP para los handlers de Zabbix/OpManager.
    testImplementation(libs.okhttp.mockwebserver)
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