-- =============================================================
-- OpenTermX — v3.0: vendor HPE_COMWARE (Fase 6A)
--
-- Migración PROPIA y fuera de transacción (ver .conf): en PostgreSQL,
-- `ALTER TYPE ... ADD VALUE` no puede usarse en la misma transacción
-- que consuma el valor nuevo (y en PG < 12 ni siquiera puede correr
-- dentro de una transacción). V3_1 ya puede referenciarlo.
-- =============================================================

ALTER TYPE opentermx.vendor_t ADD VALUE IF NOT EXISTS 'HPE_COMWARE';
