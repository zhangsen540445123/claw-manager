CREATE TABLE IF NOT EXISTS instance_delete_operations (
  operation_id VARCHAR(64) NOT NULL,
  instance_id VARCHAR(64) NOT NULL,
  instance_name VARCHAR(100) NULL,
  container_name VARCHAR(160) NULL,
  force_delete TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(40) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  wechat_account_count INT NOT NULL DEFAULT 0,
  miniapp_binding_count INT NOT NULL DEFAULT 0,
  cleanup_operation_ids_json MEDIUMTEXT NULL,
  last_error TEXT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  completed_at VARCHAR(40) NULL,
  active_instance_id VARCHAR(64)
    GENERATED ALWAYS AS (
      CASE
        WHEN status IN ('pending', 'deleting', 'delete_failed') THEN instance_id
        ELSE NULL
      END
    ) STORED,
  PRIMARY KEY (operation_id),
  UNIQUE KEY uk_instance_delete_active_instance (active_instance_id),
  KEY idx_instance_delete_instance_status (instance_id, status),
  KEY idx_instance_delete_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
