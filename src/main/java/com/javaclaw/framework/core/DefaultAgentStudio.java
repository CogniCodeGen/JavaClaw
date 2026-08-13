package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.spi.AgentStudioRepository;
import com.javaclaw.framework.spi.JsonSchemaValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Schema-driven Agent Studio service; all user definition mutations pass through here. */
public final class DefaultAgentStudio implements AgentStudioClient {
    private final AgentStudioRepository repository;
    private final ExtensionManager extensions;
    private final ObjectMapper json;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public DefaultAgentStudio(
            AgentStudioRepository repository,
            ExtensionManager extensions,
            ObjectMapper json) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public List<CapabilityForm> capabilityForms() {
        return extensions.currentSnapshot().contributions().capabilities().values().stream()
                .map(registration -> new CapabilityForm(
                        registration.descriptor().id(), registration.extensionId(),
                        registration.descriptor().displayName(),
                        registration.descriptor().description(),
                        registration.descriptor().configurationSchema(),
                        registration.descriptor().uiSchema()))
                .sorted(Comparator.comparing(form -> form.id().value()))
                .toList();
    }

    @Override
    public List<StudioDraft> agentDrafts(String workspaceId) {
        return repository.listAgentDrafts(workspaceId);
    }

    @Override
    public List<StudioDraft> profileDrafts(String workspaceId) {
        return repository.listProfileDrafts(workspaceId);
    }

    @Override
    public StudioDraft agentDraft(String workspaceId, String definitionId) {
        return repository.getAgentDraft(workspaceId, definitionId);
    }

    @Override
    public StudioDraft profileDraft(String workspaceId, String profileId) {
        return repository.getProfileDraft(workspaceId, profileId);
    }

    @Override
    public StudioDraft saveAgentDraft(String workspaceId, AgentDefinitionDraft draft) {
        return repository.saveAgentDraft(workspaceId, draft, false);
    }

    @Override
    public StudioDraft saveProfileDraft(String workspaceId, RunProfileDraft draft) {
        return repository.saveProfileDraft(workspaceId, draft, false);
    }

    @Override
    public DefinitionValidationResult validateAgent(
            String workspaceId, AgentDefinitionDraft draft) {
        List<DefinitionValidationIssue> issues = new ArrayList<>();
        ExtensionRegistrySnapshot snapshot = extensions.currentSnapshot();
        draft.capabilityBindings().forEach((id, config) -> validateCapability(
                snapshot, id, config, "/capabilityBindings/" + id.value(), issues));
        draft.compatibleExtensionRanges().forEach((id, range) -> {
            try {
                if (snapshot.resolve(id, range).isEmpty()) {
                    issues.add(error("/compatibleExtensionRanges/" + id,
                            "extension.unavailable", "no installed extension matches " + range));
                }
            } catch (IllegalArgumentException invalidRange) {
                issues.add(error("/compatibleExtensionRanges/" + id,
                        "extension.range", invalidRange.getMessage()));
            }
        });
        return new DefinitionValidationResult(issues);
    }

    @Override
    public DefinitionValidationResult validateProfile(String workspaceId, RunProfileDraft draft) {
        List<DefinitionValidationIssue> issues = new ArrayList<>();
        ExtensionRegistrySnapshot snapshot = extensions.currentSnapshot();
        draft.capabilityOverrides().forEach((id, config) -> validateCapability(
                snapshot, id, config, "/capabilityOverrides/" + id.value(), issues));
        return new DefinitionValidationResult(issues);
    }

    @Override
    public AgentDefinition publishAgent(String workspaceId, String definitionId) {
        StudioDraft draft = repository.getAgentDraft(workspaceId, definitionId);
        AgentDefinitionDraft document = json.convertValue(draft.document(), AgentDefinitionDraft.class);
        requireValid(validateAgent(workspaceId, document));
        return repository.publishAgent(workspaceId, definitionId);
    }

    @Override
    public RunProfile publishProfile(String workspaceId, String profileId) {
        StudioDraft draft = repository.getProfileDraft(workspaceId, profileId);
        RunProfileDraft document = json.convertValue(draft.document(), RunProfileDraft.class);
        requireValid(validateProfile(workspaceId, document));
        return repository.publishProfile(workspaceId, profileId);
    }

    @Override
    public List<AgentDefinition> agentHistory(String workspaceId, String definitionId) {
        return repository.agentHistory(workspaceId, definitionId);
    }

    @Override
    public List<RunProfile> profileHistory(String workspaceId, String profileId) {
        return repository.profileHistory(workspaceId, profileId);
    }

    @Override
    public StudioDraft rollbackAgentToDraft(
            String workspaceId, String definitionId, long version) {
        AgentDefinition source = repository.findAgentVersion(workspaceId, definitionId, version);
        AgentDefinitionDraft draft = new AgentDefinitionDraft(
                source.id(), source.name(), source.modelPolicyRef(), source.promptSections(),
                source.capabilityBindings(), source.toolPolicy(), source.permissionPolicy(),
                source.budgetPolicy(), source.outputContract(), source.compatibleExtensionRanges());
        return repository.saveAgentDraft(workspaceId, draft, false);
    }

    @Override
    public StudioDraft rollbackProfileToDraft(
            String workspaceId, String profileId, long version) {
        RunProfile source = repository.findProfileVersion(workspaceId, profileId, version);
        return repository.saveProfileDraft(workspaceId, new RunProfileDraft(
                source.id(), source.name(), source.permissionCeiling(), source.budget(),
                source.capabilityOverrides(), source.failurePolicy()), false);
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode exportAgent(
            String workspaceId, String definitionId, long version) {
        return auditExport("agent", repository.findAgentVersion(
                workspaceId, definitionId, version));
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode exportProfile(
            String workspaceId, String profileId, long version) {
        return auditExport("profile", repository.findProfileVersion(
                workspaceId, profileId, version));
    }

    @Override
    public StudioDraft importAgentDraft(
            String workspaceId, com.fasterxml.jackson.databind.JsonNode auditExport) {
        requireExportKind(auditExport, "agent");
        AgentDefinition source = json.convertValue(
                auditExport.path("document"), AgentDefinition.class);
        AgentDefinitionDraft draft = new AgentDefinitionDraft(
                source.id(), source.name(), source.modelPolicyRef(), source.promptSections(),
                source.capabilityBindings(), source.toolPolicy(), source.permissionPolicy(),
                source.budgetPolicy(), source.outputContract(), source.compatibleExtensionRanges());
        requireValid(validateAgent(workspaceId, draft));
        return repository.saveAgentDraft(workspaceId, draft, false);
    }

    @Override
    public StudioDraft importProfileDraft(
            String workspaceId, com.fasterxml.jackson.databind.JsonNode auditExport) {
        requireExportKind(auditExport, "profile");
        RunProfile source = json.convertValue(
                auditExport.path("document"), RunProfile.class);
        RunProfileDraft draft = new RunProfileDraft(
                source.id(), source.name(), source.permissionCeiling(), source.budget(),
                source.capabilityOverrides(), source.failurePolicy());
        requireValid(validateProfile(workspaceId, draft));
        return repository.saveProfileDraft(workspaceId, draft, false);
    }

    private com.fasterxml.jackson.databind.JsonNode auditExport(String kind, Object document) {
        var result = json.createObjectNode();
        result.put("format", "javaclaw.agent-studio.audit");
        result.put("formatVersion", 1);
        result.put("kind", kind);
        result.set("document", json.valueToTree(document));
        return result;
    }

    private static void requireExportKind(
            com.fasterxml.jackson.databind.JsonNode value, String expected) {
        if (value == null
                || !value.path("format").asText().equals("javaclaw.agent-studio.audit")
                || value.path("formatVersion").asInt() != 1
                || !value.path("kind").asText().equals(expected)
                || !value.path("document").isObject()) {
            throw new IllegalArgumentException("invalid Agent Studio " + expected + " audit export");
        }
    }

    @Override
    public boolean archiveAgent(String workspaceId, String definitionId) {
        return repository.archiveAgent(workspaceId, definitionId);
    }

    private void validateCapability(
            ExtensionRegistrySnapshot snapshot,
            CapabilityId id,
            com.fasterxml.jackson.databind.JsonNode configuration,
            String path,
            List<DefinitionValidationIssue> issues) {
        var registration = snapshot.contributions().capabilities().get(id);
        if (registration == null) {
            issues.add(error(path, "capability.unavailable", "capability is not installed"));
            return;
        }
        issues.addAll(schemas.validate(
                registration.descriptor().configurationSchema(), configuration, path));
        validateSecretReferences(
                registration.descriptor().configurationSchema(),
                registration.descriptor().uiSchema(), configuration, path, issues);
    }

    private static void validateSecretReferences(
            com.fasterxml.jackson.databind.JsonNode schema,
            com.fasterxml.jackson.databind.JsonNode ui,
            com.fasterxml.jackson.databind.JsonNode value,
            String path,
            List<DefinitionValidationIssue> issues) {
        if (schema == null || value == null || value.isMissingNode()) return;
        String widget = ui == null ? "" : ui.path(
                com.javaclaw.framework.spi.AgentStudioUiSchema.WIDGET).asText("");
        String format = schema.path("format").asText("");
        if ("password".equals(widget) || "password".equals(format)) {
            issues.add(error(path, "secret.inline_forbidden",
                    "secret values cannot be stored in an Agent Definition; use secret-ref"));
            return;
        }
        if (com.javaclaw.framework.spi.AgentStudioUiSchema.SECRET_REF.equals(widget)
                || com.javaclaw.framework.spi.AgentStudioUiSchema.SECRET_REF.equals(format)) {
            String reference = value.isTextual() ? value.asText().trim() : "";
            if (!reference.matches("(?:secret|credential):[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
                issues.add(error(path, "secret.reference",
                        "secret-ref must contain a credential reference, never a secret value"));
            }
            return;
        }
        if (schema.path("type").asText().equals("object") && value.isObject()) {
            schema.path("properties").fields().forEachRemaining(entry -> {
                com.fasterxml.jackson.databind.JsonNode propertyUi = ui == null
                        ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                        : ui.path("properties").path(entry.getKey());
                if (propertyUi.isMissingNode() && ui != null) propertyUi = ui.path(entry.getKey());
                validateSecretReferences(entry.getValue(), propertyUi, value.path(entry.getKey()),
                        path + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"),
                        issues);
            });
            return;
        }
        if (schema.path("type").asText().equals("array") && value.isArray()) {
            com.fasterxml.jackson.databind.JsonNode itemUi = ui == null
                    ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                    : ui.path("items");
            for (int index = 0; index < value.size(); index++) {
                validateSecretReferences(schema.path("items"), itemUi, value.path(index),
                        path + "/" + index, issues);
            }
        }
    }

    private static DefinitionValidationIssue error(String path, String code, String message) {
        return new DefinitionValidationIssue(
                DefinitionValidationIssue.Severity.ERROR, path, code, message);
    }

    private static void requireValid(DefinitionValidationResult result) {
        if (!result.valid()) {
            throw new IllegalArgumentException("definition validation failed: " + result.issues());
        }
    }
}
