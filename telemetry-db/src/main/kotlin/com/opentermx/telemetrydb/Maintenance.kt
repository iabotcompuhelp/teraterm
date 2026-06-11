package com.opentermx.telemetrydb

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Job de mantenimiento (regla #5 de persistencia): particiones de `interface_metrics`.
 *  - crea la del mes corriente y la del próximo si no existen (insertar nunca debería
 *    encontrarse sin partición — y si pasa, MetricsRepository la crea on-the-fly);
 *  - borra las particiones cuya ventana terminó hace más de [retentionDays].
 *
 * El nombre de partición se reconstruye SOLO desde `pg_inherits` + el sufijo numérico
 * validado — mismo criterio que ensurePartition: cero input externo en DDL.
 */
class Maintenance internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun runDaily(retentionDays: Int) {
        val today = YearMonth.now(ZoneOffset.UTC)
        db.metrics.ensurePartition(today)
        db.metrics.ensurePartition(today.plusMonths(1))
        dropPartitionsOlderThan(retentionDays)
        // Fase 5B (error #43): el histórico de fingerprints no crece sin límite.
        db.fingerprints.pruneKeepingLast(FingerprintRepository.DEFAULT_KEEP_PER_DEVICE)
    }

    /** Borra particiones `interface_metrics_YYYY_MM` cuyo fin de ventana excede la retención. */
    fun dropPartitionsOlderThan(retentionDays: Int) {
        val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays.toLong())
        runCatching {
            val partitions = db.withConnection { conn ->
                conn.queryToMaps(
                    """
                    SELECT c.relname AS name
                    FROM pg_inherits h
                    JOIN pg_class c ON c.oid = h.inhrelid
                    JOIN pg_class p ON p.oid = h.inhparent
                    JOIN pg_namespace n ON n.oid = p.relnamespace
                    WHERE p.relname = 'interface_metrics' AND n.nspname = 'opentermx'
                    """.trimIndent()
                ) { }
            }
            for (row in partitions) {
                val name = row["name"] as? String ?: continue
                val m = PARTITION_NAME.matchEntire(name) ?: continue
                val (year, month) = m.destructured
                val windowEnd = YearMonth.of(year.toInt(), month.toInt()).plusMonths(1).atDay(1)
                if (windowEnd.isBefore(cutoff)) {
                    // Nombre validado por el regex (solo dígitos) — DDL seguro.
                    db.withConnection { conn ->
                        conn.createStatement().use { st -> st.execute("DROP TABLE IF EXISTS $name") }
                    }
                    log.info("Partición {} (fin {}) borrada por retención de {} días", name, windowEnd, retentionDays)
                }
            }
        }.onFailure { log.warn("mantenimiento de particiones falló: {}", it.message) }
    }

    companion object {
        private val PARTITION_NAME = Regex("""interface_metrics_(\d{4})_(\d{2})""")
    }
}
