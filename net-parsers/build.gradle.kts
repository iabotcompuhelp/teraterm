plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Módulo PURO de parsing (Fase 2 del plan de telemetría): sin JavaFX, sin Javalin,
// sin coroutines — sólo stdlib. Todo lo que sabe hacer es texto crudo -> modelo canónico.
dependencies {
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
    // Comparación JSON estricta de fixtures (.expected.json)
    testImplementation("org.skyscreamer:jsonassert:1.5.3")
}

tasks.test {
    useJUnitPlatform()
}
