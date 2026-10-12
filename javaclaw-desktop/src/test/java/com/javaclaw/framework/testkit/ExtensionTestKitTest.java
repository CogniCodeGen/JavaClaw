package com.javaclaw.framework.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionTestKitTest {

    @Test
    void validExtensionExercisesSchemaCodecAdvisorStateJobAndLifecycleContracts() {
        AgentFrameworkExtension extension = new ContractExtension();
        ExtensionContractSamples samples = new ExtensionContractSamples(
                Map.of(new CapabilityId("test.contract"),
                        JsonNodeFactory.instance.objectNode().put("enabled", true)),
                Map.of("test.contract.observed@1",
                        JsonNodeFactory.instance.objectNode().put("message", "ok")),
                Map.of("test.contract@1", new ContractState("ready")));

        ExtensionContractReport report = ExtensionTestKit.verify(
                List.of(ExtensionArtifact.builtin(extension)), samples);

        assertTrue(report.valid(), report.issues().toString());
    }

    @Test
    void productionStagingFailuresAreReturnedAsCiFriendlyFindings() {
        AgentFrameworkExtension invalid = new ContractExtension() {
            @Override
            public void register(ExtensionRegistrar registrar) {
                super.register(registrar);
                registrar.eventType(new EventTypeDescriptor(
                        "core.stolen", 1, objectSchema()), EventCodec.jsonNode());
            }
        };

        ExtensionContractReport report = ExtensionTestKit.verify(invalid);

        assertFalse(report.valid());
        assertTrue(report.errors().stream().anyMatch(issue ->
                issue.code().equals("extension.publish")
                        && issue.message().contains("reserved")), report.issues().toString());
    }

    private static class ContractExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.contract", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1,
                Map.of("configuration", capabilitySchema()));

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            CapabilityId capability = new CapabilityId("test.contract");
            registrar.capability(new CapabilityDescriptor(
                    capability, "Contract Test", "", capabilitySchema(),
                    JsonNodeFactory.instance.objectNode()),
                    (id, configuration, context) -> configuration.deepCopy());
            registrar.eventType(new EventTypeDescriptor(
                    "test.contract.observed", 1, eventSchema()),
                    new EventCodec<ContractEvent>() {
                        @Override public JsonNode encode(ContractEvent payload) {
                            return JsonNodeFactory.instance.objectNode()
                                    .put("message", payload.message());
                        }

                        @Override public ContractEvent decode(JsonNode payload) {
                            return new ContractEvent(payload.path("message").asText());
                        }
                    });
            registrar.advisor(new AdvisorSpecFactory() {
                @Override public CapabilityId capabilityId() { return capability; }
                @Override public String advisorId() { return "test.contract.advisor"; }
                @Override public AdvisorSpec create(JsonNode configuration, CompilationContext context) {
                    return new AdvisorSpec("test.contract.advisor", "context", 100,
                            configuration);
                }
                @Override
                public org.springframework.ai.chat.client.advisor.api.Advisor createAdvisor(
                        AdvisorSpec specification, AdvisorRuntimeContext context) {
                    return new org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor(
                            specification.order());
                }
            });
            registrar.stateCodec(new StateCodec() {
                @Override public String extensionId() { return "test.contract"; }
                @Override public int schemaVersion() { return 1; }
                @Override public JsonNode encode(Object state) {
                    return JsonNodeFactory.instance.objectNode()
                            .put("value", ((ContractState) state).value());
                }
                @Override public Object decode(JsonNode json) {
                    return new ContractState(json.path("value").asText());
                }
            });
            registrar.backgroundJob(new BackgroundJob() {
                @Override public String id() { return "contract-maintenance"; }
                @Override public Duration interval() { return Duration.ofHours(1); }
                @Override public void run(CancellationToken cancellation) {
                    cancellation.throwIfCancelled();
                }
            });
        }
    }

    private static ObjectNode capabilitySchema() {
        ObjectNode schema = objectSchema();
        schema.putObject("properties").putObject("enabled").put("type", "boolean");
        schema.putArray("required").add("enabled");
        return schema;
    }

    private static ObjectNode eventSchema() {
        ObjectNode schema = objectSchema();
        schema.putObject("properties").putObject("message").put("type", "string");
        schema.putArray("required").add("message");
        return schema;
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private record ContractEvent(String message) { }
    private record ContractState(String value) { }
}
