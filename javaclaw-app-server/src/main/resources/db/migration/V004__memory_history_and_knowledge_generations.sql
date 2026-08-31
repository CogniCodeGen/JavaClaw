CREATE TABLE memory_metadata (
    memory_id VARCHAR(80) PRIMARY KEY REFERENCES memories(memory_id) ON DELETE CASCADE,
    subject VARCHAR(240) NOT NULL,
    attribute_name VARCHAR(240) NOT NULL,
    pinned BOOLEAN NOT NULL,
    sources_json CLOB NOT NULL
)
--;;
CREATE TABLE memory_versions (
    memory_id VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    workspace_id VARCHAR(80) NOT NULL,
    kind VARCHAR(80) NOT NULL,
    content CLOB NOT NULL,
    subject VARCHAR(240) NOT NULL,
    attribute_name VARCHAR(240) NOT NULL,
    pinned BOOLEAN NOT NULL,
    sources_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(memory_id, revision)
)
--;;
INSERT INTO memory_metadata SELECT memory_id, '', '', FALSE, '[]' FROM memories
--;;
INSERT INTO memory_versions SELECT memory_id, revision, workspace_id, kind, content,
    '', '', FALSE, '[]', created_at, updated_at FROM memories
--;;
CREATE TABLE memory_proposals (
    proposal_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL REFERENCES workspaces(workspace_id),
    target_id VARCHAR(80) NOT NULL,
    expected_target_revision BIGINT NOT NULL,
    draft_json CLOB NOT NULL,
    state VARCHAR(24) NOT NULL,
    reason VARCHAR(4000) NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
)
--;;
CREATE INDEX memory_proposals_workspace ON memory_proposals(workspace_id, state, created_at)
--;;
CREATE TABLE knowledge_index_generations (
    source_id VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    extractor_fingerprint VARCHAR(160) NOT NULL,
    status VARCHAR(80) NOT NULL,
    content CLOB NOT NULL,
    chunks_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(source_id, revision)
)
