-- =============================================================
-- OpenTermX — esquema v2: identidad y perfiles de dispositivos
-- (Fase 5B del plan de telemetría)
-- =============================================================

SET search_path TO opentermx;

CREATE TYPE confidence_t  AS ENUM ('HIGH','MEDIUM','LOW');
CREATE TYPE role_source_t AS ENUM ('OPERATOR','INFERRED','IMPORTED');

-- Resultado de cada fingerprint (histórico: permite detectar cambios de OS/hardware).
-- Retención: últimos N=20 por dispositivo, limpieza en el job diario (error #43).
CREATE TABLE device_fingerprints (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id    BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  taken_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  probe_id     TEXT NOT NULL,
  vendor       vendor_t NOT NULL,
  model        TEXT,
  os_version   TEXT,
  serials      TEXT[] NOT NULL DEFAULT '{}',
  hostname     TEXT,
  ha_role      TEXT,
  confidence   confidence_t NOT NULL,
  trace_id     TEXT,                             -- correlación con logs/auditoría
  raw_excerpt  TEXT                              -- máx 2 KB, para diagnóstico
);
CREATE INDEX idx_fingerprints_device ON device_fingerprints (device_id, taken_at DESC);

-- Perfil vigente del dispositivo (1:1 con devices; el histórico vive en fingerprints + audit).
-- El rol vive en devices.role (V1); acá su procedencia y el contexto operativo.
CREATE TABLE device_profiles (
  device_id      BIGINT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  schema_version INTEGER NOT NULL DEFAULT 1,     -- versión del JSON del perfil (ProfileMigrator)
  role_source    role_source_t NOT NULL DEFAULT 'INFERRED',
  criticality    TEXT NOT NULL DEFAULT 'medium'
                 CHECK (criticality IN ('low','medium','high','critical')),
  profile        JSONB NOT NULL DEFAULT '{}'::jsonb,
  -- profile contiene: capabilities{tools[],readonlyProfile,forbidden[]},
  -- uplinks[], notes, maintenanceWindow, contact, custom{}
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by     TEXT NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_profiles_jsonb ON device_profiles USING gin (profile);

-- Vecinos descubiertos (topología). Se reemplazan en bloque por descubrimiento
-- (DELETE + INSERT en una transacción, error #41).
CREATE TABLE device_neighbors (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id       BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  local_interface TEXT NOT NULL CHECK (local_interface <> ''),
  remote_hostname TEXT NOT NULL CHECK (remote_hostname <> ''),  -- dato NO confiable (viene del cable)
  remote_port     TEXT,
  remote_device_id BIGINT REFERENCES devices(id) ON DELETE SET NULL,  -- match contra inventario
  protocol        TEXT NOT NULL CHECK (protocol IN ('LLDP','CDP','MNDP')),
  discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (device_id, local_interface, remote_hostname, protocol)
);

-- Trigger: tocar updated_at del perfil en cada UPDATE.
CREATE OR REPLACE FUNCTION touch_profile_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_profile_touch BEFORE UPDATE ON device_profiles
FOR EACH ROW EXECUTE FUNCTION touch_profile_updated_at();
