-- Provider-reported usage is additive and distinct from attempted-call quotas.
-- All aggregates are user-scoped; never persist prompts, evidence or model responses.
ALTER TABLE ai_model_daily_usage
  ADD COLUMN usage_reported_calls BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0;
