CREATE TABLE skill_versions (
    skill_id VARCHAR(160) NOT NULL,
    revision BIGINT NOT NULL,
    name VARCHAR(500) NOT NULL,
    version VARCHAR(100) NOT NULL,
    manifest_json CLOB NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(skill_id, revision)
)
--;;
INSERT INTO skill_versions SELECT skill_id, revision, name, version, manifest_json, enabled, created_at, updated_at FROM skills
--;;
CREATE TABLE learning_settings (
    workspace_id VARCHAR(80) PRIMARY KEY REFERENCES workspaces(workspace_id),
    skill_mode VARCHAR(16) NOT NULL,
    memory_automatic BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
--;;
CREATE TABLE skill_proposals (
    proposal_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL REFERENCES workspaces(workspace_id),
    target_id VARCHAR(160) NOT NULL,
    name VARCHAR(500) NOT NULL,
    version VARCHAR(100) NOT NULL,
    manifest_json CLOB NOT NULL,
    sources_json CLOB NOT NULL,
    expected_target_revision BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    reason VARCHAR(4000) NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
