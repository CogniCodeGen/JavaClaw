package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDependency;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.BackgroundJobScheduler;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.spi.ExtensionLock;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Stages, validates and atomically publishes extension generations. */
public final class ExtensionManager implements AutoCloseable {
    private static final SemanticVersion FRAMEWORK_API = SemanticVersion.parse("2.0.0");
    private static final SemanticVersion SPRING_AI = SemanticVersion.parse("2.0.0");
    private final ExtensionContext context;
    private final BackgroundJobScheduler jobs;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicReference<ExtensionRegistrySnapshot> current = new AtomicReference<>();
    private final Map<Long, Generation> generations = new ConcurrentHashMap<>();
    private final Map<ExtensionArtifact, ArtifactLifecycle> lifecycles = new IdentityHashMap<>();
    private final Map<java.io.Closeable, Integer> loaderReferences = new IdentityHashMap<>();
    private final Map<ExtensionArtifact, Map<String, JobLifecycle>> jobLifecycles =
            new IdentityHashMap<>();
    private final Map<String, String> knownCoordinateHashes = new HashMap<>();

    public ExtensionManager(ExtensionContext context) {
        this(context, BackgroundJobScheduler.disabled());
    }

    public ExtensionManager(ExtensionContext context, BackgroundJobScheduler jobs) {
        this.context = Objects.requireNonNull(context, "context");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        publish(List.of());
    }

    public ExtensionRegistrySnapshot currentSnapshot() {
        return current.get();
    }

    /** Atomically leases the currently published generation for a new compilation. */
    public synchronized ExtensionRegistrySnapshot.SnapshotLease acquireCurrent() {
        ExtensionRegistrySnapshot snapshot = current.get();
        if (snapshot == null) throw new IllegalStateException("extension manager is closed");
        return snapshot.acquire();
    }

    /** Reattaches a persisted plan only when every exact version and artifact hash is present. */
    public synchronized java.util.Optional<ExtensionRegistrySnapshot.SnapshotLease> acquireLocked(
            List<ExtensionLock> locks) {
        ExtensionRegistrySnapshot snapshot = current.get();
        if (snapshot == null || locks.stream().anyMatch(lock -> !snapshot.contains(lock))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(snapshot.acquire());
    }

    public synchronized ExtensionRegistrySnapshot publish(Collection<ExtensionArtifact> artifacts) {
        return publish(artifacts, Set.of());
    }

    public synchronized ExtensionRegistrySnapshot publish(
            Collection<ExtensionArtifact> artifacts, Set<String> disabledExtensionIds) {
        Set<String> disabled = Set.copyOf(disabledExtensionIds == null
                ? Set.of() : disabledExtensionIds);
        long generationNumber = nextGeneration.incrementAndGet();
        Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped = group(artifacts);
        validateCoordinates(grouped);
        validateRuntimeCompatibility(grouped);
        validateDependencies(grouped);
        validateEnabledDependencies(grouped, disabled);
        validateConflicts(grouped, disabled);
        validateAcyclic(grouped);

        List<ExtensionArtifact> ordered = topologicalOrder(grouped);
        Map<String, ExtensionContributions> artifactContributions = new LinkedHashMap<>();
        for (ExtensionArtifact artifact : ordered) {
            StagingExtensionRegistrar.Builder contributionBuilder =
                    new StagingExtensionRegistrar.Builder();
            artifact.extension().register(new StagingExtensionRegistrar(
                    artifact.extension().descriptor().id(), contributionBuilder));
            ExtensionContributions artifactValues = contributionBuilder.build();
            if (!artifactValues.infrastructureProviders().isEmpty()
                    && artifact.extension().descriptor().scope()
                    != com.javaclaw.framework.spi.ExtensionScope.KERNEL_PROVIDER) {
                throw new IllegalStateException("infrastructure providers require KERNEL_PROVIDER scope: "
                        + artifact.extension().descriptor().coordinate());
            }
            artifactContributions.put(ExtensionRegistrySnapshot.artifactKey(artifact),
                    artifactValues);
        }
        Map<String, ExtensionArtifact> activeArtifacts = new LinkedHashMap<>();
        grouped.forEach((id, candidates) -> activeArtifacts.put(id,
                candidates.getLast().artifact()));
        List<ExtensionContributions> activeValues = ordered.stream()
                .filter(artifact -> !disabled.contains(
                        artifact.extension().descriptor().id()))
                .filter(artifact -> activeArtifacts.get(
                        artifact.extension().descriptor().id()) == artifact)
                .map(artifact -> artifactContributions.get(
                        ExtensionRegistrySnapshot.artifactKey(artifact)))
                .toList();
        ExtensionContributions contributions =
                ExtensionRegistrySnapshot.mergeContributions(activeValues);
        validateHotUpdate(grouped, contributions);
        Generation generation = new Generation(generationNumber, List.copyOf(ordered));
        ExtensionRegistrySnapshot snapshot = new ExtensionRegistrySnapshot(
                generationNumber, grouped, contributions, artifactContributions, disabled,
                generation.references::incrementAndGet, this::release);

        try {
            for (ExtensionArtifact artifact : ordered) retainArtifact(artifact);
            for (ExtensionArtifact artifact : activeArtifacts.values()) {
                if (disabled.contains(artifact.extension().descriptor().id())) continue;
                ExtensionContributions artifactValues = artifactContributions.get(
                        ExtensionRegistrySnapshot.artifactKey(artifact));
                Set<String> jobIds = new HashSet<>();
                for (OwnedContribution<com.javaclaw.framework.spi.BackgroundJob> job
                        : artifactValues.backgroundJobs()) {
                    if (!jobIds.add(job.value().id())) {
                        throw new IllegalStateException("duplicate extension background job: "
                                + artifact.extension().descriptor().id() + ":" + job.value().id());
                    }
                    generation.jobLeases.add(retainJob(artifact, job.value()));
                }
            }
            generations.put(generationNumber, generation);
            ExtensionRegistrySnapshot previousSnapshot = current.getAndSet(snapshot);
            rememberCoordinates(grouped);
            Generation previous = previousSnapshot == null ? null
                    : generations.get(previousSnapshot.generation());
            if (previous != null) retireIfUnused(previous);
            return snapshot;
        } catch (RuntimeException failure) {
            generations.remove(generationNumber);
            closeGeneration(generation);
            throw failure;
        }
    }

    /** Installs additional exact artifacts and atomically exposes them only to new runs. */
    public synchronized ExtensionRegistrySnapshot publishAdding(
            Collection<ExtensionArtifact> additions) {
        List<ExtensionArtifact> complete = new ArrayList<>(currentArtifacts());
        Set<String> coordinates = new HashSet<>();
        complete.forEach(artifact -> coordinates.add(
                artifact.extension().descriptor().coordinate()));
        for (ExtensionArtifact addition : additions) {
            String coordinate = addition.extension().descriptor().coordinate();
            if (!coordinates.add(coordinate)) {
                throw new IllegalStateException("extension coordinate is already installed: "
                        + coordinate);
            }
            complete.add(addition);
        }
        Set<String> disabled = new HashSet<>(current.get().disabledExtensionIds());
        additions.forEach(addition -> disabled.remove(
                addition.extension().descriptor().id()));
        return publish(complete, disabled);
    }

    /** Disabling changes the published snapshot; generations leased by old runs remain alive. */
    public synchronized ExtensionRegistrySnapshot disableForNewRuns(String extensionId) {
        List<ExtensionArtifact> remaining = currentArtifacts().stream()
                .filter(artifact -> !artifact.extension().descriptor().id().equals(extensionId))
                .toList();
        if (remaining.size() == currentArtifacts().size()) {
            throw new IllegalArgumentException("extension is not installed: " + extensionId);
        }
        Set<String> disabled = new HashSet<>(current.get().disabledExtensionIds());
        disabled.add(extensionId);
        return publish(currentArtifacts(), disabled);
    }

    public synchronized ExtensionRegistrySnapshot enableForNewRuns(String extensionId) {
        boolean installed = currentArtifacts().stream()
                .anyMatch(artifact -> artifact.extension().descriptor().id().equals(extensionId));
        if (!installed) throw new IllegalArgumentException(
                "extension is not installed: " + extensionId);
        Set<String> disabled = new HashSet<>(current.get().disabledExtensionIds());
        if (!disabled.remove(extensionId)) return current.get();
        return publish(currentArtifacts(), disabled);
    }

    private List<ExtensionArtifact> currentArtifacts() {
        ExtensionRegistrySnapshot snapshot = current.get();
        if (snapshot == null) return List.of();
        Generation generation = generations.get(snapshot.generation());
        return generation == null ? List.of() : generation.artifacts;
    }

    private static void validateRuntimeCompatibility(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        for (var candidates : grouped.values()) {
            for (var candidate : candidates) {
                ExtensionDescriptor descriptor = candidate.artifact().extension().descriptor();
                if (!VersionRange.parse(descriptor.frameworkApiRange()).contains(FRAMEWORK_API)) {
                    throw new IllegalStateException("extension does not support Framework API "
                            + FRAMEWORK_API + ": " + descriptor.coordinate());
                }
                if (!VersionRange.parse(descriptor.springAiRange()).contains(SPRING_AI)) {
                    throw new IllegalStateException("extension does not support Spring AI "
                            + SPRING_AI + ": " + descriptor.coordinate());
                }
            }
        }
    }

    private void validateHotUpdate(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped,
            ExtensionContributions contributions) {
        ExtensionRegistrySnapshot active = current.get();
        if (active == null || active.descriptors().isEmpty()) return;
        Map<String, ExtensionDescriptor> previous = new HashMap<>();
        active.descriptors().forEach(descriptor -> previous.merge(descriptor.id(), descriptor,
                (left, right) -> left.version().compareTo(right.version()) >= 0 ? left : right));
        for (var entry : grouped.entrySet()) {
            ExtensionDescriptor old = previous.get(entry.getKey());
            if (old == null) continue;
            ExtensionDescriptor next = entry.getValue().stream()
                    .max(java.util.Comparator.comparing(
                            ExtensionRegistrySnapshot.RegisteredExtension::version))
                    .orElseThrow().artifact().extension().descriptor();
            if (old.version().equals(next.version())) continue;
            if (old.scope() == com.javaclaw.framework.spi.ExtensionScope.KERNEL_PROVIDER
                    || next.scope() == com.javaclaw.framework.spi.ExtensionScope.KERNEL_PROVIDER
                    || next.hotUpdateCompatibility() == HotUpdateCompatibility.RESTART_REQUIRED) {
                throw new IllegalStateException(
                        "extension update requires restart: " + next.coordinate());
            }
            if (next.stateSchemaVersion() < old.stateSchemaVersion()) {
                throw new IllegalStateException("extension state schema cannot downgrade: " + next.id());
            }
            if (next.hotUpdateCompatibility() == HotUpdateCompatibility.HOT_COMPATIBLE
                    && next.stateSchemaVersion() > old.stateSchemaVersion()
                    && !hasMigrationPath(contributions, next.id(), old.stateSchemaVersion(),
                    next.stateSchemaVersion())) {
                throw new IllegalStateException("missing state migration path for " + next.id()
                        + " " + old.stateSchemaVersion() + "->" + next.stateSchemaVersion());
            }
        }
    }

    private static boolean hasMigrationPath(
            ExtensionContributions contributions, String extensionId, int from, int target) {
        int current = from;
        Set<Integer> visited = new HashSet<>();
        while (current < target && visited.add(current)) {
            final int expected = current;
            var next = contributions.stateMigrators().stream()
                    .filter(value -> value.extensionId().equals(extensionId))
                    .map(OwnedContribution::value)
                    .filter(value -> value.extensionId().equals(extensionId)
                            && value.fromVersion() == expected
                            && value.toVersion() > expected
                            && value.toVersion() <= target)
                    .max(java.util.Comparator.comparingInt(
                            com.javaclaw.framework.spi.StateMigrator::toVersion));
            if (next.isEmpty()) return false;
            current = next.get().toVersion();
        }
        return current == target;
    }

    private static Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> group(
            Collection<ExtensionArtifact> artifacts) {
        Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped = new LinkedHashMap<>();
        for (ExtensionArtifact artifact : artifacts) {
            String id = artifact.extension().descriptor().id();
            grouped.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(new ExtensionRegistrySnapshot.RegisteredExtension(artifact));
        }
        grouped.replaceAll((ignored, candidates) -> candidates.stream()
                .sorted(java.util.Comparator.comparing(
                        ExtensionRegistrySnapshot.RegisteredExtension::version)).toList());
        return Map.copyOf(grouped);
    }

    private void validateCoordinates(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        for (var entry : grouped.entrySet()) {
            Map<Object, String> hashes = new HashMap<>();
            for (var candidate : entry.getValue()) {
                String previous = hashes.putIfAbsent(candidate.version(),
                        candidate.artifact().artifactSha256());
                String coordinate = entry.getKey() + ":" + candidate.version();
                String known = knownCoordinateHashes.get(coordinate);
                if (known != null && !known.equals(candidate.artifact().artifactSha256())) {
                    throw new IllegalStateException("extension coordinate artifact changed without "
                            + "a version bump: " + coordinate);
                }
                if (previous != null && !previous.equals(candidate.artifact().artifactSha256())) {
                    throw new IllegalStateException("same extension version has different artifacts: "
                            + entry.getKey() + ":" + candidate.version());
                }
                if (previous != null) {
                    throw new IllegalStateException("duplicate extension coordinate: "
                            + entry.getKey() + ":" + candidate.version());
                }
            }
        }
    }

    private void rememberCoordinates(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        grouped.forEach((id, candidates) -> candidates.forEach(candidate ->
                knownCoordinateHashes.put(id + ":" + candidate.version(),
                        candidate.artifact().artifactSha256())));
    }

    private static void validateDependencies(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        for (var candidates : grouped.values()) {
            for (var candidate : candidates) {
                for (ExtensionDependency dependency : candidate.artifact().extension()
                        .descriptor().dependencies()) {
                    boolean present = grouped.getOrDefault(dependency.extensionId(), List.of()).stream()
                            .anyMatch(value -> VersionRange.parse(dependency.versionRange())
                                    .contains(value.version()));
                    if (!present && !dependency.optional()) {
                        throw new IllegalStateException("missing extension dependency "
                                + dependency.extensionId() + " " + dependency.versionRange()
                                + " for " + candidate.artifact().extension().descriptor().coordinate());
                    }
                }
            }
        }
    }

    private static void validateEnabledDependencies(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped,
            Set<String> disabled) {
        for (var entry : grouped.entrySet()) {
            if (disabled.contains(entry.getKey())) continue;
            ExtensionDescriptor descriptor = entry.getValue().getLast()
                    .artifact().extension().descriptor();
            for (ExtensionDependency dependency : descriptor.dependencies()) {
                if (dependency.optional()) continue;
                boolean available = !disabled.contains(dependency.extensionId())
                        && grouped.getOrDefault(dependency.extensionId(), List.of()).stream()
                        .anyMatch(candidate -> VersionRange.parse(dependency.versionRange())
                                .contains(candidate.version()));
                if (!available) {
                    throw new IllegalStateException("enabled extension dependency is unavailable: "
                            + descriptor.coordinate() + " -> " + dependency.extensionId());
                }
            }
        }
    }

    private static void validateConflicts(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped,
            Set<String> disabled) {
        Set<String> enabled = new HashSet<>(grouped.keySet());
        enabled.removeAll(disabled);
        for (String id : enabled) {
            ExtensionDescriptor descriptor = grouped.get(id).getLast()
                    .artifact().extension().descriptor();
            Set<String> conflicts = new HashSet<>(descriptor.conflicts());
            conflicts.retainAll(enabled);
            if (!conflicts.isEmpty()) {
                throw new IllegalStateException("extension conflict: " + conflicts);
            }
        }
    }

    private static void validateAcyclic(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        grouped.forEach((id, ignored) -> graph.put(id, new HashSet<>()));
        for (var entry : grouped.entrySet()) {
            for (var candidate : entry.getValue()) {
                for (ExtensionDependency dependency : candidate.artifact().extension()
                        .descriptor().dependencies()) {
                    if (grouped.containsKey(dependency.extensionId())) {
                        graph.get(entry.getKey()).add(dependency.extensionId());
                    }
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) visit(id, graph, visiting, visited);
    }

    private static void visit(String id, Map<String, Set<String>> graph,
                              Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalStateException("cyclic extension dependency at " + id);
        for (String dependency : graph.get(id)) visit(dependency, graph, visiting, visited);
        visiting.remove(id);
        visited.add(id);
    }

    private static List<ExtensionArtifact> topologicalOrder(
            Map<String, List<ExtensionRegistrySnapshot.RegisteredExtension>> grouped) {
        List<ExtensionArtifact> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        while (emitted.size() < grouped.size()) {
            boolean progressed = false;
            for (var entry : grouped.entrySet()) {
                if (emitted.contains(entry.getKey())) continue;
                Set<String> required = new HashSet<>();
                for (var candidate : entry.getValue()) {
                    candidate.artifact().extension().descriptor().dependencies().stream()
                            .map(ExtensionDependency::extensionId).filter(grouped::containsKey)
                            .forEach(required::add);
                }
                if (emitted.containsAll(required)) {
                    entry.getValue().forEach(value -> result.add(value.artifact()));
                    emitted.add(entry.getKey());
                    progressed = true;
                }
            }
            if (!progressed) throw new IllegalStateException("unable to order extension graph");
        }
        return result;
    }

    private void release(long generationNumber) {
        Generation generation = generations.get(generationNumber);
        if (generation != null && generation.references.decrementAndGet() == 0) {
            retireIfUnused(generation);
        }
    }

    private synchronized void retireIfUnused(Generation generation) {
        ExtensionRegistrySnapshot active = current.get();
        if ((active == null || active.generation() != generation.number)
                && generation.references.get() == 0
                && generations.remove(generation.number, generation)) {
            closeGeneration(generation);
        }
    }

    private void retainArtifact(ExtensionArtifact artifact) {
        ArtifactLifecycle existing = lifecycles.get(artifact);
        if (existing != null) {
            existing.references++;
            return;
        }
        artifact.extension().start(context);
        lifecycles.put(artifact, new ArtifactLifecycle(1));
        if (artifact.classLoader() != null) {
            loaderReferences.merge(artifact.classLoader(), 1, Integer::sum);
        }
    }

    private void releaseArtifact(ExtensionArtifact artifact) {
        ArtifactLifecycle lifecycle = lifecycles.get(artifact);
        if (lifecycle == null || --lifecycle.references > 0) return;
        lifecycles.remove(artifact);
        try {
            artifact.extension().stop();
        } catch (RuntimeException ignored) {
            // Continue retiring the remaining generation.
        }
        java.io.Closeable loader = artifact.classLoader();
        if (loader == null) return;
        int references = loaderReferences.getOrDefault(loader, 0) - 1;
        if (references > 0) {
            loaderReferences.put(loader, references);
            return;
        }
        loaderReferences.remove(loader);
        try {
            loader.close();
        } catch (IOException ignored) {
            // Best effort during retirement.
        }
    }

    private JobLease retainJob(
            ExtensionArtifact artifact, com.javaclaw.framework.spi.BackgroundJob job) {
        Map<String, JobLifecycle> artifactJobs = jobLifecycles.computeIfAbsent(
                artifact, ignored -> new HashMap<>());
        JobLifecycle lifecycle = artifactJobs.get(job.id());
        if (lifecycle == null) {
            lifecycle = new JobLifecycle(jobs.schedule(
                    artifact.extension().descriptor().id(), job));
            artifactJobs.put(job.id(), lifecycle);
        }
        lifecycle.references++;
        return new JobLease(artifact, job.id());
    }

    private void releaseJob(JobLease lease) {
        Map<String, JobLifecycle> artifactJobs = jobLifecycles.get(lease.artifact());
        if (artifactJobs == null) return;
        JobLifecycle lifecycle = artifactJobs.get(lease.jobId());
        if (lifecycle == null || --lifecycle.references > 0) return;
        artifactJobs.remove(lease.jobId());
        if (artifactJobs.isEmpty()) jobLifecycles.remove(lease.artifact());
        lifecycle.registration.close();
    }

    private void closeGeneration(Generation generation) {
        for (int index = generation.jobLeases.size() - 1; index >= 0; index--) {
            try {
                releaseJob(generation.jobLeases.get(index));
            } catch (RuntimeException ignored) {
                // Continue cancelling the generation.
            }
        }
        ArrayDeque<ExtensionArtifact> reverse = new ArrayDeque<>(generation.artifacts);
        while (!reverse.isEmpty()) {
            releaseArtifact(reverse.removeLast());
        }
    }

    @Override
    public synchronized void close() {
        current.set(null);
        generations.values().forEach(this::closeGeneration);
        generations.clear();
        lifecycles.clear();
        loaderReferences.clear();
        jobLifecycles.clear();
    }

    private static final class Generation {
        private final long number;
        private final List<ExtensionArtifact> artifacts;
        private final AtomicLong references = new AtomicLong();
        private final List<JobLease> jobLeases = new ArrayList<>();

        private Generation(long number, List<ExtensionArtifact> artifacts) {
            this.number = number;
            this.artifacts = artifacts;
        }
    }

    private static final class ArtifactLifecycle {
        private int references;
        private ArtifactLifecycle(int references) { this.references = references; }
    }

    private record JobLease(ExtensionArtifact artifact, String jobId) { }

    private static final class JobLifecycle {
        private final BackgroundJobScheduler.Registration registration;
        private int references;

        private JobLifecycle(BackgroundJobScheduler.Registration registration) {
            this.registration = registration;
        }
    }
}
