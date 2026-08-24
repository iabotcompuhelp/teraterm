SET search_path TO opentermx;

-- Bitácora funcional append-only. Conserva identidad visible aunque el equipo sea
-- eliminado posteriormente del inventario; details nunca debe contener credenciales.
CREATE TABLE device_activity (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  device_id       BIGINT REFERENCES devices(id) ON DELETE SET NULL,
  device_name     TEXT NOT NULL,
  mgmt_address    INET,
  actor           TEXT NOT NULL,
  activity_type   TEXT NOT NULL,
  summary         TEXT NOT NULL,
  outcome         TEXT NOT NULL,
  source          TEXT NOT NULL,
  correlation_id  TEXT,
  details         JSONB NOT NULL DEFAULT '{}'::jsonb,
  CHECK (length(device_name) > 0),
  CHECK (length(actor) > 0),
  CHECK (length(activity_type) > 0),
  CHECK (length(summary) > 0),
  CHECK (length(outcome) > 0),
  CHECK (length(source) > 0)
);

CREATE INDEX idx_device_activity_device_time
  ON device_activity (device_id, occurred_at DESC);
CREATE INDEX idx_device_activity_address_time
  ON device_activity (mgmt_address, occurred_at DESC);
CREATE INDEX idx_device_activity_type_time
  ON device_activity (activity_type, occurred_at DESC);

CREATE FUNCTION reject_device_activity_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'device_activity is append-only';
END;
$$;

CREATE TRIGGER device_activity_no_update
BEFORE UPDATE OR DELETE ON device_activity
FOR EACH ROW EXECUTE FUNCTION reject_device_activity_mutation();
