ALTER TABLE migrations
    ADD COLUMN bundle_files jsonb,
    ALTER COLUMN sql_text DROP NOT NULL,
    ADD CONSTRAINT migration_source CHECK (
        (sql_text IS NOT NULL AND bundle_files IS NULL)
        OR (sql_text IS NULL AND bundle_files IS NOT NULL AND
            CASE WHEN jsonb_typeof(bundle_files) = 'array'
                THEN jsonb_array_length(bundle_files) BETWEEN 2 AND 20
                ELSE false
            END)
    );
