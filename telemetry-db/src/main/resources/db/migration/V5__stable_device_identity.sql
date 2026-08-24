SET search_path TO opentermx;

ALTER TABLE devices ADD COLUMN base_mac MACADDR;

CREATE UNIQUE INDEX uq_devices_base_mac
  ON devices (base_mac) WHERE base_mac IS NOT NULL;
CREATE UNIQUE INDEX uq_devices_serial_number
  ON devices (lower(serial_number)) WHERE serial_number IS NOT NULL AND length(trim(serial_number)) > 0;

-- Observaciones históricas de identidad/dirección. Permiten conservar trazabilidad
-- cuando el mismo equipo cambia de IP, hostname o MAC observada.
CREATE TABLE device_identity_history (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  device_id      BIGINT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  observed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  hostname       TEXT NOT NULL,
  mgmt_address   INET,
  base_mac       MACADDR,
  serial_number  TEXT,
  source         TEXT NOT NULL,
  UNIQUE (device_id, hostname, mgmt_address, base_mac, serial_number)
);

CREATE INDEX idx_device_identity_history_device_time
  ON device_identity_history (device_id, observed_at DESC);
