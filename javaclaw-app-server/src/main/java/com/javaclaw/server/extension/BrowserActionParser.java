package com.javaclaw.server.extension;

import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 将模型的有界参数转成类型化动作；缺省空字段在本边界补齐，不接受选择器或脚本。 */
final class BrowserActionParser {
    private final CanonicalJson json;

    BrowserActionParser(CanonicalJson json) {
        this.json = json;
    }

    BrowserContracts.Action parse(String operation, CanonicalPayload payload) {
        if (operation.equals("browser_screenshot")) {
            return BrowserContracts.Action.simple(BrowserContracts.Operation.SCREENSHOT);
        }
        if (operation.equals("browser_tabs")) {
            return BrowserContracts.Action.simple(BrowserContracts.Operation.SNAPSHOT);
        }
        Optional<CanonicalPayload> typed = json.objectField(payload, "action");
        if (typed.isPresent()) {
            return json.decode(typed.orElseThrow(), BrowserContracts.Action.class);
        }
        var action = BrowserContracts.Operation.valueOf(
                json.textField(payload, "operation").orElseThrow());
        var target = json.objectField(payload, "target").map(this::target).orElseGet(BrowserContracts.Target::current);
        var input = json.objectField(payload, "input")
                .map(this::input)
                .orElseGet(() -> BrowserContracts.ActionInput.text(""));
        return new BrowserContracts.Action(action, target, input);
    }

    private BrowserContracts.Target target(CanonicalPayload payload) {
        return new BrowserContracts.Target(
                json.textField(payload, "pageId").orElse(""),
                json.textField(payload, "reference").orElse(""),
                json.textField(payload, "frameId").orElse(""));
    }

    private BrowserContracts.ActionInput input(CanonicalPayload payload) {
        return new BrowserContracts.ActionInput(
                json.textField(payload, "value").orElse(""),
                json.objectField(payload, "point").map(value -> json.decode(value, BrowserContracts.Point.class)),
                json.objectField(payload, "drag").map(value -> json.decode(value, BrowserContracts.Drag.class)),
                Optional.empty());
    }
}
