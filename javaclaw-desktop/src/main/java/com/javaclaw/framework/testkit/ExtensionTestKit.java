package com.javaclaw.framework.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.*;
import com.javaclaw.framework.spi.*;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.javaclaw.framework.testkit.ExtensionContractIssue.Severity.ERROR;
import static com.javaclaw.framework.testkit.ExtensionContractIssue.Severity.WARNING;

/**
 * Black-box contract suite for trusted {@link AgentFrameworkExtension} artifacts.
 *
 * <p>It deliberately exercises the production staging registry: API/Spring AI ranges,
 * dependencies, cycles, conflicts, duplicate contributions and lifecycle behavior cannot drift
 * from what the application accepts. Extension code is started, but background job bodies and
 * tools are never executed.</p>
 */
public final class ExtensionTestKit {
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public static ExtensionContractReport verify(AgentFrameworkExtension extension) {
        return new ExtensionTestKit().verify(
                List.of(ExtensionArtifact.builtin(extension)), ExtensionContractSamples.empty());
    }

    public static ExtensionContractReport verify(
            Collection<ExtensionArtifact> artifacts,
            ExtensionContractSamples samples) {
        return new ExtensionTestKit().inspect(artifacts, samples);
    }

    private ExtensionContractReport inspect(
            Collection<ExtensionArtifact> sourceArtifacts,
            ExtensionContractSamples samples) {
        Objects.requireNonNull(sourceArtifacts, "artifacts");
        samples = samples == null ? ExtensionContractSamples.empty() : samples;
        List<ExtensionContractIssue> issues = new ArrayList<>();
        TrackingScheduler scheduler = new TrackingScheduler();
        List<TrackingCloseable> loaders = new ArrayList<>();
        List<ExtensionArtifact> artifacts = new ArrayList<>();
        for (ExtensionArtifact artifact : sourceArtifacts) {
            TrackingCloseable loader = new TrackingCloseable();
            loaders.add(loader);
            artifacts.add(new ExtensionArtifact(
                    artifact.extension(), artifact.artifactSha256(), loader));
        }

        ExtensionManager manager = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(
                        new UnsupportedOperationException(
                                "Extension TestKit does not execute model tasks"))), scheduler);
        boolean published = false;
        try {
            ExtensionRegistrySnapshot snapshot = manager.publish(artifacts);
            published = true;
            ExtensionContributions contributions = snapshot.contributions();
            CompilationContext compilation = compilationContext(
                    contributions, samples, snapshot.generation());
            Map<CapabilityId, JsonNode> configurations = validateCapabilities(
                    contributions, samples, compilation, issues);
            validateEvents(contributions, samples, issues);
            validateAdvisors(contributions, configurations, compilation, issues);
            validateStateContracts(contributions, snapshot.descriptors(), samples, issues);
            validateTools(contributions, issues);
            validateWorkflowContributions(contributions, issues);
            validatePoliciesAndProviders(contributions, issues);
            validateJobs(contributions, issues);
        } catch (Throwable failure) {
            issues.add(issue(ERROR, "extension.publish", "registry",
                    describe(failure)));
        } finally {
            manager.close();
            if (!published) loaders.forEach(TrackingCloseable::close);
        }

        scheduler.registrations.forEach(registration -> {
            if (!registration.closed.get()) {
                issues.add(issue(ERROR, "background_job.registration_leak",
                        registration.subject, "scheduler registration was not closed"));
            }
        });
        for (int i = 0; i < loaders.size(); i++) {
            if (!loaders.get(i).closed.get()) {
                issues.add(issue(ERROR, "classloader.leak", "artifact[" + i + "]",
                        "artifact class loader remained reachable after registry close"));
            }
        }
        return new ExtensionContractReport(issues);
    }

    private Map<CapabilityId, JsonNode> validateCapabilities(
            ExtensionContributions contributions,
            ExtensionContractSamples samples,
            CompilationContext compilation,
            List<ExtensionContractIssue> issues) {
        Map<CapabilityId, JsonNode> valid = new LinkedHashMap<>();
        contributions.capabilities().forEach((id, registration) -> {
            boolean explicit = samples.capabilityConfigurations().containsKey(id);
            JsonNode configuration = explicit
                    ? samples.capabilityConfigurations().get(id)
                    : sampleFor(registration.descriptor().configurationSchema());
            var validation = schemas.validate(
                    registration.descriptor().configurationSchema(), configuration,
                    "/capabilities/" + pointer(id.value()));
            if (!validation.isEmpty()) {
                issues.add(issue(explicit ? ERROR : WARNING, "capability.schema", id.value(),
                        validation.toString()));
                return;
            }
            try {
                JsonNode first = Objects.requireNonNull(registration.compiler().compile(
                        id, configuration.deepCopy(), compilation), "compiled capability");
                JsonNode second = Objects.requireNonNull(registration.compiler().compile(
                        id, configuration.deepCopy(), compilation), "compiled capability");
                if (!first.equals(second)) {
                    issues.add(issue(ERROR, "capability.compiler.nondeterministic", id.value(),
                            "the same definition compiled to different values"));
                    return;
                }
                valid.put(id, configuration.deepCopy());
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "capability.compiler", id.value(), describe(failure)));
            }
        });
        return Map.copyOf(valid);
    }

    private void validateEvents(
            ExtensionContributions contributions,
            ExtensionContractSamples samples,
            List<ExtensionContractIssue> issues) {
        contributions.eventTypes().forEach((key, registration) -> {
            boolean explicit = samples.eventPayloads().containsKey(key);
            JsonNode payload = explicit ? samples.eventPayloads().get(key)
                    : sampleFor(registration.descriptor().jsonSchema());
            try {
                JsonNode encoded = registration.normalize(payload);
                JsonNode repeated = registration.normalize(encoded);
                if (!encoded.equals(repeated)) {
                    issues.add(issue(ERROR, "event.codec.nondeterministic", key,
                            "decode/encode is not stable"));
                }
                var validation = schemas.validate(registration.descriptor().jsonSchema(),
                        encoded, "/events/" + pointer(key));
                if (!validation.isEmpty()) {
                    issues.add(issue(explicit ? ERROR : WARNING, "event.schema", key,
                            validation.toString()));
                }
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "event.codec", key, describe(failure)));
            }
        });
    }

    private void validateAdvisors(
            ExtensionContributions contributions,
            Map<CapabilityId, JsonNode> configurations,
            CompilationContext compilation,
            List<ExtensionContractIssue> issues) {
        List<AdvisorSpec> advisors = new ArrayList<>();
        for (OwnedContribution<AdvisorSpecFactory> owned : contributions.advisors()) {
            AdvisorSpecFactory factory = owned.value();
            JsonNode configuration = configurations.get(factory.capabilityId());
            String subject = owned.extensionId() + ":" + factory.capabilityId();
            if (configuration == null) {
                issues.add(issue(ERROR, "advisor.capability.missing", subject,
                        "advisor factory references an unavailable or invalid capability"));
                continue;
            }
            try {
                AdvisorSpec first = Objects.requireNonNull(
                        factory.create(configuration.deepCopy(), compilation), "advisor");
                AdvisorSpec second = Objects.requireNonNull(
                        factory.create(configuration.deepCopy(), compilation), "advisor");
                if (!first.equals(second)) {
                    issues.add(issue(ERROR, "advisor.nondeterministic", subject,
                            "advisor declaration changes for the same compilation input"));
                }
                if (first.id().equals("spring-ai.tool-calling")) {
                    issues.add(issue(ERROR, "advisor.tool_calling.reserved", subject,
                            "only framework.springai owns ToolCallingAdvisor"));
                }
                if (!first.id().equals(factory.advisorId())) {
                    issues.add(issue(ERROR, "advisor.id.mismatch", subject,
                            "advisorId() must equal the compiled AdvisorSpec ID"));
                }
                org.springframework.ai.chat.client.advisor.api.Advisor runtimeAdvisor =
                        Objects.requireNonNull(factory.createAdvisor(
                                first, advisorRuntimeContext()), "runtime advisor");
                if (runtimeAdvisor.getClass().getName().equals(
                        "org.springframework.ai.chat.client.advisor.ToolCallingAdvisor")) {
                    issues.add(issue(ERROR, "advisor.tool_calling.reserved", subject,
                            "extensions cannot create ToolCallingAdvisor"));
                }
                advisors.add(first);
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "advisor.factory", subject, describe(failure)));
            }
        }
        Set<String> ids = new HashSet<>();
        Set<String> exclusive = new HashSet<>();
        advisors.stream().sorted(Comparator.comparingInt(AdvisorSpec::order)
                        .thenComparing(AdvisorSpec::id))
                .forEach(advisor -> {
                    if (!ids.add(advisor.id())) {
                        issues.add(issue(ERROR, "advisor.id.duplicate", advisor.id(),
                                "advisor IDs must be unique in an execution plan"));
                    }
                    if (advisor.slot().startsWith("exclusive:")
                            && !exclusive.add(advisor.slot())) {
                        issues.add(issue(ERROR, "advisor.slot.conflict", advisor.slot(),
                                "more than one advisor occupies an exclusive slot"));
                    }
                });
    }

    private void validateStateContracts(
            ExtensionContributions contributions,
            List<ExtensionDescriptor> descriptors,
            ExtensionContractSamples samples,
            List<ExtensionContractIssue> issues) {
        Map<String, ExtensionDescriptor> descriptorById = new HashMap<>();
        descriptors.forEach(descriptor -> descriptorById.merge(descriptor.id(), descriptor,
                (left, right) -> left.version().compareTo(right.version()) >= 0 ? left : right));
        Set<String> codecKeys = new HashSet<>();
        for (OwnedContribution<StateCodec> owned : contributions.stateCodecs()) {
            StateCodec codec = owned.value();
            String key = codec.extensionId() + "@" + codec.schemaVersion();
            if (!owned.extensionId().equals(codec.extensionId())) {
                issues.add(issue(ERROR, "state.codec.owner", key,
                        "codec extensionId differs from its registering extension"));
            }
            if (codec.schemaVersion() < 1 || !codecKeys.add(key)) {
                issues.add(issue(ERROR, "state.codec.version", key,
                        "schema version must be positive and unique"));
                continue;
            }
            ExtensionDescriptor descriptor = descriptorById.get(codec.extensionId());
            if (descriptor == null || codec.schemaVersion() > descriptor.stateSchemaVersion()) {
                issues.add(issue(ERROR, "state.codec.descriptor", key,
                        "codec version exceeds the extension state schema version"));
            }
            Object sample = samples.stateValues().get(key);
            if (sample == null) {
                issues.add(issue(WARNING, "state.codec.sample_missing", key,
                        "provide a representative state value to exercise round-trip compatibility"));
                continue;
            }
            try {
                JsonNode first = Objects.requireNonNull(codec.encode(sample), "encoded state");
                JsonNode repeated = Objects.requireNonNull(
                        codec.encode(codec.decode(first.deepCopy())), "re-encoded state");
                if (!first.equals(repeated)) {
                    issues.add(issue(ERROR, "state.codec.round_trip", key,
                            "encode/decode/encode changed the durable representation"));
                }
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "state.codec.round_trip", key, describe(failure)));
            }
        }

        Map<String, Map<Integer, StateMigrator>> migrationGraph = new HashMap<>();
        for (OwnedContribution<StateMigrator> owned : contributions.stateMigrators()) {
            StateMigrator migrator = owned.value();
            String subject = migrator.extensionId() + ":" + migrator.fromVersion()
                    + "->" + migrator.toVersion();
            if (!owned.extensionId().equals(migrator.extensionId())) {
                issues.add(issue(ERROR, "state.migrator.owner", subject,
                        "migrator extensionId differs from its registering extension"));
            }
            if (migrator.fromVersion() < 1
                    || migrator.toVersion() != migrator.fromVersion() + 1) {
                issues.add(issue(ERROR, "state.migrator.increment", subject,
                        "migrations must advance exactly one schema version"));
                continue;
            }
            StateMigrator previous = migrationGraph
                    .computeIfAbsent(migrator.extensionId(), ignored -> new HashMap<>())
                    .putIfAbsent(migrator.fromVersion(), migrator);
            if (previous != null) {
                issues.add(issue(ERROR, "state.migrator.duplicate", subject,
                        "duplicate migration source version"));
            }
        }
        descriptorById.values().stream()
                .filter(descriptor -> descriptor.hotUpdateCompatibility()
                        == HotUpdateCompatibility.HOT_COMPATIBLE)
                .filter(descriptor -> descriptor.stateSchemaVersion() > 1)
                .forEach(descriptor -> {
                    Map<Integer, StateMigrator> graph = migrationGraph.getOrDefault(
                            descriptor.id(), Map.of());
                    for (int version = 1; version < descriptor.stateSchemaVersion(); version++) {
                        if (!graph.containsKey(version)) {
                            issues.add(issue(ERROR, "state.migration.gap",
                                    descriptor.id() + "@" + version,
                                    "missing " + version + "->" + (version + 1) + " migrator"));
                        }
                    }
                });
    }

    private void validateTools(
            ExtensionContributions contributions,
            List<ExtensionContractIssue> issues) {
        ToolContext context = toolContext();
        Set<String> names = new HashSet<>();
        for (OwnedContribution<ToolFactory> owned : contributions.tools()) {
            try (FrameworkTool tool = Objects.requireNonNull(
                    owned.value().create(context), "tool factory result")) {
                validateTool(owned.extensionId(), tool, names, issues);
            } catch (Exception failure) {
                issues.add(issue(ERROR, "tool.factory", owned.extensionId(), describe(failure)));
            }
        }
        for (OwnedContribution<ToolProviderFactory> owned : contributions.toolProviders()) {
            List<FrameworkTool> tools;
            try {
                tools = List.copyOf(Objects.requireNonNull(
                        owned.value().create(context), "tool provider result"));
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "tool.provider", owned.extensionId(), describe(failure)));
                continue;
            }
            for (FrameworkTool tool : tools) {
                try (FrameworkTool closeable = Objects.requireNonNull(tool, "provided tool")) {
                    validateTool(owned.extensionId(), closeable, names, issues);
                } catch (Exception failure) {
                    issues.add(issue(ERROR, "tool.provider", owned.extensionId(), describe(failure)));
                }
            }
        }
    }

    private void validateTool(
            String owner,
            FrameworkTool tool,
            Set<String> names,
            List<ExtensionContractIssue> issues) {
        ToolDescriptor descriptor = Objects.requireNonNull(tool.descriptor(), "tool descriptor");
        if (!names.add(descriptor.name())) {
            issues.add(issue(ERROR, "tool.name.duplicate", descriptor.name(),
                    "tool names must be unique in one execution plan"));
        }
        schemas.requireValidSchema(descriptor.inputSchema(),
                "tool " + owner + ":" + descriptor.name());
    }

    private static void validateJobs(
            ExtensionContributions contributions,
            List<ExtensionContractIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (OwnedContribution<BackgroundJob> owned : contributions.backgroundJobs()) {
            BackgroundJob job = owned.value();
            String subject = owned.extensionId() + ":" + job.id();
            if (job.id() == null || job.id().isBlank() || !ids.add(subject)) {
                issues.add(issue(ERROR, "background_job.id", subject,
                        "job ID must be non-blank and unique per extension"));
            }
            Duration interval = job.interval();
            if (interval == null || interval.isZero() || interval.isNegative()) {
                issues.add(issue(ERROR, "background_job.interval", subject,
                        "job interval must be positive"));
            }
        }
    }

    private void validateWorkflowContributions(
            ExtensionContributions contributions,
            List<ExtensionContractIssue> issues) {
        for (OwnedContribution<WorkflowNodeContribution> owned : contributions.workflowNodes()) {
            WorkflowNodeContribution node = owned.value();
            JsonNode sample = sampleFor(node.configurationSchema());
            var validation = schemas.validate(node.configurationSchema(), sample,
                    "/workflowNodes/" + pointer(node.type()));
            if (!validation.isEmpty()) {
                issues.add(issue(WARNING, "workflow_node.schema", node.type(),
                        validation.toString()));
            }
        }
        for (OwnedContribution<WorkflowTemplateContribution> owned
                : contributions.workflowTemplates()) {
            WorkflowTemplateContribution template = owned.value();
            if (!template.definition().isObject()) {
                issues.add(issue(ERROR, "workflow_template.definition", template.id(),
                        "template definition must be a JSON object"));
            }
        }
    }

    private static void validatePoliciesAndProviders(
            ExtensionContributions contributions,
            List<ExtensionContractIssue> issues) {
        Set<String> retryIds = new HashSet<>();
        for (OwnedContribution<RetryPolicy> owned : contributions.retryPolicies()) {
            RetryPolicy retry = owned.value();
            if (retry.id() == null || retry.id().isBlank() || !retryIds.add(retry.id())) {
                issues.add(issue(ERROR, "retry_policy.id", owned.extensionId(),
                        "retry policy ID must be non-blank and globally unique"));
            }
        }
        for (OwnedContribution<InfrastructureProvider<?>> owned
                : contributions.infrastructureProviders()) {
            InfrastructureProvider<?> provider = owned.value();
            Object instance;
            try {
                instance = Objects.requireNonNull(provider.instance(), "provider instance");
            } catch (RuntimeException failure) {
                issues.add(issue(ERROR, "infrastructure_provider.instance",
                        owned.extensionId() + ":" + provider.id(), describe(failure)));
                continue;
            }
            if (!provider.contract().isInstance(instance)) {
                issues.add(issue(ERROR, "infrastructure_provider.contract",
                        owned.extensionId() + ":" + provider.id(),
                        "instance does not implement " + provider.contract().getName()));
            }
        }
    }

    private static CompilationContext compilationContext(
            ExtensionContributions contributions,
            ExtensionContractSamples samples,
            long generation) {
        Map<CapabilityId, JsonNode> configurations = new LinkedHashMap<>();
        contributions.capabilities().forEach((id, registration) -> configurations.put(id,
                samples.capabilityConfigurations().getOrDefault(id,
                        sampleFor(registration.descriptor().configurationSchema()))));
        Map<String, String> ranges = new LinkedHashMap<>();
        contributions.capabilities().values().forEach(registration ->
                ranges.put(registration.extensionId(), "*"));
        AgentDefinition definition = new AgentDefinition(
                "testkit.agent", 1, "Extension Test Agent", "testkit:model",
                Map.of("system", "Extension contract test"), configurations,
                JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(), ranges, "testkit");
        RunProfile profile = new RunProfile(
                "testkit.profile", 1, "Extension Test Profile",
                PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED, Map.of(),
                JsonNodeFactory.instance.objectNode(), "testkit");
        return new CompilationContext(definition, profile, generation);
    }

    private static ToolContext toolContext() {
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("testkit.agent"))
                .profile(RunProfileRef.latest("testkit.profile"))
                .source(new InvocationSource("testkit", "extension-contract"))
                .scope(new RunScope("testkit", "testkit", "testkit"))
                .input(InputBlock.text("contract test"))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .budget(RunBudget.UNBOUNDED)
                .build();
        return new ToolContext(new RunId("testkit-run"), request.scope(),
                PermissionSet.UNRESTRICTED, () -> false,
                Instant.now().plus(Duration.ofMinutes(5)), request);
    }

    private static AdvisorRuntimeContext advisorRuntimeContext() {
        ToolContext tool = toolContext();
        ExtensionStateView state = new ExtensionStateView() {
            @Override public RunId runId() { return tool.runId(); }
            @Override public Optional<JsonNode> get(String extensionId, String key) {
                return Optional.empty();
            }
        };
        ModelTaskGateway models = request -> CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Extension TestKit does not execute advisor model tasks"));
        return new AdvisorRuntimeContext(tool.runId(), tool.request(), state, models,
                tool.cancellation());
    }

    /** Best-effort representative value; complex schemas should supply an explicit sample. */
    private static JsonNode sampleFor(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isBoolean()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (schema.has("default")) return schema.get("default").deepCopy();
        String type = schema.path("type").isArray()
                ? schema.path("type").path(0).asText("object")
                : schema.path("type").asText("object");
        return switch (type) {
            case "object" -> objectSample(schema);
            case "array" -> arraySample(schema);
            case "string" -> JsonNodeFactory.instance.textNode(
                    schema.path("enum").isArray() && !schema.path("enum").isEmpty()
                            ? schema.path("enum").path(0).asText() : "test");
            case "integer" -> JsonNodeFactory.instance.numberNode(
                    schema.path("minimum").asLong(0));
            case "number" -> JsonNodeFactory.instance.numberNode(
                    schema.path("minimum").decimalValue());
            case "boolean" -> JsonNodeFactory.instance.booleanNode(false);
            case "null" -> JsonNodeFactory.instance.nullNode();
            default -> JsonNodeFactory.instance.objectNode();
        };
    }

    private static ObjectNode objectSample(JsonNode schema) {
        ObjectNode sample = JsonNodeFactory.instance.objectNode();
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(value -> required.add(value.asText()));
        schema.path("properties").properties().forEach(entry -> {
            if (entry.getValue().has("default") || required.contains(entry.getKey())) {
                sample.set(entry.getKey(), sampleFor(entry.getValue()));
            }
        });
        return sample;
    }

    private static ArrayNode arraySample(JsonNode schema) {
        ArrayNode sample = JsonNodeFactory.instance.arrayNode();
        int minimum = schema.path("minItems").asInt(0);
        for (int i = 0; i < minimum; i++) sample.add(sampleFor(schema.path("items")));
        return sample;
    }

    private static ExtensionContractIssue issue(
            ExtensionContractIssue.Severity severity,
            String code,
            String subject,
            String message) {
        return new ExtensionContractIssue(severity, code, subject, message);
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String describe(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static final class TrackingScheduler implements BackgroundJobScheduler {
        private final List<TrackingRegistration> registrations = new ArrayList<>();

        @Override
        public Registration schedule(String extensionId, BackgroundJob job) {
            TrackingRegistration registration = new TrackingRegistration(
                    extensionId + ":" + job.id());
            registrations.add(registration);
            return registration;
        }
    }

    private static final class TrackingRegistration implements BackgroundJobScheduler.Registration {
        private final String subject;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingRegistration(String subject) { this.subject = subject; }
        @Override public void close() { closed.set(true); }
    }

    private static final class TrackingCloseable implements Closeable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() { closed.set(true); }
    }
}
