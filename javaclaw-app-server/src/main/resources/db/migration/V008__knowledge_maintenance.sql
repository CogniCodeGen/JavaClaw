CREATE TABLE knowledge_maintenance_jobs(
    source_turn_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    state VARCHAR(40) NOT NULL,
    maintenance_thread_id VARCHAR(80),
    reason VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_maintenance_source FOREIGN KEY(source_turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE)
--;;
CREATE INDEX ix_maintenance_pending ON knowledge_maintenance_jobs(state, created_at)
