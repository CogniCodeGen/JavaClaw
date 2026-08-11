package com.javaclaw.platform.fxml;

import java.util.ArrayList;
import java.util.List;

public final class FxmlDependency {
    private final List<String> closeOrder = new ArrayList<>();

    public void closed(String controller) {
        closeOrder.add(controller);
    }

    public List<String> closeOrder() {
        return List.copyOf(closeOrder);
    }
}
