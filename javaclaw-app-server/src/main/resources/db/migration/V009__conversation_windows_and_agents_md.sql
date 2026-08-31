CREATE TABLE conversation_windows(
    thread_id VARCHAR(80) NOT NULL,
    window_number BIGINT NOT NULL,
    strategy VARCHAR(20) NOT NULL,
    provider VARCHAR(200) NOT NULL,
    model VARCHAR(500) NOT NULL,
    covered_sequence BIGINT NOT NULL,
    schema_version INT NOT NULL,
    payload CLOB NOT NULL,
    retained_user_messages_json CLOB NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    reasoning_tokens BIGINT NOT NULL,
    compaction_item_id VARCHAR(80),
    created_at BIGINT NOT NULL,
    PRIMARY KEY(thread_id, window_number),
    CONSTRAINT uq_conversation_window_item UNIQUE(compaction_item_id),
    CONSTRAINT fk_conversation_window_thread FOREIGN KEY(thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_window_item FOREIGN KEY(compaction_item_id)
        REFERENCES items(item_id) ON DELETE CASCADE)
--;;
CREATE INDEX idx_conversation_windows_active
    ON conversation_windows(thread_id, window_number DESC)
--;;
DROP TABLE project_instruction_revisions
--;;
DROP TABLE project_instructions
