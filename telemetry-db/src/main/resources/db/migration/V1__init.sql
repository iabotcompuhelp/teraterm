-- =============================================================
-- OpenTermX — esquema PostgreSQL v1 (Fase 3 del plan de telemetría)
-- Requiere PostgreSQL >= 14
-- =============================================================

CREATE SCHEMA IF NOT EXISTS opentermx;
SET search_path TO opentermx;

-- ---------- Tipos ----------
CREATE TYPE vendor_t AS ENUM (
  'CISCO_IOS','CISCO_IOSXE','CISCO_NXOS','ARUBA_AOSCX','ARUBA_PROVISION',
  'FORTINET','HUAWEI_VRP','MIKROTIK','JUNIPER_JUNOS','GENERIC','UNKNOWN'
);
CREATE TYPE protocol_t    AS ENUM ('SSH','TELNET','SERIAL');
CREATE TYPE port_status_t AS ENUM ('UP','DOWN','ERR_DISABLED','ADMIN_DOWN','UNKNOWN');
CREATE TYPE risk_t        AS ENUM ('SAFE','CONFIG','DANGEROUS');
CREATE TYPE decision_t    AS ENUM ('APPROVED','REJECTED','PARTIAL','AUTO_READONLY');
CREATE TYPE integration_t AS ENUM ('ZABBIX','OPMANAGER');
CREATE TYPE link_event_t  AS ENUM ('LINK_UP','LINK_DOWN','FLAPPING','ERR_DISABLED','SPEED_CHANGE');

-- ---------- Inventario ----------
CREATE TABLE devices (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  hostname        TEXT NOT NULL,
  mgmt_address    INET NOT NULL,
  port            INTEGER NOT NULL DEFAULT 22 CHECK (port BETWEEN 1 AND 65535),
  protocol        protocol_t NOT NULL DEFAULT 'SSH',
  vendor          vendor_t NOT NULL DEFAULT 'UNKNOWN',
  model           TEXT,
  os_version      TEXT,
  serial_number   TEXT,
  site            TEXT,
  role            TEXT,              -- 'core-switch','access-switch','ap','wlc','firewall','router'
  credential_ref  TEXT,              -- alias en el keystore/vault. NUNCA la credencial.
  tags            JSONB NOT NULL DEFAULT '{}'::jsonb,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (mgmt_address, port)
);
CREATE INDEX idx_devices_hostname ON devices (lower(hostname));
CREATE INDEX idx_devices_tags     ON devices USING gin (tags);

-- ---------- Interfaces (catálogo por dispositivo) ----------
CREATE TABLE interfaces (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id    BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,        -- nombre canónico tal como lo reporta el equipo
  description  TEXT,
  speed_bps    BIGINT,
  first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (device_id, name)
);

-- ---------- Sesiones ----------
CREATE TABLE sessions_log (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  session_uid  TEXT NOT NULL UNIQUE,          -- sessionId de OpenTermX
  device_id    BIGINT REFERENCES devices(id) ON DELETE SET NULL,
  protocol     protocol_t NOT NULL,
  host         TEXT NOT NULL,
  port         INTEGER NOT NULL,
  username     TEXT,
  opened_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at    TIMESTAMPTZ,
  opened_by    TEXT NOT NULL DEFAULT 'operator'  -- 'operator' | 'mcp:<clientName>'
);

-- ---------- Auditoría de comandos (reemplaza audit-ia.csv) ----------
CREATE TABLE command_audit (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  session_uid    TEXT NOT NULL,
  device_id      BIGINT REFERENCES devices(id) ON DELETE SET NULL,
  source         TEXT NOT NULL,                 -- 'mcp:propose_commands','mcp:run_readonly_command','ui'
  vendor         vendor_t NOT NULL DEFAULT 'UNKNOWN',
  read_only      BOOLEAN NOT NULL DEFAULT FALSE,
  commands       TEXT[] NOT NULL,
  rationale      TEXT,
  risk_safe      INTEGER NOT NULL DEFAULT 0,
  risk_config    INTEGER NOT NULL DEFAULT 0,
  risk_dangerous INTEGER NOT NULL DEFAULT 0,
  decision       decision_t NOT NULL,
  executed_count INTEGER NOT NULL DEFAULT 0,
  rejected_count INTEGER NOT NULL DEFAULT 0,
  output_excerpt TEXT,                          -- máx 8 KB; truncar con sufijo '…[truncated]'
  operator       TEXT,
  legacy_row_hash TEXT UNIQUE                   -- para import idempotente del CSV
);
CREATE INDEX idx_audit_time    ON command_audit (occurred_at DESC);
CREATE INDEX idx_audit_device  ON command_audit (device_id, occurred_at DESC);

-- ---------- Snapshots y diffs de configuración ----------
CREATE TABLE config_snapshots (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id     BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  taken_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  trigger       TEXT NOT NULL,                  -- 'scheduled','pre_change','post_change','manual'
  audit_id      BIGINT REFERENCES command_audit(id) ON DELETE SET NULL,
  config_sha256 CHAR(64) NOT NULL,
  config_text   TEXT NOT NULL,                  -- running-config sanitizada (ver ConfigSanitizer)
  line_count    INTEGER NOT NULL,
  UNIQUE (device_id, config_sha256, taken_at)
);
CREATE INDEX idx_snapshots_device_time ON config_snapshots (device_id, taken_at DESC);

CREATE TABLE config_diffs (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id        BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  from_snapshot_id BIGINT NOT NULL REFERENCES config_snapshots(id) ON DELETE CASCADE,
  to_snapshot_id   BIGINT NOT NULL REFERENCES config_snapshots(id) ON DELETE CASCADE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  unified_diff     TEXT NOT NULL,
  lines_added      INTEGER NOT NULL,
  lines_removed    INTEGER NOT NULL,
  audit_id         BIGINT REFERENCES command_audit(id) ON DELETE SET NULL,
  CHECK (from_snapshot_id <> to_snapshot_id)
);

-- ---------- Métricas de interfaces (serie temporal, particionada) ----------
CREATE TABLE interface_metrics (
  time            TIMESTAMPTZ NOT NULL,
  interface_id    BIGINT NOT NULL REFERENCES interfaces(id) ON DELETE CASCADE,
  oper_status     port_status_t NOT NULL DEFAULT 'UNKNOWN',
  input_rate_bps  BIGINT,
  output_rate_bps BIGINT,
  input_packets   BIGINT,    -- contadores acumulativos del equipo
  output_packets  BIGINT,
  input_errors    BIGINT,
  output_errors   BIGINT,
  crc_errors      BIGINT,
  input_drops     BIGINT,
  output_drops    BIGINT,
  utilization_in_pct  REAL CHECK (utilization_in_pct  IS NULL OR utilization_in_pct  BETWEEN 0 AND 100),
  utilization_out_pct REAL CHECK (utilization_out_pct IS NULL OR utilization_out_pct BETWEEN 0 AND 100),
  collection_method TEXT NOT NULL DEFAULT 'ssh_parse',  -- 'ssh_parse','snmp','counter_delta'
  PRIMARY KEY (interface_id, time)
) PARTITION BY RANGE (time);

-- Las particiones mensuales NO se crean acá: el código las crea on-demand al insertar
-- (MetricsRepository.ensurePartition) y el job de mantenimiento crea la del mes próximo
-- y borra las que exceden la retención. Ejemplo de partición:
-- CREATE TABLE interface_metrics_2026_06 PARTITION OF interface_metrics
--   FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE INDEX idx_metrics_iface_time ON interface_metrics (interface_id, time DESC);

-- ---------- Eventos de enlace ----------
CREATE TABLE link_events (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  interface_id  BIGINT NOT NULL REFERENCES interfaces(id) ON DELETE CASCADE,
  detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  event         link_event_t NOT NULL,
  prev_status   port_status_t,
  new_status    port_status_t,
  detail        JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_linkevents_time ON link_events (detected_at DESC);

-- ---------- Integraciones externas (Fase 4) ----------
CREATE TABLE monitoring_integrations (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind          integration_t NOT NULL,
  name          TEXT NOT NULL UNIQUE,
  base_url      TEXT NOT NULL,                 -- ej: https://zabbix.acme.local
  credential_ref TEXT NOT NULL,                -- alias en keystore. NUNCA el token/API key.
  verify_tls    BOOLEAN NOT NULL DEFAULT TRUE,
  enabled       BOOLEAN NOT NULL DEFAULT TRUE,
  extra         JSONB NOT NULL DEFAULT '{}'::jsonb,  -- ej: {"apiVersion":"7.0"}
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mapeo dispositivo local <-> id en plataforma externa
CREATE TABLE device_external_refs (
  device_id      BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  integration_id BIGINT NOT NULL REFERENCES monitoring_integrations(id) ON DELETE CASCADE,
  external_id    TEXT NOT NULL,                -- hostid de Zabbix / deviceId de OpManager
  external_name  TEXT,
  PRIMARY KEY (device_id, integration_id)
);

-- ---------- Vistas de conveniencia ----------
CREATE VIEW v_latest_interface_status AS
SELECT DISTINCT ON (m.interface_id)
       d.hostname, i.name AS interface, m.time, m.oper_status,
       m.input_rate_bps, m.output_rate_bps, m.input_errors, m.output_errors,
       m.crc_errors, m.input_drops, m.output_drops
FROM interface_metrics m
JOIN interfaces i ON i.id = m.interface_id
JOIN devices d    ON d.id = i.device_id
ORDER BY m.interface_id, m.time DESC;
