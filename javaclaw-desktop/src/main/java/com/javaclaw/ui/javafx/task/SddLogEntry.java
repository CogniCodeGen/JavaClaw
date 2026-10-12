package com.javaclaw.ui.javafx.task;

record SddLogEntry(String time, String message, Kind kind) {
    enum Kind { OK, WARN, INFO, DEFAULT }

    static Kind from(com.javaclaw.task.sdd.SddProgress.LogKind kind) {
        return kind == null ? Kind.DEFAULT : switch (kind) {
            case DEFAULT -> Kind.DEFAULT;
            case INFO -> Kind.INFO;
            case OK -> Kind.OK;
            case WARN -> Kind.WARN;
        };
    }
}
