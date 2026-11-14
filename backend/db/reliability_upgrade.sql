-- Additive migration against the selected database. Safe to run repeatedly.
CREATE TABLE IF NOT EXISTS tb_cache_invalidation_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  shop_id BIGINT NOT NULL,
  old_type_id BIGINT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at DATETIME(3) NULL,
  last_error VARCHAR(255) NULL,
  KEY idx_cache_event_due (processed_at, next_attempt_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
