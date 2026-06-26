package com.javaclaw.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置数据类
 *
 * <p>支持两种传输：</p>
 * <ul>
 *   <li><b>stdio</b>：填写 {@link #command} + {@link #args} + {@link #env}，
 *       由本地进程承载 MCP 协议。</li>
 *   <li><b>http</b>（streamable-HTTP / 简化 JSON-RPC over POST）：填写 {@link #url}
 *       + 可选 {@link #headers}，由远程 HTTP 端点承载 MCP 协议。</li>
 * </ul>
 *
 * <p>{@link #getTransport()} 按是否填写 {@code url} 自动推断：
 * 设置 {@code url} 时为 {@code http}，否则为 {@code stdio}。</p>
 *
 * @author JavaClaw
 */
public class McpServerConfig {

    /** 服务器唯一名称 */
    private String name;

    /** stdio：启动命令（如 "npx"、"python"、"node"） */
    private String command;

    /** stdio：命令参数列表 */
    private List<String> args;

    /** stdio：环境变量（可选） */
    private Map<String, String> env;

    /** http：远程 MCP 端点 URL（与 command 二选一） */
    private String url;

    /** http：HTTP 请求头（如 Authorization 等，可选） */
    private Map<String, String> headers;

    /** 是否启用 */
    private boolean enabled;

    public McpServerConfig() {
        this.args = List.of();
        this.env = Map.of();
        this.headers = Map.of();
        this.enabled = true;
    }

    public McpServerConfig(String name, String command, List<String> args,
                           Map<String, String> env, boolean enabled) {
        this.name = name;
        this.command = command;
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
        this.headers = Map.of();
        this.enabled = enabled;
    }

    /**
     * HTTP 传输专用构造器（command 为 null）
     */
    public McpServerConfig(String name, String url, Map<String, String> headers, boolean enabled) {
        this.name = name;
        this.url = url;
        this.headers = headers != null ? headers : Map.of();
        this.args = List.of();
        this.env = Map.of();
        this.enabled = enabled;
    }

    /**
     * 推断的传输类型：{@code "http"} 或 {@code "stdio"}。
     *
     * <p>{@link JsonIgnore} 防止 Jackson 把这个派生字段写入 JSON：
     * 一旦持久化到 {@code mcp-servers.json}，下次读取时 Jackson 找不到对应 setter 会抛
     * {@code UnrecognizedPropertyException}，导致整份配置加载失败、表象上像"配置丢了"。</p>
     */
    @JsonIgnore
    public String getTransport() {
        return (url != null && !url.isBlank()) ? "http" : "stdio";
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args != null ? args : List.of();
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env != null ? env : Map.of();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? headers : Map.of();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "McpServerConfig{name='" + name + "', transport=" + getTransport()
                + (getTransport().equals("http")
                    ? ", url='" + url + "'"
                    : ", command='" + command + "', args=" + args)
                + ", enabled=" + enabled + '}';
    }
}
