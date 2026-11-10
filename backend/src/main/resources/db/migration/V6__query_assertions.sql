ALTER TABLE probes
    ADD COLUMN assertion_kind varchar(20),
    ADD COLUMN assertion_expected bigint,
    ADD CONSTRAINT probe_assertion_valid CHECK (
        (assertion_kind IS NULL AND assertion_expected IS NULL)
        OR (assertion_kind IS NOT NULL AND assertion_expected IS NOT NULL AND (
            (assertion_kind = 'ROW_COUNT_EQUALS' AND assertion_expected BETWEEN 0 AND 100)
            OR (assertion_kind = 'SCALAR_EQUALS' AND assertion_expected BETWEEN -9007199254740991 AND 9007199254740991)
        ))
    );
