package com.javaclaw.agent.model;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用量观测传输层 —— 包装共享 HttpTransport，从提供商原始响应中截获 <b>缓存命中 token</b>。
 *
 * <p>动机：OpenAI 兼容端（含 DashScope qwen 隐式上下文缓存）在 usage 的
 * {@code prompt_tokens_details.cached_tokens} 字段返回缓存命中量——命中部分按折扣计费，
 * 是评估 ReAct 类代理真实成本的关键数据。但 AgentScope 1.0.11 的 {@code ChatUsage}
 * 只携带 input/output（{@code OpenAIUsage.PromptTokensDetails} 解析后无人消费），
 * 上层拿不到。本类在传输层补上这块账：对每个携带 {@code "prompt_tokens"} 的响应片段
 * 用正则截获 prompt/cached 两个数，回调到 sink（由 AgentRuntime 接到
 * {@code TokenTracker.recordCachedObservation}）。</p>
 *
 * <p>性能：流式 chunk 先做廉价的 {@code contains} 预判（usage 通常只在最终 chunk 出现一次），
 * 命中才走正则；解析失败静默跳过，绝不影响正常请求链路。sink 未注入时本类是纯透传。</p>
 */
public final class UsageMeteredTransport implements HttpTransport {

    private static final Logger log = LoggerFactory.getLogger(UsageMeteredTransport.class);

    private static final Pattern PROMPT_TOKENS = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CACHED_TOKENS = Pattern.compile("\"cached_tokens\"\\s*:\\s*(\\d+)");

    /** 用量回调：(promptTokens, cachedTokens)。volatile 静态注入，未注入时纯透传。 */
    private static volatile UsageSink sink;

    @FunctionalInterface
    public interface UsageSink {
        void accept(long promptTokens, long cachedTokens);
    }

    public static void setSink(UsageSink s) {
        sink = s;
    }

    private final HttpTransport delegate;

    public UsageMeteredTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws HttpTransportException {
        HttpResponse resp = delegate.execute(request);
        if (resp != null) scan(resp.getBody());
        return resp;
    }

    @Override
    public Flux<String> stream(HttpRequest request) {
        return delegate.stream(request).doOnNext(this::scan);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** 从响应片段中截获 usage；任何异常静默吞掉，不影响请求链路。 */
    private void scan(String chunk) {
        UsageSink s = sink;
        if (s == null || chunk == null || !chunk.contains("\"prompt_tokens\"")) return;
        try {
            Matcher pm = PROMPT_TOKENS.matcher(chunk);
            if (!pm.find()) return;
            long prompt = Long.parseLong(pm.group(1));
            if (prompt <= 0) return;
            Matcher cm = CACHED_TOKENS.matcher(chunk);
            long cached = cm.find() ? Long.parseLong(cm.group(1)) : 0;
            s.accept(prompt, cached);
        } catch (Exception e) {
            log.debug("usage 截获失败（忽略）: {}", e.getMessage());
        }
    }
}
