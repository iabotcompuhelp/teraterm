SET search_path TO opentermx;

CREATE TABLE agents (
  agent_id          TEXT PRIMARY KEY,
  display_name      TEXT NOT NULL,
  platform          TEXT NOT NULL,
  agent_version     TEXT,
  protocol_version  INTEGER NOT NULL,
  last_remote_addr  INET,
  first_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_session_count INTEGER NOT NULL DEFAULT 0,
  enabled           BOOLEAN NOT NULL DEFAULT true,
  CHECK (agent_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  CHECK (last_session_count >= 0)
);

CREATE TABLE agent_auth_events (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  agent_id     TEXT,
  remote_addr  INET,
  event_type   TEXT NOT NULL,
  outcome      TEXT NOT NULL,
  detail       TEXT,
  CHECK (event_type IN ('ENROLLED', 'ROTATED', 'REVOKED', 'AUTHENTICATED', 'AUTH_FAILED', 'IDENTITY_MISMATCH')),
  CHECK (outcome IN ('SUCCESS', 'DENIED'))
);

CREATE INDEX idx_agents_last_seen ON agents (last_seen_at DESC);
CREATE INDEX idx_agent_auth_events_agent_time ON agent_auth_events (agent_id, occurred_at DESC);

CREATE TRIGGER agent_auth_events_no_update
BEFORE UPDATE OR DELETE ON agent_auth_events
FOR EACH ROW EXECUTE FUNCTION reject_device_activity_mutation();
