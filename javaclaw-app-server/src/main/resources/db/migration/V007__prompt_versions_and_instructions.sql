CREATE TABLE project_instructions(
    instruction_id VARCHAR(80) PRIMARY KEY,
    workspace_id VARCHAR(80) NOT NULL,
    relative_directory VARCHAR(2000) NOT NULL,
    current_revision BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_instruction_workspace FOREIGN KEY(workspace_id)
        REFERENCES workspaces(workspace_id) ON DELETE CASCADE)
--;;
CREATE TABLE project_instruction_revisions(
    instruction_id VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL,
    content CLOB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    source_attachment_sha256 CHAR(64),
    confirmed_at BIGINT NOT NULL,
    PRIMARY KEY(instruction_id, revision),
    CONSTRAINT fk_instruction_revision FOREIGN KEY(instruction_id)
        REFERENCES project_instructions(instruction_id) ON DELETE CASCADE)
--;;
CREATE TABLE prompt_template_archive(
    template_id VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content CLOB NOT NULL,
    source_commit VARCHAR(40) NOT NULL,
    PRIMARY KEY(template_id, version))
--;;
CREATE TABLE turn_prompt_snapshots(
    turn_id VARCHAR(80) NOT NULL,
    invocation_ordinal INT NOT NULL,
    purpose VARCHAR(80) NOT NULL,
    compiled_sha256 CHAR(64) NOT NULL,
    snapshot_json CLOB NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(turn_id, invocation_ordinal),
    CONSTRAINT fk_prompt_turn FOREIGN KEY(turn_id)
        REFERENCES turns(turn_id) ON DELETE CASCADE)
