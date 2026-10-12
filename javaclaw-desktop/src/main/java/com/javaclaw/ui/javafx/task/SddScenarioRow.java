package com.javaclaw.ui.javafx.task;

import com.javaclaw.task.sdd.spec.Scenario;

record SddScenarioRow(String capability, Scenario scenario, boolean passed) {}
