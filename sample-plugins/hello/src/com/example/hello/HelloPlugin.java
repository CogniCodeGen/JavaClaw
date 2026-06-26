package com.example.hello;

import com.javaclaw.plugin.api.JavaClawPlugin;
import com.javaclaw.plugin.api.PluginContext;
import com.javaclaw.plugin.api.PluginTool;
import com.javaclaw.plugin.api.ToolProvider;
import com.javaclaw.plugin.api.exec.ServiceHandle;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1 示例插件 —— 仅依赖 plugin-api，演示插件如何使用宿主托管能力：
 * <ul>
 *   <li>同步调用：{@code ctx.exec().call(...)} 在托管虚拟线程上阻塞取值；</li>
 *   <li>后台监听：{@code ctx.exec().background(...)} 注册一个心跳循环（长活虚拟线程）；</li>
 *   <li>CHAT 能力：{@code ctx.chat().ask(...)} 发起一轮 AI 对话。</li>
 * </ul>
 *
 * <p>插件不创建任何真实线程，全部经 {@code ctx.exec()} 申请；停用时宿主统一回收。</p>
 */
public class HelloPlugin implements JavaClawPlugin, ToolProvider {

    private ServiceHandle heartbeat;

    @Override
    public void start(PluginContext ctx) throws Exception {
        log("启动");

        // 0) 读取注入的配置（在插件中心填写，secret 项已解密）
        String greeting = ctx.config().get("greeting", "你好（默认问候语）");
        log("配置 greeting = " + greeting);

        // 1) 同步调用：在宿主托管虚拟线程上执行并阻塞取回结果
        String calc = ctx.exec().call(() -> "1 + 1 = " + (1 + 1));
        log("同步调用结果：" + calc);

        // 2) 后台监听：注册一个心跳循环，跑在宿主命名的专属虚拟线程上
        heartbeat = ctx.exec().background("hello-heartbeat", c -> {
            int n = 0;
            while (!c.isCancelled()) {
                n++;
                log("心跳 #" + n + "（线程：" + Thread.currentThread() + "）");
                // 3) CHAT 能力：发起一轮对话（无模型/网络时记录异常但不影响心跳）
                try {
                    String reply = ctx.chat().ask("用一句话讲个冷笑话");
                    log("AI 回复：" + reply);
                } catch (Exception e) {
                    log("CHAT 调用异常（已忽略）：" + e.getMessage());
                }
                Thread.sleep(3000);
            }
            log("心跳循环已退出");
        });

        log("启动完成（后台心跳运行中）");
    }

    /** 向聊天编排器贡献工具：演示"聊天中调用插件能力"。 */
    @Override
    public List<PluginTool> tools() {
        return List.of(new PluginTool(
                "hello_shout",
                "把给定文本转为大写并加三个叹号（示例工具，用于验证聊天调用插件）",
                List.of(new PluginTool.Param("text", "要处理的文本", true)),
                argsJson -> {
                    String text = jsonString(argsJson, "text");
                    String shout = (text == null ? "" : text.toUpperCase()) + "!!!";
                    log("收到工具调用 hello_shout，返回：" + shout);
                    return shout;
                }));
    }

    /** 极简提取 JSON 对象里某个字符串字段的值（示例用，避免引入 JSON 依赖）。 */
    private static String jsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    @Override
    public void stop() {
        log("停用");
        if (heartbeat != null) {
            heartbeat.cancel();
        }
    }

    private void log(String msg) {
        System.out.println("[HelloPlugin] " + msg);
    }
}
