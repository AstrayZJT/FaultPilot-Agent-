ALTER TABLE incident_run
    ADD COLUMN IF NOT EXISTS source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS external_ref VARCHAR(256);

CREATE UNIQUE INDEX IF NOT EXISTS uq_incident_external_ref
    ON incident_run (source, external_ref)
    WHERE external_ref IS NOT NULL;
