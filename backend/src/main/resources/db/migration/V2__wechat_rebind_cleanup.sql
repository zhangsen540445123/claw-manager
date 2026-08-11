ALTER TABLE wechat_bind_links
  ADD COLUMN cleanup_stage VARCHAR(64) NULL AFTER error_message,
  ADD COLUMN cleanup_error TEXT NULL AFTER cleanup_stage;

CREATE TABLE IF NOT EXISTS wechat_rebind_operations (
  bind_token VARCHAR(96) PRIMARY KEY,
  phone VARCHAR(32) NOT NULL,
  wechat_user_id VARCHAR(255) NOT NULL,
  old_instance_id VARCHAR(64) NOT NULL,
  old_account_id VARCHAR(255) NOT NULL,
  new_instance_id VARCHAR(64) NOT NULL,
  new_account_id VARCHAR(255) NOT NULL,
  old_agent_id VARCHAR(37) NOT NULL,
  new_agent_id VARCHAR(37) NULL,
  openviking_user_id VARCHAR(128) NOT NULL,
  api_peer_ids_json TEXT NULL,
  old_session_ids_json MEDIUMTEXT NULL,
  account_snapshot_json MEDIUMTEXT NULL,
  status VARCHAR(40) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  completed_at VARCHAR(40) NULL,
  active_phone VARCHAR(32)
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('pending', 'cleaning', 'provisioning', 'cleanup_failed') THEN phone ELSE NULL END
    ) STORED,
  active_wechat_user_id VARCHAR(255)
    GENERATED ALWAYS AS (
      CASE WHEN status IN ('pending', 'cleaning', 'provisioning', 'cleanup_failed') THEN wechat_user_id ELSE NULL END
    ) STORED,
  UNIQUE INDEX uk_wechat_rebind_operations_active_phone (active_phone),
  UNIQUE INDEX uk_wechat_rebind_operations_active_wechat_user (active_wechat_user_id),
  INDEX idx_wechat_rebind_operations_phone (phone),
  INDEX idx_wechat_rebind_operations_wechat_user_id (wechat_user_id),
  INDEX idx_wechat_rebind_operations_status (status),
  INDEX idx_wechat_rebind_operations_old_instance_id (old_instance_id),
  INDEX idx_wechat_rebind_operations_new_instance_id (new_instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Terminal operation statuses are 'completed' and 'cancelled'. String foreign keys are intentionally
-- omitted because existing production tables may use a different utf8mb4 collation; application
-- row locks and validation preserve consistency without deleting the redacted operation audit.
