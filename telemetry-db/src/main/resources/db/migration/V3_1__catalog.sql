-- =============================================================
-- OpenTermX — esquema v3.1: catálogo de marcas/tipos/modelos y
-- métodos de gestión por dispositivo (Fase 6A)
-- =============================================================

SET search_path TO opentermx;

CREATE TYPE mgmt_method_t AS ENUM ('CLI_SSH','CLI_SERIAL','NETMIKO','ANSIBLE','REST_API','SNMP');

CREATE TABLE catalog_brands (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name       TEXT NOT NULL UNIQUE,                 -- 'Aruba','HPE','MikroTik','Cisco',...
  vendor     vendor_t NOT NULL DEFAULT 'UNKNOWN',  -- enlaza con el enum técnico de parsers
  enabled    BOOLEAN NOT NULL DEFAULT TRUE,
  source     TEXT NOT NULL DEFAULT 'builtin'       -- 'builtin' | 'pack:<archivo>' | 'operator'
);

CREATE TABLE catalog_device_types (
  id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name  TEXT NOT NULL UNIQUE
);

-- Seed alineado al enum de rol publicado por get_device_profile (Fase 5C).
INSERT INTO catalog_device_types (name) VALUES
  ('switch'), ('router'), ('firewall'), ('access_point'),
  ('wireless_controller'), ('server'), ('unknown');

CREATE TABLE catalog_models (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  brand_id        BIGINT NOT NULL REFERENCES catalog_brands(id) ON DELETE CASCADE,
  device_type_id  BIGINT NOT NULL REFERENCES catalog_device_types(id),
  name            TEXT NOT NULL,                   -- '5130 EI','2930F','hAP ac2',...
  family          TEXT,                            -- 'Comware','ArubaOS-Switch','AOS-CX',...
  match_patterns  TEXT[] NOT NULL DEFAULT '{}',    -- regex contra el modelo del fingerprint (Fase 5)
  default_methods mgmt_method_t[] NOT NULL DEFAULT '{CLI_SSH}',
  metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
  -- metadata: netmikoDeviceType, ansibleCollection, restApi{...}, readonlyProfile (Fase 1 v2),
  --           parserHints, pagination, configMode, promptPattern, quirks[]
  source          TEXT NOT NULL DEFAULT 'builtin',
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (brand_id, name)
);
CREATE INDEX idx_models_meta ON catalog_models USING gin (metadata);

-- Habilitación de métodos POR DISPOSITIVO (error #55: nacen deshabilitados; el
-- opt-in registra quién y cuándo — superficie de ataque nueva, debe ser trazable).
CREATE TABLE device_management_settings (
  device_id     BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  method        mgmt_method_t NOT NULL,
  enabled       BOOLEAN NOT NULL DEFAULT FALSE,
  config        JSONB NOT NULL DEFAULT '{}'::jsonb,
  -- config por método: REST_API:{baseUrl,verifyTls}; ANSIBLE:{inventoryGroup,extraVarsAllowed[]};
  --                    NETMIKO:{deviceTypeOverride}. credential_ref SIEMPRE alias de keystore.
  credential_ref TEXT,
  enabled_by    TEXT,
  enabled_at    TIMESTAMPTZ,
  PRIMARY KEY (device_id, method)
);

ALTER TABLE devices ADD COLUMN catalog_model_id BIGINT REFERENCES catalog_models(id) ON DELETE SET NULL;
