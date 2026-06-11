plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Conectores READ-ONLY a plataformas de monitoreo (Fase 4 del plan de telemetría):
// Zabbix (JSON-RPC) y OpManager (REST). Sin JavaFX, sin Javalin — HTTP con
// java.net.http.HttpClient y JSON con Jackson.
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}
