CREATE TABLE execution_checkpoints (
    item_id VARCHAR(80) PRIMARY KEY REFERENCES items(item_id) ON DELETE CASCADE,
    thread_id VARCHAR(80) NOT NULL REFERENCES threads(thread_id) ON DELETE CASCADE,
    turn_id VARCHAR(80) NOT NULL REFERENCES turns(turn_id) ON DELETE CASCADE,
    execution_id VARCHAR(160) NOT NULL,
    definition_hash VARCHAR(64) NOT NULL,
    step_id VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    payload_json CLOB NOT NULL,
    created_at BIGINT NOT NULL
)
--;;
CREATE INDEX execution_checkpoint_latest ON execution_checkpoints(thread_id, execution_id, created_at)
--;;
CREATE TABLE effect_receipts (
    thread_id VARCHAR(80) NOT NULL REFERENCES threads(thread_id) ON DELETE CASCADE,
    effect_key VARCHAR(64) NOT NULL,
    item_id VARCHAR(80) NOT NULL REFERENCES items(item_id) ON DELETE CASCADE,
    turn_id VARCHAR(80) NOT NULL REFERENCES turns(turn_id) ON DELETE CASCADE,
    tool_name VARCHAR(512) NOT NULL,
    state VARCHAR(24) NOT NULL,
    payload_json CLOB NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(thread_id, effect_key)
)
--;;
CREATE TABLE domain_artifacts (
    item_id VARCHAR(80) PRIMARY KEY REFERENCES items(item_id) ON DELETE CASCADE,
    thread_id VARCHAR(80) NOT NULL REFERENCES threads(thread_id) ON DELETE CASCADE,
    artifact_id VARCHAR(160) NOT NULL,
    category VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    payload_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(thread_id, artifact_id, revision)
)
