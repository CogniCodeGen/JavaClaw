package com.javaclaw.server.transport;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.automation.AutomationKind;
import com.javaclaw.agent.automation.AutomationRepository;
import com.javaclaw.agent.automation.AutomationUseCases;
import com.javaclaw.protocol.RpcMethods;

/** Loop, Workflow, SDD and Schedule protocol adapter. */
final class AutomationRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.AUTOMATION_LIST,
            RpcMethods.AUTOMATION_READ,
            RpcMethods.AUTOMATION_PUT,
            RpcMethods.AUTOMATION_DELETE,
            RpcMethods.AUTOMATION_START,
            RpcMethods.AUTOMATION_RESUME,
            RpcMethods.AUTOMATION_ITEMS,
            RpcMethods.AUTOMATION_INTERRUPT,
            RpcMethods.SCHEDULE_LIST,
            RpcMethods.SCHEDULE_READ,
            RpcMethods.SCHEDULE_PREVIEW,
            RpcMethods.SCHEDULE_PUT,
            RpcMethods.SCHEDULE_ENABLE,
            RpcMethods.SCHEDULE_DISABLE,
            RpcMethods.SCHEDULE_DELETE,
            RpcMethods.SCHEDULE_TRIGGER);

    private final AutomationUseCases automation;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    AutomationRpcHandler(AutomationUseCases automation, ObjectMapper json, ProtocolMapper wire) {
        this.automation = automation;
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        AutomationUseCases service = requireService();
        return switch (method) {
            case RpcMethods.AUTOMATION_LIST ->
                json.valueToTree(
                        service.listAutomations().stream().map(wire::automation).toList());
            case RpcMethods.AUTOMATION_READ ->
                json.valueToTree(wire.automation(
                        service.readAutomation(RequestParameters.requiredText(params, "automationId"))));
            case RpcMethods.AUTOMATION_PUT -> putAutomation(service, params);
            case RpcMethods.AUTOMATION_DELETE ->
                RpcResults.flag(
                        "deleted",
                        service.deleteAutomation(
                                RequestParameters.requiredText(params, "automationId"),
                                RequestParameters.optionalLong(params, "expectedRevision", -1),
                                RequestParameters.optionalText(params, "idempotencyKey", null)));
            case RpcMethods.AUTOMATION_START ->
                json.valueToTree(wire.turn(service.startAutomation(
                        RequestParameters.requiredText(params, "automationId"),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.AUTOMATION_INTERRUPT ->
                RpcResults.flag(
                        "interrupted",
                        service.interruptAutomation(RequestParameters.requiredText(params, "automationId")));
            case RpcMethods.AUTOMATION_RESUME ->
                json.valueToTree(wire.turn(service.resumeAutomation(
                        RequestParameters.requiredText(params, "automationId"),
                        RequestParameters.requiredText(params, "idempotencyKey"))));
            case RpcMethods.AUTOMATION_ITEMS ->
                json.valueToTree(service.executionItems(RequestParameters.requiredText(params, "automationId")).stream()
                        .map(wire::item)
                        .toList());
            case RpcMethods.SCHEDULE_LIST ->
                json.valueToTree(
                        service.listSchedules().stream().map(wire::schedule).toList());
            case RpcMethods.SCHEDULE_READ ->
                json.valueToTree(
                        wire.schedule(service.readSchedule(RequestParameters.requiredText(params, "scheduleId"))));
            case RpcMethods.SCHEDULE_PREVIEW -> previewSchedule(service, params);
            case RpcMethods.SCHEDULE_PUT -> putSchedule(service, params);
            case RpcMethods.SCHEDULE_ENABLE -> setSchedule(service, params, true);
            case RpcMethods.SCHEDULE_DISABLE -> setSchedule(service, params, false);
            case RpcMethods.SCHEDULE_DELETE ->
                RpcResults.flag(
                        "deleted",
                        service.deleteSchedule(
                                RequestParameters.requiredText(params, "scheduleId"),
                                RequestParameters.optionalLong(params, "expectedRevision", -1),
                                RequestParameters.optionalText(params, "idempotencyKey", null)));
            case RpcMethods.SCHEDULE_TRIGGER -> trigger(service, params);
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private JsonNode putAutomation(AutomationUseCases service, JsonNode params) {
        JsonNode value = nested(params, "automation");
        JsonNode definition = value.get("definition");
        if (definition == null || definition.isNull()) {
            throw new IllegalArgumentException("definition is required");
        }
        var draft = new AutomationRepository.AutomationDraft(
                RequestParameters.optionalText(value, "id", null),
                AutomationKind.valueOf(RequestParameters.requiredText(value, "kind")),
                RequestParameters.requiredText(value, "name"),
                RequestParameters.requiredText(value, "workspaceId"),
                RequestParameters.requiredText(value, "profileId"),
                RequestParameters.requiredText(value, "prompt"),
                definition.toString());
        return json.valueToTree(wire.automation(service.putAutomation(
                draft,
                RequestParameters.optionalLong(params, "expectedRevision", 0),
                RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private JsonNode previewSchedule(AutomationUseCases service, JsonNode params) {
        String cron = RequestParameters.requiredText(params, "cronExpression");
        String zone = RequestParameters.requiredText(params, "zoneId");
        int count = Math.toIntExact(RequestParameters.optionalLong(params, "count", 5));
        return json.valueToTree(
                new com.javaclaw.protocol.WireSchedulePreview(cron, zone, service.previewSchedule(cron, zone, count)));
    }

    private JsonNode putSchedule(AutomationUseCases service, JsonNode params) {
        JsonNode value = nested(params, "schedule");
        var draft = new AutomationRepository.ScheduleDraft(
                RequestParameters.optionalText(value, "id", null),
                RequestParameters.requiredText(value, "name"),
                RequestParameters.requiredText(value, "workspaceId"),
                RequestParameters.requiredText(value, "profileId"),
                RequestParameters.requiredText(value, "prompt"),
                RequestParameters.requiredText(value, "cronExpression"),
                RequestParameters.requiredText(value, "zoneId"),
                RequestParameters.optionalBoolean(value, "enabled", true));
        return json.valueToTree(wire.schedule(service.putSchedule(
                draft,
                RequestParameters.optionalLong(params, "expectedRevision", 0),
                RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private JsonNode setSchedule(AutomationUseCases service, JsonNode params, boolean enabled) {
        return json.valueToTree(wire.schedule(service.setScheduleEnabled(
                RequestParameters.requiredText(params, "scheduleId"),
                enabled,
                RequestParameters.optionalLong(params, "expectedRevision", -1),
                RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private JsonNode trigger(AutomationUseCases service, JsonNode params) {
        var result = service.triggerSchedule(
                RequestParameters.requiredText(params, "scheduleId"),
                RequestParameters.optionalText(params, "idempotencyKey", null));
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("skipped", result.skipped());
        response.put("reason", result.reason());
        if (result.turn() != null) {
            response.set("turn", json.valueToTree(wire.turn(result.turn())));
        }
        return response;
    }

    private static JsonNode nested(JsonNode params, String name) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        JsonNode value = params.has(name) ? params.get(name) : params;
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return value;
    }

    private AutomationUseCases requireService() {
        if (automation == null) {
            throw new IllegalStateException("automation capability is unavailable");
        }
        return automation;
    }
}
