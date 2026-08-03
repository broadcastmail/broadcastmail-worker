CREATE INDEX idx_outbox_processing_last_attempted
    ON outbox(last_attempted_at)
    WHERE status = 'processing';