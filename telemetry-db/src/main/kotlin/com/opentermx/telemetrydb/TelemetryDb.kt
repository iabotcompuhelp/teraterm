package com.opentermx.telemetrydb

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

/**
 * Parámetros de conexión. El password viene resuelto por el caller (keystore de la app
 * o env `OPENTERMX_DB_PASSWORD`) — esta capa jamás lo loguea ni lo persiste.
 */
data class DbConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "opentermx",
    val username: String = "opentermx",
    val password: String = "",
    val maxPoolSize: Int = 5,
) {
    val jdbcUrl: String get() = "jdbc:postgresql://$host:$port/$database"
}

/**
 * Punto de entrada a la persistencia PostgreSQL (Fase 3): pool HikariCP + migraciones
 * Flyway al conectar. La invariante del módulo es la **degradación con gracia**: si la
 * BD no está disponible, [connect] devuelve `failure` y el caller sigue operando sin
 * histórico — nada de esta capa lanza hacia arriba en el camino de telemetría.
 *
 * Todo el SQL del módulo usa PreparedStatement con bind params (error #16 del catálogo).
 * La única excepción documentada es el DDL de particiones ([MetricsRepository]), donde
 * Postgres no acepta binds: el nombre se genera EXCLUSIVAMENTE de un año/mes numéricos
 * validados, nunca de input externo.
 */
class TelemetryDb private constructor(
    private val dataSource: HikariDataSource,
) : AutoCloseable {

    val devices = DeviceRepository(this)
    val interfaces = InterfaceRepository(this)
    val metrics = MetricsRepository(this)
    val linkEvents = LinkEventRepository(this)
    val audit = AuditRepository(this)
    val snapshots = SnapshotRepository(this)
    val history = HistoryQueries(this)
    val maintenance = Maintenance(this)

    /** `use {}` siempre: ninguna conexión sale del pool sin volver (error #17). */
    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    fun isAvailable(): Boolean = runCatching {
        withConnection { it.createStatement().use { st -> st.execute("SELECT 1") } }
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching { dataSource.close() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TelemetryDb::class.java)

        /**
         * Abre el pool y aplica las migraciones Flyway. Devuelve `failure` (con log de
         * warning, sin stacktrace al caller) si la BD no responde o la migración falla.
         */
        fun connect(config: DbConfig): Result<TelemetryDb> = runCatching {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                minimumIdle = 1
                connectionTimeout = 5_000
                initializationFailTimeout = 5_000
                poolName = "opentermx-telemetry"
                // Todo el esquema vive en `opentermx`; cada conexión arranca ahí.
                connectionInitSql = "SET search_path TO opentermx"
            }
            val ds = HikariDataSource(hikari)
            try {
                Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .schemas("opentermx")
                    .defaultSchema("opentermx")
                    .load()
                    .migrate()
            } catch (e: Throwable) {
                ds.close()
                throw e
            }
            log.info("Telemetría PostgreSQL conectada en {} (schema opentermx)", config.jdbcUrl)
            TelemetryDb(ds)
        }.onFailure { e ->
            log.warn(
                "PostgreSQL no disponible en {} — la telemetría sigue sin persistir ({})",
                config.jdbcUrl, e.message,
            )
        }
    }
}
