package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.runtime.NodeResult;
import com.javaclaw.workflow.runtime.GraphExecutionManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** START/END/CONDITION/TRANSFORM/HUMAN_INPUT/OUTPUT 节点实现。 */
public final class BasicNodeExecutors {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BasicNodeExecutors() {}

    public static void register(NodeExecutorRegistry registry) {
        registry.register(noop("start"));
        registry.register(noop("end"));
        registry.register(noop("condition"));
        registry.register(new TransformExecutor());
        registry.register(new HumanInputExecutor());
        registry.register(new OutputExecutor());
    }

    private static NodeExecutor noop(String type) {
        return new NodeExecutor() {
            @Override public String type() { return type; }
            @Override public NodeResult execute(NodeExecutionContext context) { return NodeResult.next(); }
        };
    }

    private static final class TransformExecutor implements NodeExecutor {
        @Override public String type() { return "transform"; }

        @Override
        public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
            JsonNode ops = node.config().path("operations");
            if (!ops.isArray()) return List.of("TRANSFORM 需要 operations 数组");
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < ops.size(); i++) {
                JsonNode op = ops.get(i);
                String prefix = "operations[" + i + "]";
                if (!op.isObject()) {
                    errors.add(prefix + " 必须是对象");
                    continue;
                }
                String kind = op.path("op").asText("set").toLowerCase();
                if (!List.of("set", "copy", "remove", "append").contains(kind)) {
                    errors.add(prefix + " 包含未知操作: " + kind);
                    continue;
                }
                if (!StatePathValidator.isValid(op.path("path").asText())) {
                    errors.add(prefix + ".path 不是合法状态路径");
                }
                if ("copy".equals(kind) && !StatePathValidator.isValid(op.path("from").asText())) {
                    errors.add(prefix + ".from 不是合法状态路径");
                }
                if (("set".equals(kind) || "append".equals(kind)) && !op.has("value")) {
                    errors.add(prefix + " 必须提供 value");
                }
            }
            return List.copyOf(errors);
        }

        @Override
        public NodeResult execute(NodeExecutionContext context) {
            StatePatch.Builder patch = StatePatch.builder();
            for (JsonNode op : context.node().config().path("operations")) {
                String kind = op.path("op").asText("set").toLowerCase();
                String path = op.path("path").asText();
                switch (kind) {
                    case "set" -> {
                        JsonNode value = TemplateRenderer.renderJson(op.get("value"), context.state());
                        patch.setJson(path, value);
                    }
                    case "copy" -> patch.setJson(path, context.state().get(op.path("from").asText()));
                    case "remove" -> patch.remove(path);
                    case "append" -> {
                        JsonNode value = TemplateRenderer.renderJson(op.get("value"), context.state());
                        patch.append(path, MAPPER.convertValue(value, Object.class));
                    }
                    default -> throw new IllegalArgumentException("未知 transform 操作: " + kind);
                }
            }
            return NodeResult.next(patch.build());
        }

    }

    private static final class HumanInputExecutor implements NodeExecutor {
        @Override public String type() { return "human_input"; }

        @Override
        public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
            List<String> errors = new ArrayList<>();
            StatePathValidator.validate(node.config().path("responseKey").asText("human.response"),
                    "HUMAN_INPUT responseKey", errors);
            return List.copyOf(errors);
        }

        @Override
        public NodeResult execute(NodeExecutionContext context) {
            String responseKey = context.node().config().path("responseKey").asText("human.response");
            String resumedNode = context.state().get(GraphExecutionManager.RESUME_NODE_STATE_KEY).asText();
            if (context.node().id().equals(resumedNode)) {
                return NodeResult.next(StatePatch.builder()
                        .remove(GraphExecutionManager.RESUME_NODE_STATE_KEY).build());
            }
            String prompt = TemplateRenderer.render(
                    context.node().config().path("prompt").asText("请提供继续执行所需的信息"), context.state());
            return NodeResult.interrupt(prompt, responseKey);
        }
    }

    private static final class OutputExecutor implements NodeExecutor {
        @Override public String type() { return "output"; }

        @Override
        public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
            List<String> errors = new ArrayList<>();
            StatePathValidator.validate(node.config().path("outputKey").asText("output"),
                    "OUTPUT outputKey", errors);
            return List.copyOf(errors);
        }

        @Override
        public NodeResult execute(NodeExecutionContext context) {
            String text = TemplateRenderer.render(context.node().config().path("template").asText("{{output}}"),
                    context.state());
            ConversationCallbacks callbacks = context.optional(ConversationCallbacks.class);
            if (callbacks != null && !text.isEmpty()) callbacks.onEvent(new ConversationEvent.Reply(text));
            String outputKey = context.node().config().path("outputKey").asText("output");
            return NodeResult.output(StatePatch.builder().set(outputKey, text).build(), text);
        }
    }
}
