CREATE TABLE workspaces(
    workspace_id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    root_path VARCHAR(4000) NOT NULL UNIQUE,
    revision BIGINT NOT NULL,
    locked BOOLEAN NOT NULL,
    lock_reason VARCHAR(2000) NOT NULL,
    idempotency_key VARCHAR(500) UNIQUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE threads(
    thread_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    parent_thread_id VARCHAR(80),
    forked_from_turn_id VARCHAR(80),
    title VARCHAR(1000) NOT NULL,
    cwd VARCHAR(4000) NOT NULL,
    status VARCHAR(40) NOT NULL,
    base_sequence BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_thread_workspace FOREIGN KEY(workspace_id)
        REFERENCES workspaces(workspace_id),
    CONSTRAINT fk_thread_parent FOREIGN KEY(parent_thread_id)
        REFERENCES threads(thread_id) ON DELETE SET NULL)
--;;
CREATE TABLE turns(
    turn_id VARCHAR(80) PRIMARY KEY,
    thread_id VARCHAR(80) NOT NULL,
    attempt_id VARCHAR(80) NOT NULL,
    status VARCHAR(40) NOT NULL,
    profile_kind VARCHAR(40) NOT NULL DEFAULT 'CHAT',
    input_json CLOB NOT NULL,
    config_json CLOB NOT NULL,
    resolved_config_json CLOB,
    idempotency_key VARCHAR(500),
    error_text CLOB,
    started_at BIGINT NOT NULL,
    completed_at BIGINT,
    CONSTRAINT fk_turn_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT uq_turn_idempotency UNIQUE(thread_id, idempotency_key))
--;;
CREATE INDEX ix_turn_thread_started ON turns(thread_id, started_at, turn_id)
--;;
CREATE TABLE execution_attempts(
    attempt_id VARCHAR(80) PRIMARY KEY,
    turn_id VARCHAR(80) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(40) NOT NULL,
    provider VARCHAR(200) NOT NULL,
    model VARCHAR(500) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    reasoning_tokens BIGINT NOT NULL DEFAULT 0,
    started_at BIGINT NOT NULL,
    completed_at BIGINT,
    error_text CLOB,
    CONSTRAINT fk_attempt_turn FOREIGN KEY(turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE,
    CONSTRAINT uq_attempt_number UNIQUE(turn_id, attempt_number))
--;;
CREATE TABLE items(
    item_id VARCHAR(80) PRIMARY KEY,
    thread_id VARCHAR(80) NOT NULL,
    turn_id VARCHAR(80) NOT NULL,
    ordinal BIGINT NOT NULL,
    state VARCHAR(40) NOT NULL,
    kind VARCHAR(100) NOT NULL,
    payload_json CLOB,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_item_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_item_turn FOREIGN KEY(turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE,
    CONSTRAINT uq_item_ordinal UNIQUE(turn_id, ordinal))
--;;
CREATE TABLE thread_events(
    thread_id VARCHAR(80) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    turn_id VARCHAR(80),
    event_type VARCHAR(160) NOT NULL,
    schema_version INT NOT NULL,
    correlation_id VARCHAR(160),
    causation_id VARCHAR(160),
    payload_json CLOB NOT NULL,
    timestamp_ms BIGINT NOT NULL,
    PRIMARY KEY(thread_id, event_sequence),
    CONSTRAINT fk_event_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE)
--;;
CREATE TABLE event_outbox(
    thread_id VARCHAR(80) NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_id VARCHAR(80) NOT NULL,
    envelope_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    published_at BIGINT,
    PRIMARY KEY(thread_id, event_sequence),
    CONSTRAINT fk_outbox_event FOREIGN KEY(thread_id, event_sequence)
        REFERENCES thread_events(thread_id, event_sequence) ON DELETE CASCADE)
--;;
CREATE TABLE approvals(
    approval_id VARCHAR(80) PRIMARY KEY,
    thread_id VARCHAR(80) NOT NULL,
    turn_id VARCHAR(80) NOT NULL,
    state VARCHAR(40) NOT NULL,
    request_json CLOB NOT NULL,
    response_json CLOB,
    created_at BIGINT NOT NULL,
    resolved_at BIGINT,
    CONSTRAINT fk_approval_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_approval_turn FOREIGN KEY(turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE)
--;;
CREATE TABLE user_input_requests(
    request_id VARCHAR(80) PRIMARY KEY,
    thread_id VARCHAR(80) NOT NULL,
    turn_id VARCHAR(80) NOT NULL,
    state VARCHAR(40) NOT NULL,
    request_json CLOB NOT NULL,
    response_json CLOB,
    created_at BIGINT NOT NULL,
    resolved_at BIGINT,
    CONSTRAINT fk_user_input_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_input_turn FOREIGN KEY(turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE)
--;;
CREATE TABLE attachments(
    content_sha256 CHAR(64) PRIMARY KEY,
    media_type VARCHAR(300) NOT NULL,
    storage_path VARCHAR(4000) NOT NULL,
    size_bytes BIGINT NOT NULL,
    reference_count BIGINT NOT NULL,
    orphaned_at BIGINT,
    created_at BIGINT NOT NULL)
--;;
CREATE TABLE attachment_uploads(
    upload_id VARCHAR(80) PRIMARY KEY,
    expected_sha256 CHAR(64),
    media_type VARCHAR(300) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    expected_size BIGINT NOT NULL,
    received_bytes BIGINT NOT NULL,
    temporary_path VARCHAR(4000) NOT NULL,
    completed_sha256 CHAR(64),
    idempotency_key VARCHAR(500) UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL)
--;;
CREATE TABLE attachment_references(
    owner_type VARCHAR(80) NOT NULL,
    owner_id VARCHAR(160) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(owner_type, owner_id, content_sha256),
    CONSTRAINT fk_attachment_reference_blob FOREIGN KEY(content_sha256)
        REFERENCES attachments(content_sha256) ON DELETE CASCADE)
--;;
CREATE TABLE profiles(
    profile_id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    current_revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE profile_revisions(
    profile_id VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    provider VARCHAR(200) NOT NULL,
    model VARCHAR(500) NOT NULL,
    config_json CLOB NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(profile_id, revision),
    CONSTRAINT fk_profile_revision FOREIGN KEY(profile_id)
        REFERENCES profiles(profile_id) ON DELETE CASCADE)
--;;
CREATE TABLE provider_configs(
    provider VARCHAR(40) PRIMARY KEY,
    config_json CLOB NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE automations(
    automation_id VARCHAR(80) PRIMARY KEY,
    kind VARCHAR(40) NOT NULL,
    name VARCHAR(500) NOT NULL,
    workspace_id VARCHAR(80) NOT NULL,
    thread_id VARCHAR(80),
    active_turn_id VARCHAR(80),
    profile_id VARCHAR(80),
    prompt CLOB NOT NULL,
    definition_json CLOB NOT NULL,
    status VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE schedules(
    schedule_id VARCHAR(80) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    workspace_id VARCHAR(80) NOT NULL,
    thread_id VARCHAR(80),
    profile_id VARCHAR(80) NOT NULL,
    prompt CLOB NOT NULL,
    cron_expression VARCHAR(500) NOT NULL,
    zone_id VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    next_fire_at BIGINT,
    last_fire_at BIGINT,
    last_result VARCHAR(500),
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE memories(
    memory_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    kind VARCHAR(80) NOT NULL,
    content CLOB NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE knowledge_sources(
    source_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    attachment_sha256 CHAR(64),
    display_name VARCHAR(500) NOT NULL,
    media_type VARCHAR(300) NOT NULL,
    status VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE knowledge_documents(
    document_id VARCHAR(80) PRIMARY KEY,
    source_id VARCHAR(80) NOT NULL,
    content CLOB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    extractor_fingerprint CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_knowledge_document_source FOREIGN KEY(source_id)
        REFERENCES knowledge_sources(source_id) ON DELETE CASCADE)
--;;
CREATE TABLE knowledge_chunks(
    chunk_id VARCHAR(80) PRIMARY KEY,
    document_id VARCHAR(80) NOT NULL,
    ordinal INT NOT NULL,
    content CLOB NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_knowledge_chunk UNIQUE(document_id, ordinal),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY(document_id)
        REFERENCES knowledge_documents(document_id) ON DELETE CASCADE)
--;;
CREATE TABLE knowledge_embeddings(
    chunk_id VARCHAR(80) NOT NULL,
    provider VARCHAR(200) NOT NULL,
    model VARCHAR(500) NOT NULL,
    dimensions INT NOT NULL,
    vector_blob BLOB NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(chunk_id, fingerprint),
    CONSTRAINT fk_embedding_chunk FOREIGN KEY(chunk_id)
        REFERENCES knowledge_chunks(chunk_id) ON DELETE CASCADE)
--;;
CREATE TABLE skills(
    skill_id VARCHAR(160) PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    version VARCHAR(100) NOT NULL,
    manifest_json CLOB NOT NULL,
    enabled BOOLEAN NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE plugin_trust(
    key_id VARCHAR(160) PRIMARY KEY,
    public_key BINARY VARYING NOT NULL,
    label VARCHAR(500) NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL)
--;;
CREATE TABLE plugins(
    plugin_id VARCHAR(160) PRIMARY KEY,
    version VARCHAR(100) NOT NULL,
    install_path VARCHAR(4000) NOT NULL,
    manifest_json CLOB NOT NULL,
    bundle_sha256 CHAR(64) NOT NULL,
    signer_key_id VARCHAR(160),
    signature_verified BOOLEAN NOT NULL,
    source_confirmed BOOLEAN NOT NULL,
    permissions_approved BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL,
    state VARCHAR(40) NOT NULL,
    restart_count INT NOT NULL DEFAULT 0,
    last_error CLOB NOT NULL,
    revision BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE mcp_servers(
    mcp_id VARCHAR(160) PRIMARY KEY,
    plugin_id VARCHAR(160),
    name VARCHAR(500) NOT NULL,
    config_json CLOB NOT NULL,
    enabled BOOLEAN NOT NULL,
    state VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE credentials(
    credential_id VARCHAR(160) PRIMARY KEY,
    namespace VARCHAR(160) NOT NULL,
    secret_name VARCHAR(500) NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    cipher_text BINARY VARYING NOT NULL,
    nonce BINARY(12) NOT NULL,
    key_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    idempotency_key VARCHAR(500),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_credential_name UNIQUE(namespace, secret_name),
    CONSTRAINT uq_credential_idempotency UNIQUE(namespace, idempotency_key))
--;;
CREATE TABLE worktrees(
    worktree_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    parent_thread_id VARCHAR(80) NOT NULL,
    child_thread_id VARCHAR(80) NOT NULL,
    path VARCHAR(4000) NOT NULL,
    baseline_hash CHAR(64) NOT NULL,
    state VARCHAR(40) NOT NULL,
    cleanup_required BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL)
--;;
CREATE TABLE diagnostic_records(
    record_id VARCHAR(80) PRIMARY KEY,
    severity VARCHAR(40) NOT NULL,
    component VARCHAR(200) NOT NULL,
    code VARCHAR(200) NOT NULL,
    message CLOB NOT NULL,
    details_json CLOB NOT NULL,
    created_at BIGINT NOT NULL)
--;;
CREATE TABLE idempotency_records(
    method VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(500) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(method, idempotency_key))
--;;
CREATE TABLE server_configuration(
    config_key VARCHAR(128) PRIMARY KEY,
    value_json CLOB NOT NULL,
    updated_at BIGINT NOT NULL)
