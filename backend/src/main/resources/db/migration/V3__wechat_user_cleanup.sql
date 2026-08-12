ALTER TABLE wechat_rebind_operations
  MODIFY phone VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  MODIFY wechat_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  MODIFY old_account_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  MODIFY new_account_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  MODIFY old_agent_id VARCHAR(37) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
  MODIFY openviking_user_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;

CREATE TABLE IF NOT EXISTS wechat_user_cleanup_operations (
  operation_id VARCHAR(64) NOT NULL,
  instance_id VARCHAR(64) NOT NULL,
  source VARCHAR(40) NOT NULL,
  subject_hash VARCHAR(64) NOT NULL,
  phone VARCHAR(32) NULL,
  wechat_user_id VARCHAR(255) NULL,
  account_id VARCHAR(255) NULL,
  agent_id VARCHAR(37) NULL,
  openviking_user_id VARCHAR(128) NULL,
  api_peer_ids_json TEXT NULL,
  old_session_ids_json MEDIUMTEXT NULL,
  protected_agent_ids_json TEXT NULL,
  snapshot_json MEDIUMTEXT NULL,
  status VARCHAR(40) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  deleted_bindings INT NOT NULL DEFAULT 0,
  deleted_files INT NOT NULL DEFAULT 0,
  deleted_database_rows INT NOT NULL DEFAULT 0,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  completed_at VARCHAR(40) NULL,
  active_subject_key VARCHAR(192)
    GENERATED ALWAYS AS (
      CASE
        WHEN status IN ('pending', 'cleaning', 'cleanup_failed')
        THEN CONCAT(instance_id, ':', subject_hash)
        ELSE NULL
      END
    ) STORED,
  PRIMARY KEY (operation_id),
  UNIQUE KEY uk_wechat_user_cleanup_active_subject (active_subject_key),
  KEY idx_wechat_user_cleanup_instance_status (instance_id, status),
  KEY idx_wechat_user_cleanup_subject_hash (subject_hash)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
