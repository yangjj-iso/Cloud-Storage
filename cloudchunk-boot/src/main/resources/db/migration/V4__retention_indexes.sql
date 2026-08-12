-- Retention purges delete by "terminal status + completion time". None of the existing
-- indexes lead with those columns, so without these the purge would full-scan exactly the
-- tables that grow without bound, which is the opposite of what it is there to fix.
ALTER TABLE event_outbox
    ADD KEY idx_outbox_published (status, published_at);

ALTER TABLE event_inbox
    ADD KEY idx_inbox_processed (status, processed_at);

ALTER TABLE object_gc_task
    ADD KEY idx_gc_completed (status, completed_at);

-- op_log already indexes created_at, but only behind user_id / op_type, so a plain range
-- scan over time cannot use either as a prefix.
ALTER TABLE op_log
    ADD KEY idx_oplog_created (created_at);
