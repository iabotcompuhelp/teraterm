package com.opentermx.telemetrydb

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/**
 * PostgreSQL real embebido (binarios zonky, sin Docker) compartido por todos los tests
 * del módulo: arrancar un postgres por clase sería lento al pedo. Se apaga con la JVM.
 *
 * Todos los tests comparten la base `postgres` + schema `opentermx` (la migración es
 * idempotente vía Flyway) — cada test usa hostnames/IPs propios para no pisarse.
 */
object EmbeddedPg {

    private val pg: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { started ->
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { started.close() } })
        }
    }

    fun config(): DbConfig = DbConfig(
        host = "localhost",
        port = pg.port,
        database = "postgres",
        username = "postgres",
        password = "postgres",
    )

    /** Conexión migrada lista para usar. Cachea una instancia por JVM de test. */
    val db: TelemetryDb by lazy {
        TelemetryDb.connect(config()).getOrThrow()
    }
}
