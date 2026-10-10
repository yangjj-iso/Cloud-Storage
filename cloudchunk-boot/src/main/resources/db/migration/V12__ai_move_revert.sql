-- Short-window conditional reversal of approved moves; no access to old file bodies.
ALTER TABLE ai_move_proposal
    ADD COLUMN applied_file_updated_at DATETIME(3) NULL,
    ADD COLUMN reverted_at DATETIME(3) NULL;
