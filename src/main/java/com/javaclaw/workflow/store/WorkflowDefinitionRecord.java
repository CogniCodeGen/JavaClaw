package com.javaclaw.workflow.store;

import com.javaclaw.workflow.model.GraphDefinition;

public record WorkflowDefinitionRecord(
        String id,
        String name,
        String description,
        GraphDefinition draft,
        GraphDefinition published,
        int draftRevision,
        int publishedVersion,
        boolean archived,
        long createdAt,
        long updatedAt) {

    public boolean isPublished() { return published != null && publishedVersion > 0; }
}
