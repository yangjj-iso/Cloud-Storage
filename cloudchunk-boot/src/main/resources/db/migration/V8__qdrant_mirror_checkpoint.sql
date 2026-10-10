-- Qdrant is disposable derived state. Checkpoints allow eventual backfill and refresh.
ALTER TABLE knowledge_embedding_job
  ADD COLUMN qdrant_synced_at DATETIME(6) NULL,
  ADD KEY idx_qdrant_sync (status, qdrant_synced_at);
