CREATE TABLE IF NOT EXISTS skill_repositories (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  repo_url TEXT NOT NULL,
  branch_name VARCHAR(120) NOT NULL,
  auth_type VARCHAR(40) NOT NULL,
  access_token TEXT NULL,
  token_preview VARCHAR(80) NULL,
  last_commit_sha VARCHAR(80) NULL,
  last_pull_status VARCHAR(40) NOT NULL DEFAULT 'never',
  last_pull_message TEXT NULL,
  last_pulled_at VARCHAR(40) NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  INDEX idx_skill_repositories_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS skill_definitions (
  id VARCHAR(64) PRIMARY KEY,
  repository_id VARCHAR(64) NOT NULL,
  skill_name VARCHAR(120) NOT NULL,
  original_name VARCHAR(120) NOT NULL,
  relative_path VARCHAR(500) NOT NULL,
  description TEXT NULL,
  content_hash VARCHAR(128) NOT NULL,
  warnings JSON NULL,
  syncable TINYINT(1) NOT NULL DEFAULT 1,
  last_commit_sha VARCHAR(80) NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  UNIQUE KEY uk_skill_definitions_repo_path (repository_id, relative_path),
  INDEX idx_skill_definitions_name (skill_name),
  INDEX idx_skill_definitions_syncable (syncable),
  CONSTRAINT fk_skill_definitions_repository FOREIGN KEY (repository_id) REFERENCES skill_repositories(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS skill_instance_syncs (
  instance_id VARCHAR(64) NOT NULL,
  skill_name VARCHAR(120) NOT NULL,
  skill_id VARCHAR(64) NULL,
  repository_id VARCHAR(64) NULL,
  source_commit_sha VARCHAR(80) NULL,
  status VARCHAR(40) NOT NULL,
  message TEXT NULL,
  synced_at VARCHAR(40) NULL,
  updated_at VARCHAR(40) NOT NULL,
  PRIMARY KEY (instance_id, skill_name),
  INDEX idx_skill_instance_syncs_skill_id (skill_id),
  INDEX idx_skill_instance_syncs_repository_id (repository_id),
  CONSTRAINT fk_skill_instance_syncs_instance FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE,
  CONSTRAINT fk_skill_instance_syncs_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE SET NULL,
  CONSTRAINT fk_skill_instance_syncs_repository FOREIGN KEY (repository_id) REFERENCES skill_repositories(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
