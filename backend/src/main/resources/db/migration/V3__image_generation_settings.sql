CREATE TABLE IF NOT EXISTS image_generation_settings (
  id TINYINT PRIMARY KEY,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  provider_id VARCHAR(120) NOT NULL DEFAULT '',
  model_id VARCHAR(200) NOT NULL DEFAULT '',
  api_mode VARCHAR(80) NOT NULL DEFAULT '',
  base_url TEXT NULL,
  api_key TEXT NULL,
  provider_config JSON NULL,
  timeout_ms INT NOT NULL DEFAULT 180000,
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
