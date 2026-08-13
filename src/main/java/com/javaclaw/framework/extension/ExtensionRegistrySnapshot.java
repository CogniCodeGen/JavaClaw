package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.ExtensionLock;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;
import java.util.ArrayList;
import java.util.function.LongConsumer;

/** Immutable view atomically captured by AgentCompiler for one run. */
public final class ExtensionRegistrySnapshot {
    private final long generation;
    private final Map<String, List<RegisteredExtension>> extensions;
    private final ExtensionContributions contributions;
    private final Map<String, ExtensionContributions> artifactContributions;
    private final Set<String> disabledExtensionIds;
    private final Runnable acquire;
    private final LongConsumer release;

    ExtensionRegistrySnapshot(
            long generation,
            Map<String, List<RegisteredExtension>> extensions,
            ExtensionContributions contributions,
            Map<String, ExtensionContributions> artifactContributions,
            Set<String> disabledExtensionIds,
            Runnable acquire,
            LongConsumer release) {
        this.generation = generation;
        this.extensions = Map.copyOf(extensions);
        this.contributions = Objects.requireNonNull(contributions);
        this.artifactContributions = Map.copyOf(artifactContributions);
        this.disabledExtensionIds = Set.copyOf(disabledExtensionIds);
        this.acquire = acquire;
        this.release = release;
    }

    public long generation() { return generation; }

    public ExtensionContributions contributions() { return contributions; }
    public Set<String> disabledExtensionIds() { return disabledExtensionIds; }

    /** Returns only the contributions of the exact artifacts locked into an execution plan. */
    public ExtensionContributions contributionsFor(List<ExtensionLock> locks) {
        List<ExtensionContributions> selected = new ArrayList<>();
        for (ExtensionLock lock : locks) {
            ExtensionContributions value = artifactContributions.get(artifactKey(
                    lock.extensionId(), lock.version(), lock.artifactSha256()));
            if (value == null) {
                throw new IllegalStateException("locked extension contributions are unavailable: "
                        + lock.extensionId() + ":" + lock.version());
            }
            selected.add(value);
        }
        return mergeContributions(selected);
    }

    public List<ExtensionDescriptor> descriptors() {
        return extensions.values().stream().flatMap(List::stream)
                .map(registered -> registered.artifact().extension().descriptor()).toList();
    }

    public Optional<ExtensionLock> resolve(String extensionId, String versionRange) {
        VersionRange range = VersionRange.parse(versionRange);
        if (disabledExtensionIds.contains(extensionId)) return Optional.empty();
        return extensions.getOrDefault(extensionId, List.of()).stream()
                .filter(candidate -> range.contains(candidate.version()))
                .max(Comparator.comparing(RegisteredExtension::version))
                .map(candidate -> new ExtensionLock(extensionId, candidate.version(),
                        candidate.artifact().artifactSha256(), generation));
    }

    boolean contains(ExtensionLock lock) {
        return extensions.getOrDefault(lock.extensionId(), List.of()).stream()
                .anyMatch(candidate -> candidate.version().equals(lock.version())
                        && candidate.artifact().artifactSha256().equals(lock.artifactSha256()));
    }

    /** Resolves roots and their transitive dependencies to exact artifacts. */
    public List<ExtensionLock> resolveClosure(Map<String, String> roots) {
        LinkedHashMap<String, RegisteredExtension> resolved = new LinkedHashMap<>();
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        roots.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                resolveRecursive(entry.getKey(), entry.getValue(), resolved, visiting, false));
        return resolved.entrySet().stream().map(entry -> new ExtensionLock(
                entry.getKey(), entry.getValue().version(),
                entry.getValue().artifact().artifactSha256(), generation)).toList();
    }

    private void resolveRecursive(
            String extensionId,
            String rangeExpression,
            Map<String, RegisteredExtension> resolved,
            Set<String> visiting,
            boolean optional) {
        VersionRange range = VersionRange.parse(rangeExpression);
        RegisteredExtension existing = resolved.get(extensionId);
        if (existing != null) {
            if (!range.contains(existing.version())) {
                throw new IllegalStateException("incompatible extension constraints for " + extensionId);
            }
            return;
        }
        RegisteredExtension selected = extensions.getOrDefault(extensionId, List.of()).stream()
                .filter(candidate -> !disabledExtensionIds.contains(extensionId))
                .filter(candidate -> range.contains(candidate.version()))
                .max(Comparator.comparing(RegisteredExtension::version)).orElse(null);
        if (selected == null) {
            if (optional) return;
            throw new IllegalStateException("no compatible extension " + extensionId + " " + rangeExpression);
        }
        if (!visiting.add(extensionId)) {
            throw new IllegalStateException("cyclic extension dependency at " + extensionId);
        }
        for (var dependency : selected.artifact().extension().descriptor().dependencies()) {
            resolveRecursive(dependency.extensionId(), dependency.versionRange(), resolved,
                    visiting, dependency.optional());
        }
        visiting.remove(extensionId);
        resolved.put(extensionId, selected);
    }

    public SnapshotLease acquire() {
        acquire.run();
        return new SnapshotLease(this, release);
    }

    static String artifactKey(ExtensionArtifact artifact) {
        return artifactKey(artifact.extension().descriptor().id(),
                artifact.extension().descriptor().version(), artifact.artifactSha256());
    }

    private static String artifactKey(String id, SemanticVersion version, String hash) {
        return id + "\u0000" + version + "\u0000" + hash;
    }

    static ExtensionContributions mergeContributions(
            Collection<ExtensionContributions> contributions) {
        Map<com.javaclaw.framework.api.CapabilityId,
                ExtensionContributions.CapabilityRegistration> capabilities = new LinkedHashMap<>();
        Map<String, EventTypeRegistration> eventTypes = new LinkedHashMap<>();
        List<OwnedContribution<com.javaclaw.framework.spi.DefinitionValidator>> definitionValidators =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.PromptContributor>> prompts = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ContextProvider>> contexts = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.RetrieverContribution>> retrievers = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.AdvisorSpecFactory>> advisors = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.OutputGuard>> guards = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ToolFactory>> tools = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ToolProviderFactory>> toolProviders = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ToolPolicy>> toolPolicies = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ToolResultPostProcessor>> toolResultPostProcessors =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.ModelPolicy>> modelPolicies = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.PermissionPolicy>> permissionPolicies = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.BudgetPolicy>> budgetPolicies = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.RetryPolicy>> retryPolicies = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.EvaluationPolicy>> evaluations = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.RunProfileContribution>> runProfiles =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.WorkflowNodeContribution>> workflowNodes =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.WorkflowTemplateContribution>> workflowTemplates =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.SubAgentPolicy>> subAgentPolicies =
                new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.StateCodec>> codecs = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.StateMigrator>> migrators = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.BackgroundJob>> jobs = new ArrayList<>();
        List<OwnedContribution<com.javaclaw.framework.spi.InfrastructureProvider<?>>> infrastructureProviders =
                new ArrayList<>();
        Set<String> runProfileIds = new LinkedHashSet<>();
        Set<String> workflowNodeTypes = new LinkedHashSet<>();
        Set<String> workflowTemplateIds = new LinkedHashSet<>();
        Set<String> infrastructureIds = new LinkedHashSet<>();
        for (ExtensionContributions value : contributions) {
            putUnique(capabilities, value.capabilities(), "capability");
            putUnique(eventTypes, value.eventTypes(), "event schema");
            definitionValidators.addAll(value.definitionValidators());
            prompts.addAll(value.promptContributors());
            contexts.addAll(value.contextProviders());
            retrievers.addAll(value.retrievers());
            advisors.addAll(value.advisors());
            guards.addAll(value.outputGuards());
            tools.addAll(value.tools());
            toolProviders.addAll(value.toolProviders());
            toolPolicies.addAll(value.toolPolicies());
            toolResultPostProcessors.addAll(value.toolResultPostProcessors());
            modelPolicies.addAll(value.modelPolicies());
            permissionPolicies.addAll(value.permissionPolicies());
            budgetPolicies.addAll(value.budgetPolicies());
            retryPolicies.addAll(value.retryPolicies());
            evaluations.addAll(value.evaluationPolicies());
            addUnique(runProfiles, value.runProfiles(), runProfileIds,
                    owned -> owned.value().profile().id(), "run profile");
            addUnique(workflowNodes, value.workflowNodes(), workflowNodeTypes,
                    owned -> owned.value().type(), "workflow node");
            addUnique(workflowTemplates, value.workflowTemplates(), workflowTemplateIds,
                    owned -> owned.value().id(), "workflow template");
            subAgentPolicies.addAll(value.subAgentPolicies());
            codecs.addAll(value.stateCodecs());
            migrators.addAll(value.stateMigrators());
            jobs.addAll(value.backgroundJobs());
            addUnique(infrastructureProviders, value.infrastructureProviders(), infrastructureIds,
                    owned -> owned.value().kind() + ":" + owned.value().id(),
                    "infrastructure provider");
        }
        return new ExtensionContributions(capabilities, eventTypes, definitionValidators,
                prompts, contexts, retrievers,
                advisors, guards, tools, toolProviders, toolPolicies, toolResultPostProcessors,
                modelPolicies, permissionPolicies, budgetPolicies, retryPolicies,
                evaluations, runProfiles, workflowNodes, workflowTemplates, subAgentPolicies,
                codecs, migrators, jobs, infrastructureProviders);
    }

    private static <K, V> void putUnique(
            Map<K, V> target, Map<K, V> source, String kind) {
        source.forEach((key, value) -> {
            if (target.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("duplicate " + kind + ": " + key);
            }
        });
    }

    private static <T> void addUnique(
            List<OwnedContribution<T>> target,
            List<OwnedContribution<T>> source,
            Set<String> keys,
            java.util.function.Function<OwnedContribution<T>, String> key,
            String kind) {
        for (OwnedContribution<T> value : source) {
            String contributionKey = key.apply(value);
            if (!keys.add(contributionKey)) {
                throw new IllegalStateException("duplicate " + kind + ": " + contributionKey);
            }
            target.add(value);
        }
    }

    record RegisteredExtension(ExtensionArtifact artifact) {
        SemanticVersion version() { return artifact.extension().descriptor().version(); }
    }

    public static final class SnapshotLease implements AutoCloseable {
        private final ExtensionRegistrySnapshot snapshot;
        private final LongConsumer release;
        private boolean closed;

        private SnapshotLease(ExtensionRegistrySnapshot snapshot, LongConsumer release) {
            this.snapshot = snapshot;
            this.release = release;
        }

        public ExtensionRegistrySnapshot snapshot() { return snapshot; }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release.accept(snapshot.generation);
            }
        }
    }
}
