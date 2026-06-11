plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Capa de persistencia PostgreSQL (Fase 3 del plan de telemetría). JDBC explícito +
// HikariCP + Flyway — nada de ORM. Sin dependencias de UI ni de transporte.
dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    // JSONB del perfil de dispositivo (Fase 5B): parseo/serialización + ProfileMigrator.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    // Catalog packs YAML (Fase 6A): CatalogPackImporter.
    implementation(libs.jackson.dataformat.yaml)
    // Modelo canónico InterfaceStats — lo que se persiste en interface_metrics.
    api(project(":net-parsers"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // PostgreSQL real embebido (binarios, sin Docker) para tests de migración/roundtrip.
    testImplementation(libs.zonky.embedded.postgres)
    testImplementation(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
