package com.javaclaw.agent.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonSchemaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能体阻塞调用的单一实现：latch 订阅 + 超时 dispose + 中断处理 + 结构化输出提取。
 *
 * <p>此前 SDD 阶段智能体、SDD critic、循环验收员各持一份近逐字拷贝——超时/中断的
 * dispose 细节修一处漏两处，只在某条编排路径上悬挂或漏订阅。所有「同步等一个
 * 结构化/文本智能体调用」的场景一律走这里。</p>
 */
public final class StructuredCalls {

    private static final Logger log = LoggerFactory.getLogger(StructuredCalls.class);

    private StructuredCalls() {}

    /**
     * 阻塞调用智能体直到完成 / 出错 / 超时。
     *
     * @param agent          目标智能体
     * @param userPrompt     用户消息正文
     * @param schema         结构化输出 POJO 类；null 表示普通文本调用
     * @param timeoutSeconds 阻塞超时（秒，最小 1）；超时会 dispose 订阅并抛异常
     * @param what           调用名（用于超时/中断的报错文案，如「验收判定」）
     * @return 结果消息（可能为 null——流未发射任何元素即完成）
     * @throws RuntimeException 超时 / 被中断 / 智能体出错
     */
    public static Msg blockingCall(ReActAgent agent, String userPrompt, Class<?> schema,
                                   long timeoutSeconds, String what) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(userPrompt).build();
        AtomicReference<Msg> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        var flux = (schema == null) ? agent.call(List.of(userMsg)) : agent.call(List.of(userMsg), schema);
        Disposable d = flux.subscribeOn(Schedulers.boundedElastic())
                .subscribe(ref::set, e -> {
                    err.set(e);
                    latch.countDown();
                }, latch::countDown);
        try {
            if (!latch.await(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)) {
                d.dispose();
                throw new RuntimeException(what + "超时（" + timeoutSeconds + "s）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.dispose();
            throw new RuntimeException(what + "被中断", e);
        }
        if (err.get() != null) {
            Throwable t = err.get();
            throw (t instanceof RuntimeException re) ? re : new RuntimeException(t);
        }
        return ref.get();
    }

    /**
     * 从结果消息元数据提取 {@code _structured_output} 并转 POJO。
     *
     * @return 转换后的对象；消息为空 / 无结构化输出 / 解析失败一律返回 null（调用方按保守语义处理）
     */
    public static <T> T extractStructured(Msg msg, Class<T> cls) {
        if (msg == null || msg.getMetadata() == null) {
            log.warn("结构化输出缺失（{}）", cls.getSimpleName());
            return null;
        }
        Object raw = msg.getMetadata().get("_structured_output");
        if (raw == null) {
            log.warn("metadata 无 _structured_output（{}）", cls.getSimpleName());
            return null;
        }
        try {
            return JsonSchemaUtils.convertToObject(raw, cls);
        } catch (Exception e) {
            log.warn("结构化输出解析失败（{}）：{}", cls.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
