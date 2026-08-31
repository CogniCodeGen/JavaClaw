ALTER TABLE worktrees ALTER COLUMN baseline_hash VARCHAR(128)
--;;
ALTER TABLE worktrees ADD COLUMN IF NOT EXISTS details CLOB NOT NULL DEFAULT ''
--;;
ALTER TABLE worktrees ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1
--;;
CREATE UNIQUE INDEX IF NOT EXISTS uq_worktree_child ON worktrees(child_thread_id)
--;;
CREATE TABLE IF NOT EXISTS collaboration_spawns(
    parent_thread_id VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(500) NOT NULL,
    child_thread_id VARCHAR(80) NOT NULL UNIQUE,
    writable BOOLEAN NOT NULL,
    profile_id VARCHAR(80) NOT NULL,
    task CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(parent_thread_id, idempotency_key),
    CONSTRAINT fk_collaboration_parent FOREIGN KEY(parent_thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE,
    CONSTRAINT fk_collaboration_child FOREIGN KEY(child_thread_id)
        REFERENCES threads(thread_id) ON DELETE CASCADE)
