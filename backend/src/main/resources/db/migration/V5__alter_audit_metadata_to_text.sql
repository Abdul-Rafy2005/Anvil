ALTER TABLE audit_log_entries ALTER COLUMN metadata TYPE text USING metadata::text;
