DO $$
DECLARE
    constraint_record RECORD;
BEGIN
    FOR constraint_record IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'evidence_record'::regclass
          AND contype = 'u'
          AND pg_get_constraintdef(oid) =
              'UNIQUE (incident_id, evidence_type, source, content_hash)'
    LOOP
        EXECUTE format('ALTER TABLE evidence_record DROP CONSTRAINT %I', constraint_record.conname);
    END LOOP;
END $$;

CREATE UNIQUE INDEX uq_evidence_active_run_content
    ON evidence_record (run_id, evidence_type, source, content_hash)
    WHERE run_id IS NOT NULL AND evidence_status = 'ACTIVE';

CREATE UNIQUE INDEX uq_evidence_active_legacy_content
    ON evidence_record (incident_id, evidence_type, source, content_hash)
    WHERE run_id IS NULL AND evidence_status = 'ACTIVE';
