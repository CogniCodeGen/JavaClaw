package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionPayloadCodec;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.VersionedDocument;

/** 管理 Skill 的 Turn 冻结目录，并在精确读取前执行实时撤权检查。 */
final class SkillCatalogCoordinator {
    private final ExtensionPayloadCodec payloads;

    SkillCatalogCoordinator(ExtensionPayloadCodec payloads) {
        this.payloads = payloads;
    }

    SkillContracts.CatalogSnapshot catalog(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String publishedCollection,
            String catalogCollection) {
        List<SkillContracts.Summary> live = summaries(transaction, publishedCollection);
        if (request.turnId().isEmpty()) {
            return snapshot(TurnId.random(), live, context.clock().instant());
        }
        String key = request.turnId().orElseThrow().toString();
        return transaction
                .get(catalogCollection, key)
                .map(document -> payloads.decode(document.payload(), SkillContracts.CatalogSnapshot.class))
                .orElseGet(() -> createSnapshot(request, context, transaction, catalogCollection, key, live));
    }

    SkillContracts.PublishedSkill requireFrozenPublished(
            ExtensionRequest request,
            ExtensionTransaction transaction,
            SkillContracts.PublishedReadRequest input,
            String publishedCollection,
            String catalogCollection) {
        SkillContracts.CatalogSnapshot catalog = frozenCatalog(request, transaction, catalogCollection);
        if (!contains(catalog, input)) {
            throw new IllegalArgumentException("Skill is not present in this Turn frozen catalog");
        }
        SkillContracts.PublishedSkill current = currentPublished(transaction, publishedCollection, input.id());
        if (!matchesLiveIdentity(current, input)) {
            throw new IllegalArgumentException("Published Skill changed or was disabled after catalog freeze");
        }
        return current;
    }

    private SkillContracts.CatalogSnapshot createSnapshot(
            ExtensionRequest request,
            ExtensionExecutionContext context,
            ExtensionTransaction transaction,
            String collection,
            String key,
            List<SkillContracts.Summary> live) {
        SkillContracts.CatalogSnapshot created =
                snapshot(request.turnId().orElseThrow(), live, context.clock().instant());
        transaction.put(collection, key, 0, payloads.encode(created));
        return created;
    }

    private SkillContracts.CatalogSnapshot frozenCatalog(
            ExtensionRequest request, ExtensionTransaction transaction, String collection) {
        String key = request.turnId().orElseThrow().toString();
        return transaction
                .get(collection, key)
                .map(document -> payloads.decode(document.payload(), SkillContracts.CatalogSnapshot.class))
                .orElseThrow(() -> new IllegalArgumentException("Skill catalog must be searched before exact read"));
    }

    private SkillContracts.PublishedSkill currentPublished(
            ExtensionTransaction transaction, String collection, String id) {
        VersionedDocument document = transaction
                .get(collection, id)
                .orElseThrow(() -> new IllegalArgumentException("Published Skill does not exist"));
        SkillContracts.PublishedSkill current =
                payloads.decode(document.payload(), SkillContracts.PublishedSkill.class);
        if (current.revision() != document.revision()) {
            throw new IllegalStateException("Published Skill payload revision differs from managed store");
        }
        return current;
    }

    private List<SkillContracts.Summary> summaries(ExtensionTransaction transaction, String collection) {
        return all(transaction, collection).stream()
                .map(document -> currentPublished(transaction, collection, document.key()))
                .filter(SkillContracts.PublishedSkill::enabled)
                .map(value -> new SkillContracts.Summary(
                        value.id(), value.revision(), value.digest(), value.name(), value.description()))
                .sorted(Comparator.comparing(SkillContracts.Summary::id))
                .toList();
    }

    private SkillContracts.CatalogSnapshot snapshot(
            TurnId turnId, List<SkillContracts.Summary> skills, Instant capturedAt) {
        String digest = payloads.encode(new CatalogContent(skills)).sha256();
        return new SkillContracts.CatalogSnapshot(turnId, skills, digest, capturedAt);
    }

    private static boolean contains(SkillContracts.CatalogSnapshot catalog, SkillContracts.PublishedReadRequest input) {
        return catalog.skills().stream()
                .anyMatch(skill -> skill.id().equals(input.id())
                        && skill.revision() == input.revision()
                        && skill.digest().equals(input.digest()));
    }

    private static boolean matchesLiveIdentity(
            SkillContracts.PublishedSkill current, SkillContracts.PublishedReadRequest input) {
        return current.enabled()
                && current.revision() == input.revision()
                && current.digest().equals(input.digest());
    }

    private static List<VersionedDocument> all(ExtensionTransaction transaction, String collection) {
        List<VersionedDocument> result = new ArrayList<>();
        String cursor = "";
        while (true) {
            List<VersionedDocument> page = transaction.list(collection, cursor, 500);
            result.addAll(page);
            if (page.size() < 500) {
                return List.copyOf(result);
            }
            cursor = page.getLast().key();
        }
    }

    private record CatalogContent(List<SkillContracts.Summary> skills) {}
}
