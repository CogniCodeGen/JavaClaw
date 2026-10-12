package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunSnapshot;

public record StoredRun(RunSnapshot snapshot, RunRequest request) {}
