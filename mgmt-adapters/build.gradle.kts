plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Contrato (SPI) de adaptadores de gestión (Fase 6C): CLI/Netmiko/Ansible/REST.
// Módulo deliberadamente fino — solo interfaces y tipos de datos, sin transporte ni I/O
// pesada. Las implementaciones concretas viven donde están sus dependencias (CLI en
// mcp-server sobre el SessionCommandRunner; REST/bridge en sus propios módulos más
// adelante).
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    // Vendor canónico, compartido con el subsistema de telemetría.
    api(project(":net-parsers"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
