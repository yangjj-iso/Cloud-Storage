-- Enforce the active user-visible namespace at the database boundary, for every writer.
-- NULL permits arbitrarily many recycled entries with the same name.
-- Uses the existing user_file utf8mb4_unicode_ci comparison rules (case-insensitive).
-- Existing conflicting active rows must be reconciled before V13; this migration
-- deliberately FAILS rather than silently renaming or deleting user content.
ALTER TABLE user_file
    ADD COLUMN active_file_name VARCHAR(512)
        GENERATED ALWAYS AS (CASE WHEN status=0 THEN file_name ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_drive_active_sibling (user_id,parent_id,active_file_name);
