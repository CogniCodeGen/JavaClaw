package com.javaclaw.config;

import java.util.Properties;

/** Keys, defaults and typed parsing for the persisted agent configuration schema. */
final class AgentConfigSchema {

    static final String CONFIG_NAMESPACE = "agent";

    static final String KEY_PROVIDER_TYPE = "api.provider.type";
    static final String KEY_BASE_URL = "api.base.url";
    static final String KEY_MODEL_NAME = "api.model.name";
    static final String KEY_API_KEY = "api.key";
    static final String KEY_THINKING_BUDGET = "model.thinking.budget";
    static final String KEY_NORMAL_PROVIDER_TYPE = "api.normal.provider.type";
    static final String KEY_NORMAL_BASE_URL = "api.normal.base.url";
    static final String KEY_NORMAL_MODEL_NAME = "api.normal.model.name";
    static final String KEY_NORMAL_API_KEY = "api.normal.key";
    static final String KEY_NORMAL_THINKING_ENABLED = "api.normal.thinking.enabled";
    static final String KEY_LIGHT_PROVIDER_TYPE = "api.light.provider.type";
    static final String KEY_LIGHT_BASE_URL = "api.light.base.url";
    static final String KEY_LIGHT_MODEL_NAME = "api.light.model.name";
    static final String KEY_LIGHT_API_KEY = "api.light.key";
    static final String KEY_LIGHT_THINKING_ENABLED = "api.light.thinking.enabled";
    static final String KEY_CONNECT_TIMEOUT = "timeout.connect.seconds";
    static final String KEY_READ_TIMEOUT = "timeout.read.seconds";
    static final String KEY_WRITE_TIMEOUT = "timeout.write.seconds";
    static final String KEY_MODEL_REQUEST_TIMEOUT = "timeout.model.request.seconds";
    static final String KEY_ORCHESTRATOR_MAX_ITERS = "orchestrator.max.iters";
    static final String KEY_WEB_AGENT_MAX_ITERS = "web.agent.max.iters";
    static final String KEY_EMAIL_AGENT_MAX_ITERS = "email.agent.max.iters";
    static final String KEY_SYSTEM_AGENT_MAX_ITERS = "system.agent.max.iters";
    static final String KEY_NOTIFICATION_AGENT_MAX_ITERS = "notification.agent.max.iters";
    static final String KEY_HTTP_VERSION = "http.version";
    static final String KEY_THINKING_ENABLED = "model.thinking.enabled";
    static final String KEY_MAX_REPEATED_TOOL_CALLS = "loop.max.repeated.calls";
    static final String KEY_LOOP_SIMILARITY_THRESHOLD = "loop.similarity.threshold";
    static final String KEY_EVALUATOR_PASS_THRESHOLD = "evaluator.pass.threshold";
    static final String KEY_EVALUATOR_MAX_RETRIES = "evaluator.max.retries";
    static final String KEY_MEMORY_MAX_TOKEN = "memory.max.token";
    static final String KEY_MEMORY_MSG_THRESHOLD = "memory.msg.threshold";
    static final String KEY_MEMORY_LAST_KEEP = "memory.last.keep";
    static final String KEY_MEMORY_TOKEN_RATIO = "memory.token.ratio";
    static final String KEY_RETRY_MAX_ATTEMPTS = "retry.max.attempts";
    static final String KEY_RETRY_INITIAL_BACKOFF = "retry.initial.backoff.seconds";
    static final String KEY_RETRY_MAX_BACKOFF = "retry.max.backoff.seconds";
    static final String KEY_FIRST_USE_GUIDANCE_DONE = "ui.first.use.guidance.done";
    static final String KEY_TRAY_MINIMIZE_ON_CLOSE = "ui.tray.minimize.on.close";
    static final String KEY_UI_THEME = "ui.theme";
    static final String KEY_UI_FONT_FAMILY = "ui.font.family";
    static final String KEY_UI_FONT_MONO = "ui.font.mono";
    static final String KEY_UI_FONT_DENSITY = "ui.font.density";
    static final String KEY_CONFIRMATION_TIMEOUT_DEFAULT = "confirmation.timeout.default.seconds";
    static final String KEY_CONFIRMATION_TIMEOUT_MANAGED = "confirmation.timeout.managed.seconds";
    static final String KEY_TOOL_REVIEW_MODE = "tool.review.mode";
    static final String KEY_TASK_EVENTS_RETENTION_DAYS = "task.events.retention.days";
    static final String KEY_SCHEDULE_THREAD_POOL_SIZE = "schedule.thread.pool.size";
    static final String KEY_TASK_VERIFICATION_ENABLED = "task.verification.enabled";
    static final String KEY_TOOL_ROUTING_ENABLED = "tool.routing.enabled";
    static final String KEY_TASK_AGENT_MAX_ITERS = "task.agent.max.iters";
    static final String KEY_TASK_MAX_CONCURRENT = "task.max.concurrent";
    static final String KEY_COMMAND_AGENT_MAX_ITERS = "command.agent.max.iters";
    static final String KEY_TASK_SUBTASK_EXECUTOR_TIMEOUT = "task.subtask.executor.timeout.seconds";
    static final String KEY_SDD_EXEC_TIMEOUT = "task.sdd.exec.timeout.seconds";
    static final String KEY_SDD_STRUCTURED_TIMEOUT = "task.sdd.structured.timeout.seconds";
    static final String KEY_SDD_EXEC_MAX_ITERS = "task.sdd.exec.max.iters";
    static final String KEY_TASK_RISK_AUTOAPPROVE = "task.risk.autoapprove.enabled";
    static final String KEY_GEPA_GOAL_ENABLED = "gepa.goal.auto.decompose";
    static final String KEY_GEPA_EVAL_INTERVAL = "gepa.eval.interval.tasks";
    static final String KEY_GEPA_EVAL_THRESHOLD = "gepa.eval.threshold";
    static final String KEY_GEPA_PLAN_ADAPTIVE = "gepa.plan.adaptive.enabled";
    static final String KEY_GEPA_FEEDBACK_MAX_ROUNDS = "gepa.feedback.loop.max.rounds";
    static final String KEY_SUBTASK_TOOL_ERROR_MAX = "task.subtask.tool.error.max";
    static final String KEY_JSHELL_EXEC_TIMEOUT = "jshell.exec.timeout.seconds";
    static final String KEY_SKILL_EVOLUTION_MODE = "skill.evolution.mode";
    static final String KEY_SKILL_EVOLUTION_MIN_TOOLS = "skill.evolution.min.tools";
    static final String KEY_SKILL_EVOLUTION_SUCCESS_THRESHOLD = "skill.evolution.success.threshold";
    static final String KEY_SKILL_CURATION_COOLDOWN_DAYS = "skill.curation.cooldown.days";
    static final String KEY_SKILL_CURATION_DEDUP_HOURS = "skill.curation.dedup.hours";
    static final String KEY_SKILL_USAGE_LOWSUCCESS_THRESHOLD = "skill.usage.lowsuccess.threshold";
    static final String KEY_SKILL_USAGE_LOWSUCCESS_MINSAMPLES = "skill.usage.lowsuccess.minsamples";
    static final String KEY_SKILL_NUDGE_ENABLED = "skill.nudge.enabled";
    static final String KEY_SKILL_BUNDLES_ENABLED = "skill.bundles.enabled";
    static final String KEY_RAG_ENABLED = "rag.enabled";
    static final String KEY_RAG_EMBEDDING_PROVIDER = "rag.embedding.provider";
    static final String KEY_RAG_EMBEDDING_BASE_URL = "rag.embedding.base.url";
    static final String KEY_RAG_EMBEDDING_API_KEY = "rag.embedding.api.key";
    static final String KEY_RAG_EMBEDDING_MODEL_NAME = "rag.embedding.model.name";
    static final String KEY_RAG_EMBEDDING_DIMENSIONS = "rag.embedding.dimensions";
    static final String KEY_RAG_CHUNK_SIZE = "rag.chunk.size";
    static final String KEY_RAG_CHUNK_OVERLAP = "rag.chunk.overlap";
    static final String KEY_RAG_RETRIEVE_LIMIT = "rag.retrieve.limit";
    static final String KEY_RAG_SCORE_THRESHOLD = "rag.score.threshold";
    static final String KEY_LOOP_MAX_ITERATIONS = "loop.max.iterations";
    static final String KEY_LOOP_TOKEN_BUDGET = "loop.token.budget";
    static final String KEY_LOOP_MAX_WALLCLOCK_SECONDS = "loop.max.wallclock.seconds";
    static final String KEY_LOOP_INTERVAL_DELAY_SECONDS = "loop.interval.delay.seconds";
    static final String KEY_LOOP_ITERATION_TIMEOUT_SECONDS = "loop.iteration.timeout.seconds";
    static final String KEY_LOOP_VERIFY_TIMEOUT_SECONDS = "loop.verify.timeout.seconds";
    static final String KEY_LOOP_JUDGE_ENABLED = "loop.judge.enabled";

    static final String DEFAULT_PROVIDER_TYPE = "OpenAI";
    static final String DEFAULT_BASE_URL = "http://127.0.0.1:1234";
    static final String DEFAULT_MODEL_NAME = "qwen/qwen3.5-9b";
    static final String DEFAULT_API_KEY = "not-needed";
    static final int DEFAULT_THINKING_BUDGET = 4096;
    static final int DEFAULT_CONNECT_TIMEOUT = 30;
    static final int DEFAULT_READ_TIMEOUT = 120;
    static final int DEFAULT_WRITE_TIMEOUT = 30;
    static final int DEFAULT_MODEL_REQUEST_TIMEOUT = 1200;
    static final int DEFAULT_ORCHESTRATOR_MAX_ITERS = 10;
    static final int DEFAULT_WEB_AGENT_MAX_ITERS = 8;
    static final int DEFAULT_EMAIL_AGENT_MAX_ITERS = 5;
    static final int DEFAULT_SYSTEM_AGENT_MAX_ITERS = 8;
    static final int DEFAULT_NOTIFICATION_AGENT_MAX_ITERS = 5;
    static final boolean DEFAULT_THINKING_ENABLED = true;
    static final int DEFAULT_MAX_REPEATED_TOOL_CALLS = 8;
    static final String DEFAULT_HTTP_VERSION = "HTTP_1_1";
    static final double DEFAULT_LOOP_SIMILARITY_THRESHOLD = 0.8;
    static final double DEFAULT_EVALUATOR_PASS_THRESHOLD = 3.5;
    static final int DEFAULT_EVALUATOR_MAX_RETRIES = 2;
    static final long DEFAULT_MEMORY_MAX_TOKEN = 128 * 1024;
    static final int DEFAULT_MEMORY_MSG_THRESHOLD = 100;
    static final int DEFAULT_MEMORY_LAST_KEEP = 50;
    static final double DEFAULT_MEMORY_TOKEN_RATIO = 0.75;
    static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;
    static final int DEFAULT_RETRY_INITIAL_BACKOFF = 2;
    static final int DEFAULT_RETRY_MAX_BACKOFF = 30;
    static final int DEFAULT_CONFIRMATION_TIMEOUT_DEFAULT = 60;
    static final int DEFAULT_CONFIRMATION_TIMEOUT_MANAGED = 600;
    static final ToolReviewMode DEFAULT_TOOL_REVIEW_MODE = ToolReviewMode.SMART;
    static final int DEFAULT_TASK_EVENTS_RETENTION_DAYS = 30;
    static final int DEFAULT_SCHEDULE_THREAD_POOL_SIZE = 4;
    static final int DEFAULT_TASK_AGENT_MAX_ITERS = 15;
    static final int DEFAULT_TASK_MAX_CONCURRENT = 3;
    static final int DEFAULT_COMMAND_AGENT_MAX_ITERS = 8;
    static final int DEFAULT_TASK_SUBTASK_EXECUTOR_TIMEOUT = 600;
    static final int DEFAULT_SDD_EXEC_TIMEOUT = 900;
    static final int DEFAULT_SDD_STRUCTURED_TIMEOUT = 300;
    static final int DEFAULT_SDD_EXEC_MAX_ITERS = 12;
    static final boolean DEFAULT_TASK_RISK_AUTOAPPROVE = true;
    static final boolean DEFAULT_GEPA_GOAL_ENABLED = true;
    static final int DEFAULT_GEPA_EVAL_INTERVAL = 3;
    static final double DEFAULT_GEPA_EVAL_THRESHOLD = 3.5;
    static final boolean DEFAULT_GEPA_PLAN_ADAPTIVE = true;
    static final int DEFAULT_GEPA_FEEDBACK_MAX_ROUNDS = 2;
    static final int DEFAULT_SUBTASK_TOOL_ERROR_MAX = 5;
    static final boolean DEFAULT_RAG_ENABLED = false;
    static final String DEFAULT_RAG_EMBEDDING_PROVIDER = "OpenAI";
    static final String DEFAULT_RAG_EMBEDDING_BASE_URL = "";
    static final String DEFAULT_RAG_EMBEDDING_API_KEY = "";
    static final String DEFAULT_RAG_EMBEDDING_MODEL_NAME = "text-embedding-3-small";
    static final int DEFAULT_RAG_EMBEDDING_DIMENSIONS = 1024;
    static final int DEFAULT_RAG_CHUNK_SIZE = 512;
    static final int DEFAULT_RAG_CHUNK_OVERLAP = 50;
    static final int DEFAULT_RAG_RETRIEVE_LIMIT = 5;
    static final double DEFAULT_RAG_SCORE_THRESHOLD = 0.3;
    static final int DEFAULT_LOOP_MAX_ITERATIONS = 25;
    static final long DEFAULT_LOOP_TOKEN_BUDGET = 0L;
    static final long DEFAULT_LOOP_MAX_WALLCLOCK_SECONDS = 3600L;
    static final long DEFAULT_LOOP_INTERVAL_DELAY_SECONDS = 300L;
    static final long DEFAULT_LOOP_ITERATION_TIMEOUT_SECONDS = 720L;
    static final long DEFAULT_LOOP_VERIFY_TIMEOUT_SECONDS = 900L;
    static final boolean DEFAULT_LOOP_JUDGE_ENABLED = false;

    private AgentConfigSchema() { }

    static void applyInitialDefaults(Properties target) {
        put(target, KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
        put(target, KEY_BASE_URL, DEFAULT_BASE_URL);
        put(target, KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
        put(target, KEY_API_KEY, DEFAULT_API_KEY);
        put(target, KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET);
        put(target, KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        put(target, KEY_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
        put(target, KEY_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
        put(target, KEY_ORCHESTRATOR_MAX_ITERS, DEFAULT_ORCHESTRATOR_MAX_ITERS);
        put(target, KEY_WEB_AGENT_MAX_ITERS, DEFAULT_WEB_AGENT_MAX_ITERS);
        put(target, KEY_EMAIL_AGENT_MAX_ITERS, DEFAULT_EMAIL_AGENT_MAX_ITERS);
        put(target, KEY_SYSTEM_AGENT_MAX_ITERS, DEFAULT_SYSTEM_AGENT_MAX_ITERS);
        put(target, KEY_NOTIFICATION_AGENT_MAX_ITERS, DEFAULT_NOTIFICATION_AGENT_MAX_ITERS);
        put(target, KEY_COMMAND_AGENT_MAX_ITERS, DEFAULT_COMMAND_AGENT_MAX_ITERS);
        put(target, KEY_THINKING_ENABLED, DEFAULT_THINKING_ENABLED);
        put(target, KEY_HTTP_VERSION, DEFAULT_HTTP_VERSION);
        put(target, KEY_MAX_REPEATED_TOOL_CALLS, DEFAULT_MAX_REPEATED_TOOL_CALLS);
        put(target, KEY_LOOP_SIMILARITY_THRESHOLD, DEFAULT_LOOP_SIMILARITY_THRESHOLD);
        put(target, KEY_EVALUATOR_PASS_THRESHOLD, DEFAULT_EVALUATOR_PASS_THRESHOLD);
        put(target, KEY_EVALUATOR_MAX_RETRIES, DEFAULT_EVALUATOR_MAX_RETRIES);
        put(target, KEY_MEMORY_MAX_TOKEN, DEFAULT_MEMORY_MAX_TOKEN);
        put(target, KEY_MEMORY_MSG_THRESHOLD, DEFAULT_MEMORY_MSG_THRESHOLD);
        put(target, KEY_MEMORY_LAST_KEEP, DEFAULT_MEMORY_LAST_KEEP);
        put(target, KEY_MEMORY_TOKEN_RATIO, DEFAULT_MEMORY_TOKEN_RATIO);
        put(target, KEY_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_MAX_ATTEMPTS);
        put(target, KEY_RETRY_INITIAL_BACKOFF, DEFAULT_RETRY_INITIAL_BACKOFF);
        put(target, KEY_RETRY_MAX_BACKOFF, DEFAULT_RETRY_MAX_BACKOFF);
        put(target, KEY_TOOL_REVIEW_MODE, DEFAULT_TOOL_REVIEW_MODE.id());
        put(target, KEY_SCHEDULE_THREAD_POOL_SIZE, DEFAULT_SCHEDULE_THREAD_POOL_SIZE);
        put(target, KEY_GEPA_GOAL_ENABLED, DEFAULT_GEPA_GOAL_ENABLED);
        put(target, KEY_GEPA_EVAL_INTERVAL, DEFAULT_GEPA_EVAL_INTERVAL);
        put(target, KEY_GEPA_EVAL_THRESHOLD, DEFAULT_GEPA_EVAL_THRESHOLD);
        put(target, KEY_GEPA_PLAN_ADAPTIVE, DEFAULT_GEPA_PLAN_ADAPTIVE);
        put(target, KEY_GEPA_FEEDBACK_MAX_ROUNDS, DEFAULT_GEPA_FEEDBACK_MAX_ROUNDS);
        put(target, KEY_RAG_ENABLED, DEFAULT_RAG_ENABLED);
        put(target, KEY_RAG_EMBEDDING_PROVIDER, DEFAULT_RAG_EMBEDDING_PROVIDER);
        put(target, KEY_RAG_EMBEDDING_BASE_URL, DEFAULT_RAG_EMBEDDING_BASE_URL);
        put(target, KEY_RAG_EMBEDDING_API_KEY, DEFAULT_RAG_EMBEDDING_API_KEY);
        put(target, KEY_RAG_EMBEDDING_MODEL_NAME, DEFAULT_RAG_EMBEDDING_MODEL_NAME);
        put(target, KEY_RAG_EMBEDDING_DIMENSIONS, DEFAULT_RAG_EMBEDDING_DIMENSIONS);
        put(target, KEY_RAG_CHUNK_SIZE, DEFAULT_RAG_CHUNK_SIZE);
        put(target, KEY_RAG_CHUNK_OVERLAP, DEFAULT_RAG_CHUNK_OVERLAP);
        put(target, KEY_RAG_RETRIEVE_LIMIT, DEFAULT_RAG_RETRIEVE_LIMIT);
        put(target, KEY_RAG_SCORE_THRESHOLD, DEFAULT_RAG_SCORE_THRESHOLD);
    }

    static void resetUserSettings(Properties target) {
        put(target, KEY_PROVIDER_TYPE, DEFAULT_PROVIDER_TYPE);
        put(target, KEY_BASE_URL, DEFAULT_BASE_URL);
        put(target, KEY_MODEL_NAME, DEFAULT_MODEL_NAME);
        put(target, KEY_API_KEY, DEFAULT_API_KEY);
        put(target, KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET);
        put(target, KEY_CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
        put(target, KEY_READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
        put(target, KEY_WRITE_TIMEOUT, DEFAULT_WRITE_TIMEOUT);
        put(target, KEY_ORCHESTRATOR_MAX_ITERS, DEFAULT_ORCHESTRATOR_MAX_ITERS);
        put(target, KEY_WEB_AGENT_MAX_ITERS, DEFAULT_WEB_AGENT_MAX_ITERS);
        put(target, KEY_EMAIL_AGENT_MAX_ITERS, DEFAULT_EMAIL_AGENT_MAX_ITERS);
        put(target, KEY_SYSTEM_AGENT_MAX_ITERS, DEFAULT_SYSTEM_AGENT_MAX_ITERS);
        put(target, KEY_NOTIFICATION_AGENT_MAX_ITERS, DEFAULT_NOTIFICATION_AGENT_MAX_ITERS);
        put(target, KEY_COMMAND_AGENT_MAX_ITERS, DEFAULT_COMMAND_AGENT_MAX_ITERS);
        put(target, KEY_HTTP_VERSION, DEFAULT_HTTP_VERSION);
        put(target, KEY_THINKING_ENABLED, DEFAULT_THINKING_ENABLED);
        put(target, KEY_MAX_REPEATED_TOOL_CALLS, DEFAULT_MAX_REPEATED_TOOL_CALLS);
        put(target, KEY_LOOP_SIMILARITY_THRESHOLD, DEFAULT_LOOP_SIMILARITY_THRESHOLD);
        put(target, KEY_EVALUATOR_PASS_THRESHOLD, DEFAULT_EVALUATOR_PASS_THRESHOLD);
        put(target, KEY_EVALUATOR_MAX_RETRIES, DEFAULT_EVALUATOR_MAX_RETRIES);
        put(target, KEY_MEMORY_MAX_TOKEN, DEFAULT_MEMORY_MAX_TOKEN);
        put(target, KEY_MEMORY_MSG_THRESHOLD, DEFAULT_MEMORY_MSG_THRESHOLD);
        put(target, KEY_MEMORY_LAST_KEEP, DEFAULT_MEMORY_LAST_KEEP);
        put(target, KEY_MEMORY_TOKEN_RATIO, DEFAULT_MEMORY_TOKEN_RATIO);
        put(target, KEY_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_MAX_ATTEMPTS);
        put(target, KEY_RETRY_INITIAL_BACKOFF, DEFAULT_RETRY_INITIAL_BACKOFF);
        put(target, KEY_RETRY_MAX_BACKOFF, DEFAULT_RETRY_MAX_BACKOFF);
        put(target, KEY_SCHEDULE_THREAD_POOL_SIZE, DEFAULT_SCHEDULE_THREAD_POOL_SIZE);
        put(target, KEY_CONFIRMATION_TIMEOUT_DEFAULT, DEFAULT_CONFIRMATION_TIMEOUT_DEFAULT);
        put(target, KEY_CONFIRMATION_TIMEOUT_MANAGED, DEFAULT_CONFIRMATION_TIMEOUT_MANAGED);
        put(target, KEY_TOOL_REVIEW_MODE, DEFAULT_TOOL_REVIEW_MODE.id());
    }

    static int integer(Properties source, String key, int fallback) {
        try {
            return Integer.parseInt(source.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static long longValue(Properties source, String key, long fallback) {
        try {
            return Long.parseLong(source.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    static double decimal(Properties source, String key, double fallback) {
        try {
            return Double.parseDouble(source.getProperty(key, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static void put(Properties target, String key, Object value) {
        target.setProperty(key, String.valueOf(value));
    }
}
