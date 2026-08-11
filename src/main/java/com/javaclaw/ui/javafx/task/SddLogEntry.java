package com.javaclaw.ui.javafx.task;

record SddLogEntry(String time, String message, Kind kind) {
    enum Kind { OK, WARN, INFO, DEFAULT }
}
