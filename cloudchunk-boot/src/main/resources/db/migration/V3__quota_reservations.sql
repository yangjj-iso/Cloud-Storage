ALTER TABLE user_quota
    ADD COLUMN reserved_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER used_bytes,
    ADD COLUMN reserved_file_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER file_count;

UPDATE user_quota q
JOIN (
    SELECT r.user_id,
           SUM(s.file_size) AS bytes_to_reserve,
           COUNT(*) AS files_to_reserve
    FROM file_reference r
    JOIN upload_session s ON s.file_id = r.file_id
    WHERE s.status IN (0, 1)
    GROUP BY r.user_id
) pending ON pending.user_id = q.user_id
SET q.reserved_bytes = pending.bytes_to_reserve,
    q.reserved_file_count = pending.files_to_reserve,
    q.used_bytes = IF(q.used_bytes >= pending.bytes_to_reserve,
                      q.used_bytes - pending.bytes_to_reserve, 0),
    q.file_count = IF(q.file_count >= pending.files_to_reserve,
                      q.file_count - pending.files_to_reserve, 0);
