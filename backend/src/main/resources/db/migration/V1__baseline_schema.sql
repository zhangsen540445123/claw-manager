CREATE TABLE IF NOT EXISTS admins (
  id VARCHAR(64) PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  must_change_password TINYINT(1) NOT NULL DEFAULT 0,
  password_hash VARCHAR(255) NOT NULL,
  password_salt VARCHAR(128) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_sessions (
  id VARCHAR(128) PRIMARY KEY,
  admin_id VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  INDEX idx_admin_sessions_admin_id (admin_id),
  INDEX idx_admin_sessions_expires_at (expires_at),
  CONSTRAINT fk_admin_sessions_admin FOREIGN KEY (admin_id) REFERENCES admins(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS model_presets (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  provider_key VARCHAR(80) NOT NULL,
  provider_id VARCHAR(120) NOT NULL,
  model_id VARCHAR(200) NOT NULL,
  api_mode VARCHAR(80) NOT NULL,
  auth_type VARCHAR(80) NOT NULL,
  auth_provider_id VARCHAR(120) NOT NULL DEFAULT '',
  auth_method_id VARCHAR(120) NOT NULL DEFAULT '',
  base_url TEXT NULL,
  api_key TEXT NULL,
  provider_config JSON NULL,
  extra JSON NULL,
  created_at VARCHAR(40) NOT NULL,
  INDEX idx_model_presets_default (is_default),
  INDEX idx_model_presets_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS instances (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  slug VARCHAR(120) NOT NULL,
  status VARCHAR(40) NOT NULL,
  port INT NOT NULL UNIQUE,
  dashboard_url TEXT NOT NULL,
  container_name VARCHAR(160) NOT NULL UNIQUE,
  gateway_token VARCHAR(255) NOT NULL,
  plugins_allow JSON NULL,
  plugins_entries JSON NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_instances_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS instance_models (
  instance_id VARCHAR(64) NOT NULL,
  sort_order INT NOT NULL,
  preset_id VARCHAR(64) NULL,
  provider_key VARCHAR(80) NOT NULL,
  provider_id VARCHAR(120) NOT NULL,
  model_id VARCHAR(200) NOT NULL,
  api_mode VARCHAR(80) NOT NULL,
  auth_type VARCHAR(80) NOT NULL,
  auth_provider_id VARCHAR(120) NOT NULL DEFAULT '',
  auth_method_id VARCHAR(120) NOT NULL DEFAULT '',
  base_url TEXT NULL,
  api_key TEXT NULL,
  provider_config JSON NULL,
  extra JSON NULL,
  PRIMARY KEY (instance_id, sort_order),
  INDEX idx_instance_models_preset_id (preset_id),
  INDEX idx_instance_models_provider (provider_id),
  CONSTRAINT fk_instance_models_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE,
  CONSTRAINT fk_instance_models_preset FOREIGN KEY (preset_id) REFERENCES model_presets(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS instance_provisioning (
  instance_id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(40) NOT NULL,
  percent INT NOT NULL,
  stage VARCHAR(80) NOT NULL,
  message TEXT NULL,
  gateway_started_at VARCHAR(40) NULL,
  updated_at VARCHAR(40) NOT NULL,
  CONSTRAINT fk_instance_provisioning_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS instance_model_auth (
  instance_id VARCHAR(64) PRIMARY KEY,
  status VARCHAR(40) NOT NULL,
  message TEXT NULL,
  output_snippet MEDIUMTEXT NULL,
  auth_url TEXT NULL,
  prompt_label VARCHAR(255) NULL,
  needs_input TINYINT(1) NOT NULL DEFAULT 0,
  updated_at VARCHAR(40) NULL,
  CONSTRAINT fk_instance_model_auth_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wechat_paired_accounts (
  account_id VARCHAR(255) PRIMARY KEY,
  phone VARCHAR(32) NOT NULL UNIQUE,
  instance_id VARCHAR(64) NOT NULL,
  wechat_user_id VARCHAR(255) NOT NULL UNIQUE,
  remark VARCHAR(100) NULL,
  base_url TEXT NULL,
  saved_at VARCHAR(40) NULL,
  bound_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_wechat_paired_accounts_instance_id (instance_id),
  CONSTRAINT fk_wechat_paired_accounts_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wechat_account_channels (
  account_id VARCHAR(255) PRIMARY KEY,
  instance_id VARCHAR(64) NOT NULL,
  wechat_user_id VARCHAR(255) NOT NULL UNIQUE,
  status VARCHAR(40) NOT NULL DEFAULT 'unknown',
  message TEXT NULL,
  output_snippet MEDIUMTEXT NULL,
  last_started_at VARCHAR(40) NULL,
  last_error_at VARCHAR(40) NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_wechat_account_channels_instance_id (instance_id),
  INDEX idx_wechat_account_channels_status (status),
  CONSTRAINT fk_wechat_account_channels_account FOREIGN KEY (account_id) REFERENCES wechat_paired_accounts(account_id) ON DELETE CASCADE,
  CONSTRAINT fk_wechat_account_channels_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS wechat_bind_links (
  token VARCHAR(96) PRIMARY KEY,
  mode VARCHAR(20) NOT NULL,
  phone VARCHAR(32) NULL,
  instance_id VARCHAR(64) NULL,
  target_account_id VARCHAR(255) NULL,
  scanned_wechat_user_id VARCHAR(255) NULL,
  status VARCHAR(40) NOT NULL,
  qr_mode VARCHAR(40) NULL,
  qr_payload MEDIUMTEXT NULL,
  qr_link TEXT NULL,
  qr_expires_at VARCHAR(40) NULL,
  error_message TEXT NULL,
  created_by_admin_id VARCHAR(64) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  started_at VARCHAR(40) NULL,
  expires_at VARCHAR(40) NULL,
  completed_at VARCHAR(40) NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_wechat_bind_links_phone (phone),
  INDEX idx_wechat_bind_links_instance_id (instance_id),
  INDEX idx_wechat_bind_links_target_account_id (target_account_id),
  INDEX idx_wechat_bind_links_scanned_wechat_user_id (scanned_wechat_user_id),
  INDEX idx_wechat_bind_links_status (status),
  CONSTRAINT fk_wechat_bind_links_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE SET NULL,
  CONSTRAINT fk_wechat_bind_links_admin FOREIGN KEY (created_by_admin_id) REFERENCES admins(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openviking_settings (
  id VARCHAR(64) PRIMARY KEY,
  base_url TEXT NULL,
  trusted_mode_enabled TINYINT(1) NOT NULL DEFAULT 1,
  account_id VARCHAR(120) NOT NULL DEFAULT 'claw-manager',
  plugin_package VARCHAR(255) NOT NULL DEFAULT 'npm:@claw-manager/openviking-openclaw-plugin@2026.6.28',
  root_api_key TEXT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS openviking_user_keys (
  account_id VARCHAR(120) NOT NULL,
  openviking_user_id VARCHAR(128) NOT NULL,
  user_key TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (account_id, openviking_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
