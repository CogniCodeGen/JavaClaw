package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

/** Skill 管理中心的受限 Draft 文本、Attachment 资源与 Published 启停命令。 */
final class SkillManagementCommands {
    private static final Set<String> OPERATIONS =
            Set.of("draft/save-content", "draft/resource/add", "draft/resource/remove", "enable/set", "enable/clear");

    private final ExtensionPayloadCodec payloads;

    SkillManagementCommands(ExtensionPayloadCodec payloads) {
        this.payloads = Objects.requireNonNull(payloads, "payloads");
    }

    boolean supports(String operation) {
        return OPERATIONS.contains(operation);
    }

    ExtensionResponse execute(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String draftsCollection,
            String publishedCollection) {
        return switch (request.operation()) {
            case "draft/save-content" -> saveContent(request, context, transaction, draftsCollection);
            case "draft/resource/add" -> addResource(request, context, transaction, draftsCollection);
            case "draft/resource/remove" -> removeResource(request, context, transaction, draftsCollection);
            case "enable/set" ->
                setEnabled(request, context, transaction, publishedCollection, publishedId(request), true);
            case "enable/clear" ->
                setEnabled(request, context, transaction, publishedCollection, publishedId(request), false);
            default -> throw new IllegalArgumentException("unknown Skill management command: " + request.operation());
        };
    }

    ExtensionResponse saveContent(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String collection) {
        SkillContracts.SaveContentRequest input =
                payloads.decode(request.payload(), SkillContracts.SaveContentRequest.class);
        Optional<SkillContracts.Draft> current =
                transaction.get(collection, input.id()).map(document -> decodeDraft(document, "Skill Draft"));
        long actual = current.map(SkillContracts.Draft::revision).orElse(0L);
        requireExpected(request, actual);
        Instant now = context.clock().instant();
        SkillContracts.Draft draft = new SkillContracts.Draft(
                input.id(),
                Math.addExact(request.expectedRevision(), 1),
                input.name(),
                input.description(),
                input.instructions(),
                current.map(SkillContracts.Draft::resources).orElse(List.of()),
                current.map(SkillContracts.Draft::createdAt).orElse(now),
                now);
        transaction.put(collection, draft.id(), actual, payloads.encode(draft));
        return response(draft);
    }

    ExtensionResponse setEnabled(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String collection,
            String id,
            boolean enabled) {
        SkillContracts.PublishedSkill current = requirePublished(transaction, collection, id);
        requireExpected(request, current.revision());
        SkillContracts.PublishedSkill updated = new SkillContracts.PublishedSkill(
                current.id(),
                Math.addExact(request.expectedRevision(), 1),
                current.draftRevision(),
                current.digest(),
                current.name(),
                current.description(),
                current.instructions(),
                current.resources(),
                enabled,
                current.publishedAt(),
                context.clock().instant());
        transaction.put(collection, updated.id(), request.expectedRevision(), payloads.encode(updated));
        return response(updated);
    }

    ExtensionResponse addResource(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String collection) {
        SkillContracts.AddResourceRequest input =
                payloads.decode(request.payload(), SkillContracts.AddResourceRequest.class);
        SkillContracts.Draft current = requireDraft(transaction, collection, input.id());
        requireExpected(request, current.revision());
        requireUniqueResourceId(current, input.resourceId());
        AttachmentMetadata metadata = context.attachments().requireOwned(input.attachment());
        requireSupportedSize(metadata.sizeBytes());
        SkillContracts.Resource resource = new SkillContracts.Resource(
                input.resourceId(), metadata.mediaType(), metadata.digest(), input.executable());
        List<SkillContracts.Resource> resources = new java.util.ArrayList<>(current.resources());
        resources.add(resource);
        SkillContracts.Draft updated =
                copyWithResources(current, resources, context.clock().instant());
        transaction.put(collection, updated.id(), request.expectedRevision(), payloads.encode(updated));
        return response(updated);
    }

    ExtensionResponse removeResource(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String collection) {
        SkillContracts.RemoveResourceRequest input =
                payloads.decode(request.payload(), SkillContracts.RemoveResourceRequest.class);
        SkillContracts.Draft current = requireDraft(transaction, collection, input.id());
        requireExpected(request, current.revision());
        List<SkillContracts.Resource> remaining = current.resources().stream()
                .filter(resource -> !resource.id().equals(input.resourceId()))
                .toList();
        if (remaining.size() == current.resources().size()) {
            throw new IllegalArgumentException("Skill Draft resource does not exist");
        }
        SkillContracts.Draft updated =
                copyWithResources(current, remaining, context.clock().instant());
        transaction.put(collection, updated.id(), request.expectedRevision(), payloads.encode(updated));
        return response(updated);
    }

    private SkillContracts.Draft decodeDraft(VersionedDocument document, String label) {
        SkillContracts.Draft draft = payloads.decode(document.payload(), SkillContracts.Draft.class);
        requireRevision(draft.revision(), document.revision(), label);
        return draft;
    }

    private SkillContracts.Draft requireDraft(ExtensionTransaction transaction, String collection, String id) {
        return transaction
                .get(collection, id)
                .map(document -> decodeDraft(document, "Skill Draft"))
                .orElseThrow(() -> new IllegalArgumentException("Skill Draft does not exist"));
    }

    private static void requireUniqueResourceId(SkillContracts.Draft draft, String resourceId) {
        if (draft.resources().stream().anyMatch(resource -> resource.id().equals(resourceId))) {
            throw new IllegalArgumentException("Skill Draft resource id is duplicated");
        }
    }

    private static void requireSupportedSize(long sizeBytes) {
        if (sizeBytes < 1 || sizeBytes > SkillContracts.MAXIMUM_RESOURCE_BYTES) {
            throw new IllegalArgumentException("Skill resource size exceeds the supported range");
        }
    }

    private static SkillContracts.Draft copyWithResources(
            SkillContracts.Draft current, List<SkillContracts.Resource> resources, Instant updatedAt) {
        return new SkillContracts.Draft(
                current.id(),
                Math.addExact(current.revision(), 1),
                current.name(),
                current.description(),
                current.instructions(),
                resources,
                current.createdAt(),
                updatedAt);
    }

    private SkillContracts.PublishedSkill requirePublished(
            ExtensionTransaction transaction, String collection, String id) {
        VersionedDocument document = transaction
                .get(collection, id)
                .orElseThrow(() -> new IllegalArgumentException("Published Skill does not exist"));
        SkillContracts.PublishedSkill published =
                payloads.decode(document.payload(), SkillContracts.PublishedSkill.class);
        requireRevision(published.revision(), document.revision(), "Published Skill");
        return published;
    }

    private ExtensionResponse response(VersionedExtensionDocument value) {
        return new ExtensionResponse(payloads.encode(value), value.revision());
    }

    private String publishedId(ExtensionRequest request) {
        return payloads.decode(request.payload(), SkillContracts.Key.class).id();
    }

    private static void requireExpected(ExtensionRequest request, long actual) {
        if (request.expectedRevision() != actual) {
            throw new IllegalArgumentException("Skill expected revision differs from current revision");
        }
    }

    private static void requireRevision(long payload, long stored, String label) {
        if (payload != stored) {
            throw new IllegalStateException(label + " payload revision differs from managed store");
        }
    }
}
