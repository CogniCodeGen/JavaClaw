-- 站点配置不存秘密；登录状态和安全填充引用均使用既有 SecretStore。
CREATE TABLE IF NOT EXISTS browser_sites(
    site_id VARCHAR(200) PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL REFERENCES workspaces(workspace_id),
    name VARCHAR(500) NOT NULL,
    origin VARCHAR(2048) NOT NULL,
    origins_json CLOB NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
--;;
CREATE INDEX IF NOT EXISTS browser_sites_workspace ON browser_sites(workspace_id)
--;;
CREATE TABLE IF NOT EXISTS network_grants(
    grant_id VARCHAR(200) PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL REFERENCES workspaces(workspace_id),
    purpose VARCHAR(40) NOT NULL,
    origin VARCHAR(2048) NOT NULL,
    addresses_json CLOB NOT NULL,
    expires_at BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
--;;
CREATE INDEX IF NOT EXISTS network_grants_workspace ON network_grants(workspace_id)
--;;
CREATE TABLE IF NOT EXISTS tool_preauthorizations(
    authorization_id VARCHAR(200) PRIMARY KEY,
    workspace_id VARCHAR(200) NOT NULL REFERENCES workspaces(workspace_id),
    tool_name VARCHAR(500) NOT NULL,
    source_revision BIGINT NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    constraints_json CLOB NOT NULL,
    maximum_uses INT NOT NULL,
    consumed_uses INT NOT NULL DEFAULT 0,
    expires_at BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
--;;
CREATE TABLE IF NOT EXISTS tool_authorization_receipts(
    authorization_id VARCHAR(200) NOT NULL REFERENCES tool_preauthorizations(authorization_id),
    invocation_id VARCHAR(500) NOT NULL,
    arguments_sha256 CHAR(64) NOT NULL,
    consumed_at BIGINT NOT NULL,
    PRIMARY KEY(authorization_id,invocation_id)
)
