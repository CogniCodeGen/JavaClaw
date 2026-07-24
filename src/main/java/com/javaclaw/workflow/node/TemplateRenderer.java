package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.workflow.model.GraphState;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只支持 {{state.path}} / {{path}} 占位符的安全模板渲染器。 */
public final class TemplateRenderer {
    private static final Pattern SLOT = Pattern.compile("\\{\\{\\s*(?:state\\.)?([A-Za-z_][A-Za-z0-9_.-]*)\\s*}}");

    private TemplateRenderer() {}

    public static String render(String template, GraphState state) {
        if (template == null) return "";
        Matcher matcher = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            JsonNode value = state.get(matcher.group(1));
            String replacement = value.isMissingNode() || value.isNull() ? ""
                    : value.isValueNode() ? value.asText() : value.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static JsonNode renderJson(JsonNode source, GraphState state) {
        if (source == null) return null;
        if (source.isTextual()) return TextNode.valueOf(render(source.asText(), state));
        if (source.isObject()) {
            ObjectNode copy = ((ObjectNode) source).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                e.setValue(renderJson(e.getValue(), state));
            }
            return copy;
        }
        if (source.isArray()) {
            ArrayNode copy = ((ArrayNode) source).deepCopy();
            for (int i = 0; i < copy.size(); i++) copy.set(i, renderJson(copy.get(i), state));
            return copy;
        }
        return source.deepCopy();
    }
}
