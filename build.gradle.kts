plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.opentermx"
    version = "1.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Gradle 9 no longer provides the JUnit Platform launcher transitively;
    // every Java/Kotlin module that runs JUnit 5 tests needs it on the test
    // runtime classpath explicitly.
    plugins.withId("java") {
        dependencies {
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
    }
}

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Verifica ciclos y dependencias prohibidas entre módulos."

    val buildFiles = subprojects.map { it.layout.projectDirectory.file("build.gradle.kts") }
    inputs.files(buildFiles)

    doLast {
        val projectNames = subprojects.map { it.path }.toSet()
        val dependencyPattern = Regex("project\\(\\\"(:[^\\\"]+)\\\"\\)")
        val graph = subprojects.associate { module ->
            val text = module.layout.projectDirectory.file("build.gradle.kts").asFile.readText()
            module.path to dependencyPattern.findAll(text).map { it.groupValues[1] }.toSet()
        }

        val errors = mutableListOf<String>()
        graph.forEach { (module, dependencies) ->
            val unknown = dependencies - projectNames
            if (unknown.isNotEmpty()) errors += "$module depende de módulos desconocidos: $unknown"
            if (module != ":app" && ":app" in dependencies) {
                errors += "$module no puede depender de la capa de composición :app"
            }
        }
        if (graph[":common"].orEmpty().isNotEmpty()) {
            errors += ":common debe permanecer libre de dependencias hacia otros módulos del proyecto"
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(node: String, path: List<String>) {
            if (node in visiting) {
                errors += "Ciclo de módulos: ${(path + node).joinToString(" -> ")}"
                return
            }
            if (!visited.add(node)) return
            visiting += node
            graph[node].orEmpty().forEach { visit(it, path + node) }
            visiting -= node
        }
        graph.keys.forEach { visit(it, emptyList()) }

        check(errors.isEmpty()) { errors.joinToString("\n") }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Ejecuta los checks agregados del build y valida límites de módulos."
    dependsOn(verifyModuleBoundaries)
}
