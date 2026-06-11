# `telemetry-db` — Persistencia PostgreSQL de telemetría

Capa de datos de la Fase 3 del plan de telemetría: histórico de métricas de interfaces,
eventos de enlace, snapshots/diffs de configuración y auditoría de comandos. JDBC
explícito + **HikariCP** (pool) + **Flyway** (migraciones) — sin ORM. Requiere
PostgreSQL ≥ 14.

## Esquema (`db/migration/V1__init.sql`)

Schema `opentermx`: `devices` (inventario, `mgmt_address INET`), `interfaces`,
`sessions_log`, `command_audit` (reemplaza `audit-ia.csv`; `legacy_row_hash UNIQUE`
para import idempotente), `config_snapshots` + `config_diffs`, `interface_metrics`
(**particionada por mes** en RANGE(time)), `link_events`, `monitoring_integrations` +
`device_external_refs` (Fase 4), y la vista `v_latest_interface_status`.

## Reglas del módulo

- **Degradación con gracia**: `TelemetryDb.connect()` devuelve `Result`; sin BD, los
  repos devuelven null/false con warning en log — la telemetría en vivo nunca depende
  de la BD.
- **PreparedStatement siempre** (cero concatenación). Única excepción documentada: el
  DDL de particiones (`interface_metrics_YYYY_MM`), cuyo nombre sale exclusivamente de
  año/mes numéricos validados.
- **Particiones on-demand**: insertar en un mes sin partición la crea y reintenta una
  vez (`no partition of relation`). `Maintenance.runDaily()` pre-crea la del mes
  próximo y borra las que exceden la retención (default 90 días).
- **Sanitización de secretos** (`ConfigSanitizer`): toda línea con
  `password/secret/community/pre-shared-key/key` se guarda con el valor `<REDACTED>`;
  el sha256 de deduplicación se calcula **sobre el texto ya sanitizado**.
- **UTC en todo** (`TIMESTAMPTZ` + `OffsetDateTime`); la conversión a zona local es de
  la UI.

## Configuración

Sección `database` de los settings de OpenTermX (`~/.opentermx/settings.json`):
`enabled`, `host` (default `localhost`), `port` (5432), `database`, `username`,
`password` (cifrado con SecretCipher) — o la variable de entorno
`OPENTERMX_DB_PASSWORD` si el campo está vacío. **Nunca plaintext.**

## Tests

PostgreSQL **real embebido** (binarios zonky, sin Docker): migración limpia e
idempotente, roundtrip device→interface→metric con partición automática y vista,
link events, snapshots sanitizados, audit idempotente y mantenimiento de particiones.

```bash
./gradlew :telemetry-db:test
```
